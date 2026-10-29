package com.example.order.core.web.auth;

import com.example.order.core.service.AuthService.AuthenticatedUser;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;

public final class TokenSecurityContext implements SecurityContext {

    private final AuthenticatedUser user;
    private final boolean secure;
    private final String authenticationScheme;

    public TokenSecurityContext(AuthenticatedUser user, boolean secure, String authenticationScheme) {
        this.user = user;
        this.secure = secure;
        this.authenticationScheme = authenticationScheme;
    }

    @Override
    public Principal getUserPrincipal() {
        return () -> user.username();
    }

    @Override
    public boolean isUserInRole(String role) {
        return user.roles().contains(role);
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public String getAuthenticationScheme() {
        return authenticationScheme;
    }
}
