package com.example.order.core.security;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecuritySettingsTest {

    private static final URI HTTP = URI.create("http://localhost:8080/api/auth/session");
    private static final URI HTTPS = URI.create("https://example.com/api/auth/session");

    @Test
    void createsNoUsersByDefault() {
        SecuritySettings settings = load(Map.of());

        assertTrue(settings.initialPassword("admin").isEmpty());
        assertTrue(settings.initialPassword("user").isEmpty());
        assertEquals(Path.of("data/import"), settings.importDir());
    }

    @Test
    void demoUsersUseWellKnownPasswords() {
        SecuritySettings settings = load(Map.of("demo-users", "true"));

        assertEquals(Optional.of("admin"), settings.initialPassword("admin"));
        assertEquals(Optional.of("user"), settings.initialPassword("user"));
    }

    @Test
    void configuredPasswordWinsOverDemoUsers() {
        SecuritySettings settings = load(Map.of("demo-users", "true", "admin-password", "s3cret"));

        assertEquals(Optional.of("s3cret"), settings.initialPassword("admin"));
        assertEquals(Optional.of("user"), settings.initialPassword("user"));
        assertThrows(IllegalArgumentException.class, () -> settings.initialPassword("someone-else"));
    }

    @Test
    void forwardedProtoIsIgnoredUnlessTrusted() {
        SecuritySettings settings = load(Map.of());

        assertFalse(settings.isSecureRequest(HTTP, "https"));
        assertTrue(settings.isSecureRequest(HTTPS, null));
        assertFalse(settings.secureCookie(HTTP, "https"));
    }

    @Test
    void trustedForwardedProtoDecides() {
        SecuritySettings settings = load(Map.of("trust-forwarded-proto", "true"));

        assertTrue(settings.isSecureRequest(HTTP, "https"));
        assertFalse(settings.isSecureRequest(HTTPS, "http"));
        assertTrue(settings.isSecureRequest(HTTPS, null));
        assertTrue(settings.secureCookie(HTTP, "HTTPS"));
    }

    @Test
    void cookieSecureOverridesTheRequest() {
        assertTrue(load(Map.of("cookie-secure", "always")).secureCookie(HTTP, null));
        assertFalse(load(Map.of("cookie-secure", "NEVER")).secureCookie(HTTPS, null));
        assertThrows(IllegalStateException.class, () -> load(Map.of("cookie-secure", "sometimes")));
    }

    private SecuritySettings load(Map<String, String> values) {
        SecuritySettings settings = new SecuritySettings();
        settings.load(key -> Optional.ofNullable(values.get(key)));
        return settings;
    }
}
