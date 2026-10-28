package com.example.order.core.web;

import com.example.order.core.service.CatalogService;
import com.example.order.api.model.CustomerResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/customers")
@Produces(MediaType.APPLICATION_JSON)
public class CustomerResource {

    @Inject
    private CatalogService catalogService;

    @GET
    public List<CustomerResponse> list() {
        return catalogService.listCustomers();
    }
}
