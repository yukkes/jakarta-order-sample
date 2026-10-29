package com.example.order.core.web.auth;

import com.example.order.core.security.SecuritySettings;
import com.example.order.core.service.AuthService;
import com.example.order.core.service.AuthService.AuthSession;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

import java.util.Optional;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {

    static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    @Inject
    private AuthService authService;

    @Inject
    private SecuritySettings settings;

    @Context
    private ResourceInfo resourceInfo;

    /**
     * Post-matching, so the decision comes from annotations on the matched resource
     * method rather than from the request path, which can be spelled many ways.
     */
    @Override
    public void filter(ContainerRequestContext request) {
        if (resourceInfo.getResourceMethod() == null) {
            // Only the runtime's automatic OPTIONS response has no resource method; fail closed otherwise.
            if (!"OPTIONS".equalsIgnoreCase(request.getMethod())) {
                request.abortWith(AuthResponses.unauthorized(request, "認証が必要です"));
            }
            return;
        }
        if (Endpoints.has(resourceInfo, PublicEndpoint.class)) {
            return;
        }
        boolean optional = Endpoints.has(resourceInfo, OptionalAuth.class);

        Optional<AuthTokens.TokenSource> source = AuthTokens.extract(request.getHeaders());
        if (source.isEmpty()) {
            if (optional) {
                return;
            }
            request.abortWith(AuthResponses.unauthorized(request, "認証が必要です"));
            return;
        }

        Optional<AuthSession> session = authService.authenticate(source.get().token());
        if (session.isEmpty()) {
            request.abortWith(AuthResponses.unauthorized(request, "セッションが無効または期限切れです"));
            return;
        }

        AuthSession auth = session.get();
        request.setProperty("auth.session", auth);
        request.setProperty("auth.cookie", source.get().cookie());
        request.setSecurityContext(new TokenSecurityContext(
                auth.user(),
                isSecure(request),
                source.get().cookie() ? "Cookie" : "Bearer"));
    }

    private boolean isSecure(ContainerRequestContext request) {
        return settings.isSecureRequest(
                request.getUriInfo().getRequestUri(), request.getHeaderString(FORWARDED_PROTO));
    }
}
