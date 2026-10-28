package com.example.order;

import com.example.order.core.service.BatchService;
import com.example.order.core.service.InvoiceService;
import com.example.order.api.model.BatchExecutionResponse;
import com.example.order.api.model.BatchStartResponse;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class BatchJobTest {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "STOPPED", "ABANDONED");

    @Inject
    BatchService batchService;

    @Inject
    InvoiceService invoiceService;

    @Test
    void runsInvoiceChunkJob() {
        String month = YearMonth.now().minusMonths(1).toString();
        BatchStartResponse started = batchService.startInvoiceJob(month);

        BatchExecutionResponse execution = await(started.getExecutionId());

        assertEquals("COMPLETED", execution.getStatus(), () -> "exitStatus=" + execution.getExitStatus());
        assertFalse(execution.getSteps().isEmpty());
        assertTrue(execution.getSteps().get(0).getWriteCount() >= 3);
        assertTrue(invoiceService.listInvoices(month).size() >= 3);
    }

    @Test
    void runsOrderImportBatchlet() {
        BatchStartResponse started = batchService.startImportJob(null);

        BatchExecutionResponse execution = await(started.getExecutionId());

        assertEquals("COMPLETED", execution.getStatus(), () -> "exitStatus=" + execution.getExitStatus());
    }

    private BatchExecutionResponse await(long executionId) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            BatchExecutionResponse execution = batchService.getExecution(executionId);
            if (TERMINAL.contains(execution.getStatus())) {
                return execution;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("job did not finish in time: " + executionId);
    }
}
