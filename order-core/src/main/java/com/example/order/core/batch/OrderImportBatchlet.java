package com.example.order.core.batch;

import com.example.order.core.service.OrderImportService;
import com.example.order.api.model.CreateOrderLine;
import com.example.order.api.model.CreateOrderRequest;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.Batchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Dependent
@Named("orderImportBatchlet")
public class OrderImportBatchlet implements Batchlet {

    private static final Logger LOG = Logger.getLogger(OrderImportBatchlet.class.getName());
    private static final String CLASSPATH_CSV = "/data/orders-import.csv";

    @Inject
    private OrderImportService orderImportService;

    @Inject
    @BatchProperty(name = "inputFile")
    private String inputFile;

    private volatile boolean stopped;

    @Override
    public String process() throws Exception {
        List<String> lines = readLines();
        int success = 0;
        List<String> errors = new ArrayList<>();

        for (String line : lines) {
            if (stopped) {
                errors.add("stopped by request");
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            try {
                importLine(trimmed);
                success++;
            } catch (RuntimeException exception) {
                errors.add(trimmed + " -> " + exception.getMessage());
                LOG.log(Level.WARNING, "import failed: {0}", trimmed);
            }
        }

        writeReport(success, errors);
        return errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
    }

    @Override
    public void stop() {
        stopped = true;
    }

    private void importLine(String line) {
        String[] columns = line.split(",");
        if (columns.length < 3) {
            throw new IllegalArgumentException("顧客コード,商品コード,数量 の3列が必要です");
        }

        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerCode(columns[0].trim());
        request.setOrderDate(columns.length >= 4 && !columns[3].isBlank()
                ? LocalDate.parse(columns[3].trim())
                : LocalDate.now());
        CreateOrderLine orderLine = new CreateOrderLine();
        orderLine.setProductCode(columns[1].trim());
        orderLine.setQuantity(Integer.parseInt(columns[2].trim()));
        request.setLines(List.of(orderLine));
        orderImportService.importOrder(request);
    }

    private List<String> readLines() throws IOException {
        if (inputFile != null && !inputFile.isBlank()) {
            Path path = Path.of(inputFile);
            if (Files.exists(path)) {
                return Files.readAllLines(path, StandardCharsets.UTF_8);
            }
            LOG.log(Level.WARNING, "inputFile not found, falling back to classpath: {0}", inputFile);
        }

        try (InputStream stream = getClass().getResourceAsStream(CLASSPATH_CSV)) {
            if (stream == null) {
                throw new IllegalStateException("CSV が見つかりません: " + CLASSPATH_CSV);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        }
    }

    private void writeReport(int success, List<String> errors) throws IOException {
        Path report = Path.of("data", "import-report.txt");
        Files.createDirectories(report.getParent());
        StringBuilder text = new StringBuilder()
                .append("import report: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .append(System.lineSeparator())
                .append("success=").append(success)
                .append(", failed=").append(errors.size())
                .append(System.lineSeparator());
        for (String error : errors) {
            text.append("ERROR ").append(error).append(System.lineSeparator());
        }
        Files.writeString(report, text.toString(), StandardCharsets.UTF_8);
        LOG.info(() -> "import report written: " + report.toAbsolutePath());
    }
}
