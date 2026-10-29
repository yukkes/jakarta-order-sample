package com.example.order;

import com.example.order.core.service.BatchService;
import com.example.order.core.service.InvoiceService;
import com.example.order.core.security.SecuritySettings;
import com.example.order.core.web.error.BusinessException;
import com.example.order.core.web.paging.PageQuery;
import com.example.order.api.model.BatchExecutionResponse;
import com.example.order.api.model.BatchStartResponse;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class BatchJobTest {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "STOPPED", "ABANDONED");

    @Inject
    BatchService batchService;

    @Inject
    InvoiceService invoiceService;

    @Inject
    SecuritySettings settings;

    @Test
    void runsInvoiceChunkJob() {
        String month = YearMonth.now().minusMonths(1).toString();
        BatchStartResponse started = batchService.startInvoiceJob(month);

        BatchExecutionResponse execution = await(started.getExecutionId());

        assertEquals("COMPLETED", execution.getStatus(), () -> "exitStatus=" + execution.getExitStatus());
        assertFalse(execution.getSteps().isEmpty());
        assertTrue(execution.getSteps().get(0).getWriteCount() >= 3);
        assertTrue(invoiceService.listInvoices(month, new PageQuery(0, 100)).content().size() >= 3);
    }

    @Test
    void runsOrderImportBatchlet() {
        BatchStartResponse started = batchService.startImportJob(null);

        BatchExecutionResponse execution = await(started.getExecutionId());

        assertEquals("COMPLETED", execution.getStatus(), () -> "exitStatus=" + execution.getExitStatus());
    }

    @Test
    void importsFileFromImportDirectory() throws IOException {
        Path dir = Files.createDirectories(settings.importDir());
        Files.writeString(dir.resolve("batch-test.csv"), "C001,P004,1\n", StandardCharsets.UTF_8);

        BatchStartResponse started = batchService.startImportJob("batch-test.csv");

        BatchExecutionResponse execution = await(started.getExecutionId());
        assertEquals("COMPLETED", execution.getStatus(), () -> "exitStatus=" + execution.getExitStatus());
        assertEquals("COMPLETED", execution.getExitStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../pom.xml", "/etc/passwd", "a/b.csv", "..\\x.csv", "orders.txt", "missing.csv"})
    void rejectsImportFilesOutsideImportDirectory(String inputFile) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> batchService.startImportJob(inputFile));
        assertEquals(400, exception.getStatus());
    }

    @Test
    void rejectsSymlinkLeavingImportDirectory() throws IOException {
        Path dir = Files.createDirectories(settings.importDir());
        Path outside = Files.createTempFile("outside", ".csv");
        Path link = dir.resolve("link-test.csv");
        Files.deleteIfExists(link);
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            return; // the file system cannot create symlinks; nothing to verify
        }
        try {
            BusinessException rejected = assertThrows(BusinessException.class,
                    () -> batchService.startImportJob("link-test.csv"));
            assertEquals(400, rejected.getStatus());
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
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
