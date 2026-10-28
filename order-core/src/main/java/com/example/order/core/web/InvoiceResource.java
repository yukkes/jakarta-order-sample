package com.example.order.core.web;

import com.example.order.core.service.InvoiceService;
import com.example.order.api.model.InvoiceResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/invoices")
@Produces(MediaType.APPLICATION_JSON)
public class InvoiceResource {

    @Inject
    private InvoiceService invoiceService;

    @GET
    public List<InvoiceResponse> list(@QueryParam("month") String month) {
        return invoiceService.listInvoices(month);
    }

    @GET
    @Path("/{id}")
    public InvoiceResponse get(@PathParam("id") Long id) {
        return invoiceService.getInvoice(id);
    }
}
