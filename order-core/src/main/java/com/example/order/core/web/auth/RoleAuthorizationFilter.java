package com.example.order.core.web.auth;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;

import java.util.Arrays;
import java.util.logging.Logger;

@Provider
@Priority(Priorities.AUTHORIZATION + 100)
public class RoleAuthorizationFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RoleAuthorizationFilter.class.getName());

    @Context
    private ResourceInfo resourceInfo;

    /** Deny by default: an endpoint without any security annotation is a mistake, not a public API. */
    @Override
    public void filter(ContainerRequestContext request) {
        if (resourceInfo.getResourceMethod() == null) {
            return; // automatic OPTIONS response; AuthFilter rejects everything else
        }
        if (Endpoints.has(resourceInfo, PublicEndpoint.class) || Endpoints.has(resourceInfo, OptionalAuth.class)) {
            return;
        }

        RequireRole roles = Endpoints.find(resourceInfo, RequireRole.class);
        if (roles == null) {
            LOG.warning(() -> "endpoint has no @RequireRole, @PublicEndpoint or @OptionalAuth: "
                    + resourceInfo.getResourceClass().getName() + "." + resourceInfo.getResourceMethod().getName());
            request.abortWith(AuthResponses.forbidden(request, "必要なロールがありません"));
            return;
        }

        SecurityContext securityContext = request.getSecurityContext();
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            request.abortWith(AuthResponses.unauthorized(request, "認証が必要です"));
            return;
        }
        boolean allowed = Arrays.stream(roles.value())
                .anyMatch(securityContext::isUserInRole);
        if (!allowed) {
            request.abortWith(AuthResponses.forbidden(request, "必要なロールがありません"));
        }
    }
}
