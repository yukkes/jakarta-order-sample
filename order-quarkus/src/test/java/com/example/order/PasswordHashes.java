package com.example.order;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Base64;

/** Builds stored hashes with arbitrary parameters, independent of {@code PasswordHasher}. */
public final class PasswordHashes {

    private PasswordHashes() {
    }

    public static String pbkdf2(String password, int iterations) {
        try {
            byte[] salt = new byte[16];
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256))
                    .getEncoded();
            Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
            return "$pbkdf2-sha256$i=" + iterations + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(key);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
