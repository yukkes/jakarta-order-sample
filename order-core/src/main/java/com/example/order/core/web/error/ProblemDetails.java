package com.example.order.core.web.error;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

/**
 * RFC 9457 is opt-in: the default JSON error shape stays unchanged unless the
 * client explicitly asks for application/problem+json. A wildcard Accept header
 * does not opt in.
 */
public final class ProblemDetails {

    public static final MediaType APPLICATION_PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    private ProblemDetails() {
    }

    public static boolean explicitlyRequested(HttpHeaders headers) {
        return headers.getAcceptableMediaTypes().stream()
                .anyMatch(type -> "application".equalsIgnoreCase(type.getType())
                        && "problem+json".equalsIgnoreCase(type.getSubtype()));
    }
}
