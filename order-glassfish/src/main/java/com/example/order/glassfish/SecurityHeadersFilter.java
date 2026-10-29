package com.example.order.glassfish;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Adds the security headers to every response, static files included.
 *
 * <p>The portable core cannot do this: it has no Servlet API, and Quarkus has no Servlet
 * container. Quarkus sets the same headers with {@code quarkus.http.header.*} in
 * {@code application.properties}; the other runtimes get a copy of this filter.
 */
@WebFilter("/*")
public class SecurityHeadersFilter implements Filter {

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                    + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
            httpResponse.setHeader("X-Content-Type-Options", "nosniff");
            httpResponse.setHeader("Referrer-Policy", "no-referrer");
        }
        chain.doFilter(request, response);
    }
}
