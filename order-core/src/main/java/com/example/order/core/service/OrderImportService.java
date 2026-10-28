package com.example.order.core.service;

import com.example.order.api.model.CreateOrderRequest;
import com.example.order.api.model.OrderResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class OrderImportService {

    @Inject
    private OrderService orderService;

    /**
     * Runs each imported row in its own transaction so a bad row does not mark
     * the whole batch transaction rollback-only.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public OrderResponse importOrder(CreateOrderRequest request) {
        return orderService.createOrder(request);
    }
}
