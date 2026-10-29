package com.example.order.core.web.auth;

import com.example.order.api.model.AuthStatusResponse;
import com.example.order.api.model.LoginRequest;
import com.example.order.api.model.LoginResponse;
import com.example.order.api.model.UserResponse;
import com.example.order.core.security.SecuritySettings;
import com.example.order.core.service.AuthService;
import com.example.order.core.service.AuthService.LoginResult;
import com.example.order.core.web.error.AuthException;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.util.ArrayList;
import java.util.List;

@Path("/auth")
@Produces({MediaType.APPLICATION_JSON, "application/problem+json"})
public class AuthResource {

    private static final long REFRESH_MAX_AGE = 8 * 60 * 60;

    @Inject
    private AuthService authService;

    @Inject
    private SecuritySettings settings;

    @Context
    private UriInfo uriInfo;

    @Context
    private HttpHeaders headers;

    @POST
    @Path("/session")
    @PublicEndpoint
    @Consumes(MediaType.APPLICATION_JSON)
    public Response session(@Valid LoginRequest request) {
        LoginResult result = authService.login(request.getUsername(), request.getPassword());
        return withSessionCookies(Response.ok(toUserResponse(result)), result).build();
    }

    /** Bearer-token clients get the access token (and a refresh token) in the body. */
    @POST
    @Path("/login")
    @PublicEndpoint
    @Consumes(MediaType.APPLICATION_JSON)
    public LoginResponse login(@Valid LoginRequest request) {
        return toLoginResponse(authService.login(request.getUsername(), request.getPassword()));
    }

    /**
     * The only endpoint that reads session state from the database. Rotates the refresh
     * token, so a replayed refresh token is rejected.
     */
    @POST
    @Path("/refresh")
    @PublicEndpoint
    public Response refresh() {
        String refreshToken = AuthTokens.cookie(headers, AuthTokens.REFRESH_COOKIE);
        LoginResult result = authService.refresh(refreshToken)
                .orElseThrow(AuthException::invalidCredentials);
        return withSessionCookies(Response.ok(toLoginResponse(result)), result).build();
    }

    @GET
    @Path("/me")
    @RequireRole({"USER", "ADMIN"})
    public UserResponse me(@Context SecurityContext securityContext) {
        return toUserResponse(securityContext);
    }

    @GET
    @Path("/status")
    @OptionalAuth
    public AuthStatusResponse status(@Context SecurityContext securityContext) {
        AuthStatusResponse response = new AuthStatusResponse();
        boolean authenticated = securityContext.getUserPrincipal() != null;
        response.setAuthenticated(authenticated);
        if (authenticated) {
            response.setUsername(securityContext.getUserPrincipal().getName());
            response.setRoles(roles(securityContext));
        } else {
            response.setRoles(List.of());
        }
        return response;
    }

    @POST
    @Path("/logout")
    @RequireRole({"USER", "ADMIN"})
    public Response logout() {
        authService.logout(AuthTokens.cookie(headers, AuthTokens.REFRESH_COOKIE));

        return Response.noContent()
                .cookie(
                        cookie(AuthTokens.SESSION_COOKIE, "", 0, "/", true),
                        cookie(AuthTokens.CSRF_COOKIE, "", 0, "/", false),
                        cookie(AuthTokens.REFRESH_COOKIE, "", 0, AuthTokens.REFRESH_COOKIE_PATH, true))
                .build();
    }

    private Response.ResponseBuilder withSessionCookies(Response.ResponseBuilder builder, LoginResult result) {
        int accessMaxAge = (int) Math.max(settings.accessTokenTtl().toSeconds(), 1);
        return builder.cookie(
                cookie(AuthTokens.SESSION_COOKIE, result.accessToken(), accessMaxAge, "/", true),
                cookie(AuthTokens.CSRF_COOKIE, result.csrfToken(), (int) REFRESH_MAX_AGE, "/", false),
                cookie(AuthTokens.REFRESH_COOKIE, result.refreshToken(), (int) REFRESH_MAX_AGE,
                        AuthTokens.REFRESH_COOKIE_PATH, true));
    }

    private LoginResponse toLoginResponse(LoginResult result) {
        LoginResponse response = new LoginResponse();
        response.setToken(result.accessToken());
        response.setRefreshToken(result.refreshToken());
        response.setExpiresAt(result.expiresAt());
        response.setUsername(result.user().username());
        response.setRoles(List.copyOf(result.user().roles()));
        return response;
    }

    private UserResponse toUserResponse(LoginResult result) {
        UserResponse response = new UserResponse();
        response.setUsername(result.user().username());
        response.setRoles(List.copyOf(result.user().roles()));
        return response;
    }

    private UserResponse toUserResponse(SecurityContext securityContext) {
        UserResponse response = new UserResponse();
        response.setUsername(securityContext.getUserPrincipal().getName());
        response.setRoles(roles(securityContext));
        return response;
    }

    private List<String> roles(SecurityContext securityContext) {
        List<String> roles = new ArrayList<>();
        if (securityContext.isUserInRole("ADMIN")) {
            roles.add("ADMIN");
        }
        if (securityContext.isUserInRole("USER")) {
            roles.add("USER");
        }
        return roles;
    }

    /** All cookies are SameSite=Strict; {@code refresh} lives under {@code /api/auth}. */
    private NewCookie cookie(String name, String value, int maxAge, String path, boolean httpOnly) {
        return new NewCookie(
                new Cookie(name, value, path, null),
                null,
                maxAge,
                null,
                settings.secureCookie(uriInfo.getRequestUri(), headers.getHeaderString(AuthFilter.FORWARDED_PROTO)),
                httpOnly,
                NewCookie.SameSite.STRICT);
    }
}
