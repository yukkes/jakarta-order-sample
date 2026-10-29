package com.example.order.testsupport;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Test-only endpoint that forgot its security annotation; it must be denied, not public. */
@Path("/test/unannotated")
@Produces({MediaType.APPLICATION_JSON, "application/problem+json"})
public class UnannotatedResource {

    @GET
    public String get() {
        return "\"should never be reached\"";
    }
}
