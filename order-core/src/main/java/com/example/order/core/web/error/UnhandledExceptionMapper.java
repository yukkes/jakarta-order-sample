package com.example.order.core.web.error;

import com.example.order.api.model.ErrorResponse;
import jakarta.json.JsonException;
import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Last-resort mapper so that no runtime's default error page (stack traces,
 * server version) ever reaches the client. The more specific mappers in this
 * package still win for their own exception types.
 */
@Provider
public class UnhandledExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(UnhandledExceptionMapper.class.getName());

    @Context
    private HttpHeaders headers;

    @Context
    private UriInfo uriInfo;

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof WebApplicationException web) {
            // Keep the status and headers (e.g. Allow on 405); replace the body, whose
            // message may echo internal details such as the full request URL.
            int status = web.getResponse().getStatus();
            Response.Status known = Response.Status.fromStatusCode(status);
            return error(Response.fromResponse(web.getResponse()), status,
                    known != null ? known.name() : "HTTP_ERROR",
                    known != null ? known.getReasonPhrase() : "HTTP " + status);
        }

        if (causedByJson(exception)) {
            // A request body that is not valid JSON. Without this mapper the runtimes answer 500
            // or a default page. RESTEasy Classic wraps the failure in ProcessingException while
            // Quarkus REST throws the JsonbException directly, so check the cause chain only.
            return error(Response.status(Response.Status.BAD_REQUEST), 400, "BAD_REQUEST",
                    "リクエストボディの JSON が不正です");
        }

        String errorId = UUID.randomUUID().toString();
        LOG.log(Level.SEVERE, "unhandled exception, errorId=" + errorId, exception);
        return error(Response.serverError(), 500, "INTERNAL_ERROR",
                "内部エラーが発生しました (errorId=" + errorId + ")");
    }

    private boolean causedByJson(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonbException || cause instanceof JsonException) {
                return true;
            }
        }
        return false;
    }

    private Response error(Response.ResponseBuilder builder, int status, String code, String message) {
        if (ProblemDetails.explicitlyRequested(headers)) {
            return builder
                    .type(ProblemDetails.APPLICATION_PROBLEM_JSON)
                    .entity(ProblemDetail.of(status, message, uriInfo.getRequestUri()))
                    .build();
        }

        ErrorResponse body = new ErrorResponse();
        body.setStatus(status);
        body.setError(code);
        body.setMessage(message);
        return builder
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}
