package com.example.order.core.event;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;

import java.util.logging.Logger;

/**
 * Example of a transactional observer: the audit line is emitted only after the
 * surrounding transaction commits, similar to Spring's
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
@ApplicationScoped
public class OrderAuditListener {

    private static final Logger LOG = Logger.getLogger(OrderAuditListener.class.getName());

    void onConfirmed(@Observes(during = TransactionPhase.AFTER_SUCCESS) OrderConfirmedEvent event) {
        LOG.info(() -> "[audit] order confirmed: id=" + event.orderId() + ", orderNo=" + event.orderNo());
    }

    void onCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) OrderCancelledEvent event) {
        LOG.info(() -> "[audit] order cancelled: id=" + event.orderId() + ", orderNo=" + event.orderNo());
    }
}
