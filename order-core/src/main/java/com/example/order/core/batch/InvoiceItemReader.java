package com.example.order.core.batch;

import com.example.order.core.service.InvoiceBatchService;
import com.example.order.domain.Order;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.AbstractItemReader;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.time.YearMonth;
import java.util.Iterator;
import java.util.List;

@Dependent
@Named("invoiceItemReader")
public class InvoiceItemReader extends AbstractItemReader {

    private static final int PAGE_SIZE = 5;

    @Inject
    private InvoiceBatchService invoiceBatchService;

    @Inject
    @BatchProperty(name = "billingMonth")
    private String billingMonth;

    private int offset;
    private boolean exhausted;
    private Iterator<Order> currentPage = List.<Order>of().iterator();

    @Override
    public void open(Serializable checkpoint) {
        if (billingMonth == null || billingMonth.isBlank()) {
            billingMonth = YearMonth.now().minusMonths(1).toString();
        }
        offset = checkpoint instanceof Number number ? number.intValue() : 0;
    }

    @Override
    public Object readItem() {
        while (!currentPage.hasNext()) {
            if (exhausted) {
                return null;
            }
            List<Order> page = invoiceBatchService.findOrdersForMonth(billingMonth, offset, PAGE_SIZE);
            if (page.isEmpty()) {
                exhausted = true;
                return null;
            }
            offset += page.size();
            currentPage = page.iterator();
            if (page.size() < PAGE_SIZE) {
                exhausted = true;
            }
        }
        return currentPage.next();
    }

    @Override
    public Serializable checkpointInfo() {
        return offset;
    }
}
