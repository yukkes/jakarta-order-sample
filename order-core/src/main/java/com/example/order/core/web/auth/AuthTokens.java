package com.example.order.core.web.auth;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Optional;

public final class AuthTokens {

    public static final String SESSION_COOKIE = "ORDER_SESSION";
    /** Refresh token; only sent to {@code /api/auth}. */
    public static final String REFRESH_COOKIE = "ORDER_REFRESH";
    public static final String REFRESH_COOKIE_PATH = "/api/auth";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private AuthTokens() {
    }

    public static Optional<TokenSource> extract(HttpHeaders headers) {
        return extract(headers.getRequestHeaders());
    }

    public static Optional<TokenSource> extract(MultivaluedMap<String, String> headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).trim();
            if (!token.isEmpty()) {
                return Optional.of(new TokenSource(token, false));
            }
        }

        String cookie = cookie(headers, SESSION_COOKIE);
        if (cookie != null && !cookie.isBlank()) {
            return Optional.of(new TokenSource(cookie, true));
        }
        return Optional.empty();
    }

    public static String cookie(HttpHeaders headers, String name) {
        return cookie(headers.getRequestHeaders(), name);
    }

    public static String cookie(MultivaluedMap<String, String> headers, String name) {
        String cookieHeader = headers.getFirst(HttpHeaders.COOKIE);
        if (cookieHeader == null) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String candidate = part.trim();
            int separator = candidate.indexOf('=');
            if (separator > 0 && candidate.substring(0, separator).trim().equals(name)) {
                return candidate.substring(separator + 1).trim();
            }
        }
        return null;
    }

    public record TokenSource(String token, boolean cookie) {
    }
}
