package com.example.order.core.web;

import com.example.order.core.web.auth.RequireRole;
import com.example.order.core.service.OrderService;
import com.example.order.api.model.CreateOrderRequest;
import com.example.order.api.model.OrderResponse;
import com.example.order.core.web.paging.PageQuery;
import com.example.order.core.web.paging.PageResponse;
import com.example.order.core.web.paging.SortOrder;
import com.example.order.core.web.paging.SortSpec;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
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
import java.util.Set;

@RequireRole({"USER", "ADMIN"})
@Path("/orders")
@Produces({MediaType.APPLICATION_JSON, "application/problem+json"})
public class OrderResource {

    private static final SortSpec SORT = new SortSpec(
            Set.of("id", "orderNo", "orderDate", "status", "totalAmount", "createdAt", "updatedAt"),
            List.of(new SortOrder("id", SortOrder.Direction.DESC)));

    @Inject
    private OrderService orderService;

    @Context
    private UriInfo uriInfo;

    @GET
    public PageResponse<OrderResponse> list(
            @QueryParam("status") String status,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") List<String> sort) {
        return orderService.listOrders(status, PageQuery.of(page, size, sort, SORT));
    }

    @GET
    @Path("/{id}")
    public OrderResponse get(@PathParam("id") Long id) {
        return orderService.getOrder(id);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@Valid CreateOrderRequest request) {
        OrderResponse order = orderService.createOrder(request);
        URI location = uriInfo.getBaseUriBuilder()
                .path("orders")
                .path(order.getId().toString())
                .build();
        return Response.created(location)
                .entity(order)
                .build();
    }

    @POST
    @Path("/{id}/confirm")
    public OrderResponse confirm(@PathParam("id") Long id) {
        return orderService.confirmOrder(id);
    }

    @POST
    @Path("/{id}/cancel")
    public OrderResponse cancel(@PathParam("id") Long id) {
        return orderService.cancelOrder(id);
    }
}
