package com.example.order.core.web.auth;

import com.example.order.core.security.TokenService;
import com.example.order.core.service.AuthService.AuthSession;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Provider
@Priority(Priorities.AUTHORIZATION)
public class CsrfFilter implements ContainerRequestFilter {

    @Inject
    private TokenService tokenService;

    @Override
    public void filter(ContainerRequestContext request) {
        if (!isStateChanging(request.getMethod())) {
            return;
        }
        if (!Boolean.TRUE.equals(request.getProperty("auth.cookie"))) {
            return;
        }

        AuthSession session = (AuthSession) request.getProperty("auth.session");
        if (session == null) {
            return;
        }

        String header = request.getHeaders().getFirst(AuthTokens.CSRF_HEADER);
        String cookie = AuthTokens.cookie(request.getHeaders(), AuthTokens.CSRF_COOKIE);
        if (header == null || cookie == null
                || !constantTimeEquals(header, cookie)
                || !constantTimeEquals(tokenService.hash(header), session.csrfTokenHash())) {
            request.abortWith(AuthResponses.forbidden(request, "CSRF トークンが不正です"));
        }
    }

    private boolean isStateChanging(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
