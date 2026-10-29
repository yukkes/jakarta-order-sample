package com.example.order.glassfish;

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
 * GlassFish-side scheduler. See the Liberty adapter for the rationale: the timer
 * is armed by the CDI {@link Startup} event and fires at 02:00 on the 1st.
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
