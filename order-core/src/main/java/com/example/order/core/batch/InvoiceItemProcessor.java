package com.example.order.core.batch;

import com.example.order.domain.Invoice;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.ItemProcessor;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;

@Dependent
@Named("invoiceItemProcessor")
public class InvoiceItemProcessor implements ItemProcessor {

    @Inject
    @BatchProperty(name = "billingMonth")
    private String billingMonth;

    @Override
    public Object processItem(Object item) {
        Order order = (Order) item;
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            return null;
        }
        if (order.getTotalAmount() == null || order.getTotalAmount().signum() <= 0) {
            throw new InvalidOrderException("請求対象外の注文です: " + order.getOrderNo());
        }

        String month = billingMonth == null || billingMonth.isBlank()
                ? YearMonth.now().minusMonths(1).toString()
                : billingMonth;
        BigDecimal total = order.getTotalAmount();
        BigDecimal tax = total.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
        String invoiceNo = "INV-" + month.replace("-", "") + "-" + order.getId();

        return new Invoice(invoiceNo, order.getCustomer(), order, month, total, tax);
    }
}
