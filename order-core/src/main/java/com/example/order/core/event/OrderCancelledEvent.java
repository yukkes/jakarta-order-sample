package com.example.order.core.event;

public record OrderCancelledEvent(Long orderId, String orderNo) {
}
