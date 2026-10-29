package com.example.order.core.web.error;

import com.example.order.api.model.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.stream.Collectors;

@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Context
    private HttpHeaders headers;

    @Context
    private UriInfo uriInfo;

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .map(this::describe)
                .sorted()
                .collect(Collectors.joining(", "));

        if (ProblemDetails.explicitlyRequested(headers)) {
            ProblemDetail problem = ProblemDetail.of(
                    Response.Status.BAD_REQUEST.getStatusCode(), message, uriInfo.getRequestUri());
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(ProblemDetails.APPLICATION_PROBLEM_JSON)
                    .entity(problem)
                    .build();
        }

        ErrorResponse body = new ErrorResponse();
        body.setStatus(Response.Status.BAD_REQUEST.getStatusCode());
        body.setError("VALIDATION_ERROR");
        body.setMessage(message);
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }

    private String describe(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}
