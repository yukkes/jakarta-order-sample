package com.example.order.liberty;

import com.example.order.core.web.SecurityHeaders;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Applies {@link SecurityHeaders} to every response, static files included.
 *
 * <p>This adapter has no other job: the header names and values live in the portable
 * core, which also ships the UI they protect. Quarkus sets the same headers through
 * {@code quarkus.http.header.*}. Because the three WAR adapters share the values,
 * a CSP change is a one-line edit in core instead of three edits that can drift.
 */
@WebFilter("/*")
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse) {
            SecurityHeaders.responseHeaders().forEach(httpResponse::setHeader);
        }
        chain.doFilter(request, response);
    }
}
