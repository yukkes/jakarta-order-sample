package com.example.order.core.security;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Runtime-neutral security settings. Core cannot use MicroProfile Config, so
 * each key is read from the system property {@code order.<key>} first and the
 * environment variable {@code ORDER_<KEY>} second (for example
 * {@code order.import-dir} / {@code ORDER_IMPORT_DIR}). Values are never logged.
 */
@ApplicationScoped
public class SecuritySettings {

    private static final Logger LOG = Logger.getLogger(SecuritySettings.class.getName());

    /** When auth cookies get the {@code Secure} attribute. */
    public enum CookieSecure {
        /** Always; use this in production. */
        ALWAYS,
        /** When {@link #isSecureRequest} says the request came over HTTPS (default). */
        AUTO,
        /** Never; only for plain-HTTP local development. */
        NEVER
    }

    private Path importDir;
    private boolean demoUsers;
    private String adminPassword;
    private String userPassword;
    private CookieSecure cookieSecure;
    private boolean trustForwardedProto;

    @PostConstruct
    void init() {
        load(SecuritySettings::lookup);
        LOG.info(() -> "import directory: " + importDir.toAbsolutePath().normalize());
        if (demoUsers) {
            LOG.warning("order.demo-users=true: demo accounts admin/admin and user/user are enabled. "
                    + "Do not use this setting in production.");
        }
    }

    /** Applies settings from {@code source}, which maps a key such as {@code import-dir} to its value. */
    void load(Function<String, Optional<String>> source) {
        importDir = Path.of(source.apply("import-dir").orElse("data/import"));
        demoUsers = source.apply("demo-users").map(Boolean::parseBoolean).orElse(false);
        adminPassword = source.apply("admin-password").orElse(null);
        userPassword = source.apply("user-password").orElse(null);
        String secure = source.apply("cookie-secure").orElse("auto");
        try {
            cookieSecure = CookieSecure.valueOf(secure.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("order.cookie-secure must be always, auto or never: " + secure);
        }
        trustForwardedProto = source.apply("trust-forwarded-proto").map(Boolean::parseBoolean).orElse(false);
    }

    /** Directory that batch CSV imports may read from. */
    public Path importDir() {
        return importDir;
    }

    /**
     * Password for a seeded account: the configured one, else the well-known demo
     * password when demo users are enabled, else empty (the account is not created).
     */
    public Optional<String> initialPassword(String username) {
        String configured = switch (username) {
            case "admin" -> adminPassword;
            case "user" -> userPassword;
            default -> throw new IllegalArgumentException("not a seeded account: " + username);
        };
        if (configured != null) {
            return Optional.of(configured);
        }
        return demoUsers ? Optional.of(username) : Optional.empty();
    }

    /**
     * Whether the client reached us over HTTPS. {@code X-Forwarded-Proto} is honoured
     * only with {@code order.trust-forwarded-proto=true}, because any client can send it
     * unless a TLS-terminating proxy in front overwrites it.
     */
    public boolean isSecureRequest(URI requestUri, String forwardedProto) {
        if (trustForwardedProto && forwardedProto != null && !forwardedProto.isBlank()) {
            return "https".equalsIgnoreCase(forwardedProto.trim());
        }
        return "https".equalsIgnoreCase(requestUri.getScheme());
    }

    /** Whether auth cookies set in response to this request get the {@code Secure} attribute. */
    public boolean secureCookie(URI requestUri, String forwardedProto) {
        return switch (cookieSecure) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> isSecureRequest(requestUri, forwardedProto);
        };
    }

    private static Optional<String> lookup(String key) {
        String value = System.getProperty("order." + key);
        if (value == null || value.isBlank()) {
            value = System.getenv("ORDER_" + key.replace('-', '_').toUpperCase(Locale.ROOT));
        }
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
}
