package com.example.order.liberty;

import com.example.order.core.service.BatchService;
import jakarta.enterprise.concurrent.Asynchronous;
import jakarta.enterprise.concurrent.Schedule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Liberty-side scheduler. The Quarkus runtime uses the JBeret cron property
 * in application.properties instead, so the portable core stays scheduler-free.
 * <p>
 * {@code @Asynchronous(runAt = ...)} arms the schedule when CDI fires the
 * {@link Startup} event; the first execution happens at the next matching time,
 * not during startup. Catching the exception keeps the timer alive when the
 * batch subsystem is temporarily unavailable (a scheduled method normally
 * stops rescheduling after an exception).
 */
@ApplicationScoped
public class InvoiceSchedule {

    private static final Logger LOG = Logger.getLogger(InvoiceSchedule.class.getName());

    @Inject
    private BatchService batchService;

    @Asynchronous(runAt = @Schedule(daysOfMonth = 1, hours = 2, minutes = 0))
    public void runMonthlyInvoice(@Observes Startup event) {
        try {
            batchService.startInvoiceJob(null);
        } catch (RuntimeException exception) {
            LOG.log(Level.SEVERE, "monthly invoice job could not be started", exception);
        }
    }
}
