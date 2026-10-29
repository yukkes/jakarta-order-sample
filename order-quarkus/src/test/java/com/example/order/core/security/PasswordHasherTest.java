package com.example.order.core.security;

import com.example.order.PasswordHashes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @BeforeAll
    static void init() {
        HASHER.init();
    }

    @Test
    void hashesWithCurrentPolicy() {
        String hash = HASHER.hash("secret");

        assertTrue(hash.matches("\\$pbkdf2-sha256\\$i=600000\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}"), hash);
        assertTrue(HASHER.matches("secret", hash));
        assertFalse(HASHER.matches("Secret", hash));
        assertFalse(HASHER.matches(null, hash));
        assertFalse(HASHER.needsRehash(hash));
    }

    @Test
    void verifiesAndFlagsHashesWithOlderIterationCount() {
        String weak = PasswordHashes.pbkdf2("secret", 210_000);

        assertTrue(HASHER.matches("secret", weak));
        assertTrue(HASHER.needsRehash(weak));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "garbage",
            "$argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHQ$aGFzaGhhc2g",
            "$pbkdf2-sha256$i=x$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "$pbkdf2-sha256$i=600000$AAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "$pbkdf2-sha256$i=600000$AAAAAAAAAAAAAAAAAAAAAA",
            "$pbkdf2-sha256$i=600000$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA$extra"
    })
    void rejectsMalformedHashesWithoutThrowing(String stored) {
        assertFalse(HASHER.matches("secret", stored));
        assertTrue(HASHER.needsRehash(stored));
    }

    @ParameterizedTest
    @ValueSource(ints = {1_000, 99_999, 2_000_001, 9_999_999})
    void rejectsIterationCountsOutsideTheAllowedRange(int iterations) {
        String stored = "$pbkdf2-sha256$i=" + iterations
                + "$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        long started = System.nanoTime();

        assertFalse(HASHER.matches("secret", stored));
        // Rejected before deriving: even 1,000 iterations would be cheap, but 9,999,999 would not.
        assertTrue(System.nanoTime() - started < 100_000_000L);
    }

    @Test
    void dummyCheckDoesNotThrow() {
        HASHER.matchesDummy(null);
        HASHER.matchesDummy("");
        HASHER.matchesDummy("anything");
    }
}
