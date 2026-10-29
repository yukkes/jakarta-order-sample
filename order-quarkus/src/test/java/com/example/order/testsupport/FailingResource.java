package com.example.order.testsupport;

import com.example.order.core.web.auth.RequireRole;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Test-only endpoint that fails with an unexpected exception. */
@RequireRole({"USER", "ADMIN"})
@Path("/test/fail")
@Produces({MediaType.APPLICATION_JSON, "application/problem+json"})
public class FailingResource {

    @GET
    public String fail() {
        throw new IllegalStateException("secret internal detail");
    }
}
