package com.example.order.core.web;

import com.example.order.core.service.OrderService;
import com.example.order.api.model.CreateOrderRequest;
import com.example.order.api.model.OrderResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.List;

@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
public class OrderResource {

    @Inject
    private OrderService orderService;

    @GET
    public List<OrderResponse> list(@QueryParam("status") String status) {
        return orderService.listOrders(status);
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
        return Response.created(URI.create("/api/orders/" + order.getId()))
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
