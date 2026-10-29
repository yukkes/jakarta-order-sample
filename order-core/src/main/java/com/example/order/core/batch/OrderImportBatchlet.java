package com.example.order.core.batch;

import com.example.order.core.service.OrderImportService;
import com.example.order.core.web.error.BusinessException;
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
import java.time.format.DateTimeParseException;
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
    private ImportFileResolver importFileResolver;

    @Inject
    @BatchProperty(name = "inputFile")
    private String inputFile;

    private volatile boolean stopped;

    @Override
    public String process() throws Exception {
        List<String> lines = readLines();
        int success = 0;
        List<String> errors = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            if (stopped) {
                errors.add("stopped by request");
                break;
            }
            String trimmed = lines.get(index).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int lineNumber = index + 1;
            try {
                importLine(trimmed);
                success++;
            } catch (RuntimeException exception) {
                // Never echo the raw line: it is file content, not a message we wrote.
                errors.add("line " + lineNumber + ": " + describe(exception));
                LOG.log(Level.WARNING, "import failed at line {0}: {1}",
                        new Object[] {lineNumber, exception.getClass().getSimpleName()});
            }
        }

        writeReport(success, errors);
        return errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
    }

    @Override
    public void stop() {
        stopped = true;
    }

    private static final class InvalidCsvLineException extends RuntimeException {
        InvalidCsvLineException(String message) {
            super(message);
        }
    }

    private void importLine(String line) {
        String[] columns = line.split(",");
        if (columns.length < 3) {
            throw new InvalidCsvLineException("顧客コード,商品コード,数量 の3列が必要です");
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

    private String describe(RuntimeException exception) {
        if (exception instanceof BusinessException) {
            return exception.getMessage();
        }
        if (exception instanceof NumberFormatException) {
            return "数量が整数ではありません";
        }
        if (exception instanceof DateTimeParseException) {
            return "受注日は YYYY-MM-DD 形式で指定してください";
        }
        if (exception instanceof InvalidCsvLineException) {
            return exception.getMessage();
        }
        return "取込に失敗しました (" + exception.getClass().getSimpleName() + ")";
    }

    private List<String> readLines() throws IOException {
        if (inputFile != null && !inputFile.isBlank()) {
            // Checked again here even though BatchService validates before starting:
            // restart() and direct JobOperator calls bypass the service.
            Path path = importFileResolver.resolve(inputFile);
            return Files.readAllLines(path, StandardCharsets.UTF_8);
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
