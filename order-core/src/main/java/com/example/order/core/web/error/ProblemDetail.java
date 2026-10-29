package com.example.order.core.web.error;

import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * RFC 9457 Problem Details response body. Kept as a record so JSON-B and
 * Jackson serialize it without a generated DTO.
 */
public record ProblemDetail(String type, String title, int status, String detail, String instance) {

    public static ProblemDetail of(int status, String detail, URI instance) {
        Response.Status statusType = Response.Status.fromStatusCode(status);
        return new ProblemDetail(
                "about:blank",
                statusType != null ? statusType.getReasonPhrase() : "Error",
                status,
                detail,
                instance != null ? instance.toString() : null);
    }
}
