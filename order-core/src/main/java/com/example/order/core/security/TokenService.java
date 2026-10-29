package com.example.order.core.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class TokenService {

    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final String HMAC = "HmacSHA256";

    /**
     * Created on first use instead of at construction: a {@link SecureRandom} instance that
     * is reachable from the image heap makes GraalVM native image fail the build, so the
     * field stays {@code null} until a token is actually generated.
     */
    private SecureRandom random;

    /**
     * {@link Mac} is not thread-safe and {@code Mac.getInstance} is comparatively expensive,
     * so each worker thread keeps one instance. {@code doFinal} resets it for the next use.
     */
    private final ThreadLocal<Mac> macs = new ThreadLocal<>();

    /**
     * {@code Json.createXxx} resolves the provider through a service lookup on every call
     * on some runtimes, which dominates per-request verification. Resolve it once.
     */
    private volatile JsonProvider jsonProvider;

    /** Opaque random value, used as the refresh token. */
    public String newToken() {
        return randomToken(32);
    }

    public String newCsrfToken() {
        return randomToken(24);
    }

    /** SHA-256 of the refresh token; only the hash is stored. */
    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return BASE64.encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /**
     * Issues a stateless HS256 access token. The payload is a JWT built with JSON-P, so the
     * portable core does not need a JOSE library. Every value needed to authorise a request
     * (user, roles, CSRF token, session id) is in the token, so verification needs no DB.
     */
    public String accessToken(byte[] secret, long userId, String username, Set<String> roles,
                              long sessionId, String csrfToken, Instant issuedAt, Instant expiresAt) {
        JsonObject header = jsonProvider().createObjectBuilder()
                .add("alg", "HS256")
                .add("typ", "JWT")
                .build();
        JsonObject claims = jsonProvider().createObjectBuilder()
                .add("sub", userId)
                .add("name", username)
                .add("roles", jsonProvider().createArrayBuilder(roles))
                .add("sid", sessionId)
                .add("csrf", csrfToken)
                .add("iat", issuedAt.getEpochSecond())
                .add("exp", expiresAt.getEpochSecond())
                .build();
        String signingInput = encode(header.toString()) + "." + encode(claims.toString());
        return signingInput + "." + encode(hmac(secret, signingInput));
    }

    /** Verifies the signature and expiry; returns the claims, or empty for anything invalid. */
    public Optional<AccessToken> parseAccessToken(byte[] secret, String token) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] presented;
        try {
            presented = BASE64_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(hmac(secret, signingInput), presented)) {
            return Optional.empty();
        }

        try {
            JsonObject header = json(parts[0]);
            // Never trust the client's choice of algorithm.
            if (!"HS256".equals(header.getString("alg", ""))) {
                return Optional.empty();
            }
            JsonObject claims = json(parts[1]);
            long expires = claims.getJsonNumber("exp").longValue();
            if (expires <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            Set<String> roles = new LinkedHashSet<>();
            JsonArray array = claims.getJsonArray("roles");
            if (array != null) {
                for (JsonValue value : array) {
                    if (value instanceof JsonString string) {
                        roles.add(string.getString());
                    }
                }
            }
            return Optional.of(new AccessToken(
                    claims.getJsonNumber("sub").longValue(),
                    claims.getString("name"),
                    Set.copyOf(roles),
                    claims.getJsonNumber("sid").longValue(),
                    claims.getString("csrf"),
                    Instant.ofEpochSecond(expires)));
        } catch (RuntimeException exception) {
            // Malformed payload: treat it exactly like a bad signature.
            return Optional.empty();
        }
    }

    private JsonObject json(String encoded) {
        String value = new String(BASE64_DECODER.decode(encoded), StandardCharsets.UTF_8);
        try (JsonReader reader = jsonProvider().createReader(new StringReader(value))) {
            return reader.readObject();
        }
    }

    private byte[] hmac(byte[] secret, String value) {
        try {
            Mac mac = macs.get();
            if (mac == null) {
                mac = Mac.getInstance(HMAC);
                mac.init(new SecretKeySpec(secret, HMAC));
                macs.set(mac);
            }
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }

    private static String encode(byte[] value) {
        return BASE64.encodeToString(value);
    }

    private static String encode(String value) {
        return encode(value.getBytes(StandardCharsets.UTF_8));
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random().nextBytes(value);
        return BASE64.encodeToString(value);
    }

    private JsonProvider jsonProvider() {
        JsonProvider value = jsonProvider;
        if (value == null) {
            synchronized (this) {
                value = jsonProvider;
                if (value == null) {
                    value = JsonProvider.provider();
                    jsonProvider = value;
                }
            }
        }
        return value;
    }

    private SecureRandom random() {
        SecureRandom value = random;
        if (value == null) {
            synchronized (this) {
                value = random;
                if (value == null) {
                    value = new SecureRandom();
                    random = value;
                }
            }
        }
        return value;
    }

    public record AccessToken(long userId, String username, Set<String> roles, long sessionId,
                              String csrfToken, Instant expiresAt) {
    }
}
