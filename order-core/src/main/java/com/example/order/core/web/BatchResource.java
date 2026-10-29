package com.example.order.core.web;

import com.example.order.core.web.auth.RequireRole;
import com.example.order.core.service.BatchService;
import com.example.order.api.model.BatchExecutionResponse;
import com.example.order.api.model.BatchStartResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.List;

@RequireRole("ADMIN")
@Path("/batch")
@Produces({MediaType.APPLICATION_JSON, "application/problem+json"})
public class BatchResource {

    @Inject
    private BatchService batchService;

    @Context
    private UriInfo uriInfo;

    @POST
    @Path("/invoices")
    public Response startInvoiceJob(@QueryParam("month") String month) {
        BatchStartResponse started = batchService.startInvoiceJob(month);
        return accepted(started);
    }

    @POST
    @Path("/imports")
    public Response startImportJob(@QueryParam("inputFile") String inputFile) {
        BatchStartResponse started = batchService.startImportJob(inputFile);
        return accepted(started);
    }

    @GET
    @Path("/executions")
    public List<BatchExecutionResponse> list(
            @QueryParam("job") String job,
            @QueryParam("count") @DefaultValue("10") int count) {
        return batchService.listExecutions(job, count);
    }

    @GET
    @Path("/executions/{id}")
    public BatchExecutionResponse get(@PathParam("id") Long id) {
        return batchService.getExecution(id);
    }

    @POST
    @Path("/executions/{id}/restart")
    public Response restart(@PathParam("id") Long id) {
        long executionId = batchService.restart(id);
        BatchExecutionResponse execution = batchService.getExecution(executionId);
        BatchStartResponse started = new BatchStartResponse();
        started.setExecutionId(executionId);
        started.setJobName(execution.getJobName());
        started.setStatus(execution.getStatus());
        return accepted(started);
    }

    private Response accepted(BatchStartResponse started) {
        URI location = uriInfo.getBaseUriBuilder()
                .path("batch")
                .path("executions")
                .path(started.getExecutionId().toString())
                .build();
        return Response.accepted(location)
                .entity(started)
                .build();
    }
}
