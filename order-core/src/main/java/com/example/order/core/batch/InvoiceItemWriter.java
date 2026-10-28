package com.example.order.core.batch;

import com.example.order.core.service.InvoiceBatchService;
import com.example.order.domain.Invoice;
import jakarta.batch.api.chunk.AbstractItemWriter;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;

@Dependent
@Named("invoiceItemWriter")
public class InvoiceItemWriter extends AbstractItemWriter {

    @Inject
    private InvoiceBatchService invoiceBatchService;

    @Override
    public void writeItems(List<Object> items) {
        List<Invoice> invoices = items.stream()
                .map(Invoice.class::cast)
                .toList();
        invoiceBatchService.saveInvoices(invoices);
    }
}
