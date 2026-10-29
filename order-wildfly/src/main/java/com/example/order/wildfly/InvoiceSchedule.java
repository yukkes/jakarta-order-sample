package com.example.order.wildfly;

import com.example.order.core.service.BatchService;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;

@Singleton
@Startup
public class InvoiceSchedule {

    @Inject
    private BatchService batchService;

    @Schedule(dayOfMonth = "1", hour = "2", minute = "0", persistent = false)
    public void runMonthlyInvoice() {
        batchService.startInvoiceJob(null);
    }
}
