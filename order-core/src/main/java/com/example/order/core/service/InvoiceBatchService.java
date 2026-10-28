package com.example.order.core.service;

import com.example.order.core.repository.InvoiceRepository;
import com.example.order.core.repository.OrderRepository;
import com.example.order.domain.Invoice;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.YearMonth;
import java.util.List;

/**
 * JPA access used by the invoice batch. Keeping the JPA work in a transactional
 * CDI bean makes the batch artifacts independent of the runtime's batch
 * transaction implementation.
 */
@ApplicationScoped
public class InvoiceBatchService {

    @Inject
    private OrderRepository orderRepository;

    @Inject
    private InvoiceRepository invoiceRepository;

    @Transactional
    public List<Order> findOrdersForMonth(String billingMonth, int offset, int limit) {
        return orderRepository.findForMonth(YearMonth.parse(billingMonth), offset, limit);
    }

    @Transactional
    public int saveInvoices(List<Invoice> invoices) {
        int written = 0;
        for (Invoice invoice : invoices) {
            if (invoiceRepository.existsByInvoiceNo(invoice.getInvoiceNo())) {
                continue;
            }

            Order managedOrder = orderRepository.findById(invoice.getOrder().getId()).orElse(null);
            if (managedOrder == null || managedOrder.getStatus() != OrderStatus.CONFIRMED) {
                continue;
            }

            invoice.setOrder(managedOrder);
            invoice.setCustomer(managedOrder.getCustomer());
            invoiceRepository.persist(invoice);
            managedOrder.setStatus(OrderStatus.INVOICED);
            written++;
        }
        return written;
    }

    @Transactional
    public long countInvoicesForMonth(String billingMonth) {
        return invoiceRepository.countByMonth(YearMonth.parse(billingMonth).toString());
    }
}
