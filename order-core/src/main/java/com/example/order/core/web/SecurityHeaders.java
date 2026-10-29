package com.example.order.core.web;

import java.util.Map;

/**
 * Security headers that every runtime has to set on every response.
 *
 * <p>The values live here, in the portable core, because the core also ships the static
 * UI ({@code META-INF/resources}) that the CSP protects. Each runtime only decides
 * <em>where</em> to apply them:
 *
 * <ul>
 *   <li>Quarkus: {@code quarkus.http.header.*} in {@code application.properties}
 *       (it has no Servlet container, and its header config also covers static files).
 *       {@code SecurityHeadersTest} fails the build if the two ever disagree.</li>
 *   <li>Liberty / WildFly / GlassFish: the {@code SecurityHeadersFilter} of each adapter.</li>
 * </ul>
 */
public final class SecurityHeaders {

    /**
     * No inline script or style, so the UI keeps its JavaScript and CSS in external
     * files only (see AGENTS.md). {@code img-src data:} is needed for the favicon.
     */
    public static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                    + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    public static final String X_CONTENT_TYPE_OPTIONS = "nosniff";

    public static final String REFERRER_POLICY = "no-referrer";

    /** Header name/value pairs, in the order they are set. */
    private static final Map<String, String> RESPONSE_HEADERS = Map.of(
            "Content-Security-Policy", CONTENT_SECURITY_POLICY,
            "X-Content-Type-Options", X_CONTENT_TYPE_OPTIONS,
            "Referrer-Policy", REFERRER_POLICY);

    private SecurityHeaders() {
    }

    public static Map<String, String> responseHeaders() {
        return RESPONSE_HEADERS;
    }
}
