package com.example.order.core.web.auth;

import com.example.order.api.model.ErrorResponse;
import com.example.order.core.web.error.ProblemDetail;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Locale;

public final class AuthResponses {

    private AuthResponses() {
    }

    public static Response unauthorized(ContainerRequestContext request, String message) {
        return error(request, 401, "UNAUTHORIZED", message);
    }

    public static Response forbidden(ContainerRequestContext request, String message) {
        return error(request, 403, "FORBIDDEN", message);
    }

    private static Response error(ContainerRequestContext request, int status, String code, String message) {
        String accept = request.getHeaders().getFirst(HttpHeaders.ACCEPT);
        if (accept != null && accept.toLowerCase(Locale.ROOT).contains("application/problem+json")) {
            ProblemDetail problem = ProblemDetail.of(status, message, request.getUriInfo().getRequestUri());
            return Response.status(status)
                    .type("application/problem+json")
                    .entity(problem)
                    .build();
        }

        ErrorResponse body = new ErrorResponse();
        body.setStatus(status);
        body.setError(code);
        body.setMessage(message);
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}
