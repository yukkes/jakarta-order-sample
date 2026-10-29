package com.example.order.core.web.error;

import com.example.order.api.model.ErrorResponse;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class BusinessExceptionMapper implements ExceptionMapper<BusinessException> {

    @Context
    private HttpHeaders headers;

    @Context
    private UriInfo uriInfo;

    @Override
    public Response toResponse(BusinessException exception) {
        if (ProblemDetails.explicitlyRequested(headers)) {
            ProblemDetail problem = ProblemDetail.of(
                    exception.getStatus(), exception.getMessage(), uriInfo.getRequestUri());
            return Response.status(exception.getStatus())
                    .type(ProblemDetails.APPLICATION_PROBLEM_JSON)
                    .entity(problem)
                    .build();
        }

        ErrorResponse body = new ErrorResponse();
        body.setStatus(exception.getStatus());
        body.setError("BUSINESS_ERROR");
        body.setMessage(exception.getMessage());
        return Response.status(exception.getStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}
