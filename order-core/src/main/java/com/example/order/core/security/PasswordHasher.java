package com.example.order.core.security;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PBKDF2-HMAC-SHA256 password hashing with the JDK only. Memory use is constant
 * (unlike Argon2), so concurrent logins cannot exhaust the heap. Hashes are
 * stored as {@code $pbkdf2-sha256$i=<iterations>$<salt>$<hash>} (Base64 without
 * padding), so the parameters travel with the hash and can be upgraded on login.
 */
@ApplicationScoped
public class PasswordHasher {

    private static final Logger LOG = Logger.getLogger(PasswordHasher.class.getName());

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    /** OWASP Password Storage Cheat Sheet: PBKDF2-HMAC-SHA256 needs at least 600,000 iterations. */
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BYTES = 32;

    /** Bounds for values read back from the database, so a bad row cannot burn CPU. */
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 2_000_000;

    /** 16-byte salt = 22 Base64 chars, 32-byte key = 43 Base64 chars. */
    private static final Pattern FORMAT = Pattern.compile(
            "\\$pbkdf2-sha256\\$i=(\\d{1,7})\\$([A-Za-z0-9+/]{22})\\$([A-Za-z0-9+/]{43})");

    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private final SecureRandom random = new SecureRandom();

    /** Checked for unknown users so that the response time does not reveal whether a user exists. */
    private String dummyHash;

    @PostConstruct
    void init() {
        dummyHash = hash("timing-equalizer");
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);
        return "$pbkdf2-sha256$i=" + ITERATIONS + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(key);
    }

    /** Returns false, never throws, for a null password or a malformed or out-of-bounds stored hash. */
    public boolean matches(String password, String stored) {
        if (password == null) {
            return false;
        }
        return parse(stored)
                .map(hash -> MessageDigest.isEqual(hash.key(), derive(password, hash.salt(), hash.iterations())))
                .orElse(false);
    }

    /** Spends the same work as {@link #matches} for a user that does not exist. */
    public void matchesDummy(String password) {
        matches(password == null ? "" : password, dummyHash);
    }

    /** True when {@code stored} was hashed with an iteration count other than the current policy. */
    public boolean needsRehash(String stored) {
        return parse(stored).map(hash -> hash.iterations() != ITERATIONS).orElse(true);
    }

    private Optional<StoredHash> parse(String stored) {
        Matcher matcher = stored == null ? null : FORMAT.matcher(stored);
        if (matcher == null || !matcher.matches()) {
            LOG.warning("stored password hash is not a $pbkdf2-sha256$ string");
            return Optional.empty();
        }
        int iterations = Integer.parseInt(matcher.group(1));
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            LOG.warning("stored password hash has an iteration count outside the allowed range");
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredHash(
                    iterations, DECODER.decode(matcher.group(2)), DECODER.decode(matcher.group(3))));
        } catch (IllegalArgumentException exception) {
            LOG.warning("stored password hash is not valid Base64");
            return Optional.empty();
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BYTES * 8);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("PBKDF2 is not available", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private record StoredHash(int iterations, byte[] salt, byte[] key) {
    }
}
