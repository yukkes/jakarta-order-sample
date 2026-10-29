package com.example.order.core.web;

import com.example.order.core.web.auth.RequireRole;
import com.example.order.core.service.InvoiceService;
import com.example.order.api.model.InvoiceResponse;
import com.example.order.core.web.paging.PageQuery;
import com.example.order.core.web.paging.PageResponse;
import com.example.order.core.web.paging.SortOrder;
import com.example.order.core.web.paging.SortSpec;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Set;

@RequireRole({"USER", "ADMIN"})
@Path("/invoices")
@Produces({MediaType.APPLICATION_JSON, "application/problem+json"})
public class InvoiceResource {

    private static final SortSpec SORT = new SortSpec(
            Set.of("id", "invoiceNo", "billingMonth", "totalAmount", "taxAmount", "createdAt", "updatedAt"),
            List.of(new SortOrder("id", SortOrder.Direction.DESC)));

    @Inject
    private InvoiceService invoiceService;

    @GET
    public PageResponse<InvoiceResponse> list(
            @QueryParam("month") String month,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") List<String> sort) {
        return invoiceService.listInvoices(month, PageQuery.of(page, size, sort, SORT));
    }

    @GET
    @Path("/{id}")
    public InvoiceResponse get(@PathParam("id") Long id) {
        return invoiceService.getInvoice(id);
    }
}
