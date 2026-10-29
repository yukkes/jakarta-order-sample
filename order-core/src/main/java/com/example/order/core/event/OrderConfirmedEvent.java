package com.example.order.core.event;

public record OrderConfirmedEvent(Long orderId, String orderNo) {
}
