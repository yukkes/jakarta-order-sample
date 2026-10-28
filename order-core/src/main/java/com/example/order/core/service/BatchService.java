package com.example.order.core.service;

import com.example.order.api.model.BatchExecutionResponse;
import com.example.order.api.model.BatchStartResponse;
import com.example.order.api.model.StepExecutionResponse;
import com.example.order.core.web.error.BusinessException;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchRuntime;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.JobExecution;
import jakarta.batch.runtime.JobInstance;
import jakarta.batch.runtime.Metric;
import jakarta.batch.runtime.StepExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;

@ApplicationScoped
public class BatchService {

    public static final String INVOICE_JOB = "invoiceJob";
    public static final String IMPORT_JOB = "orderImportJob";

    /**
     * Quarkus JBeret exposes JobOperator as a CDI bean. Open Liberty does not,
     * so fall back to the standard BatchRuntime lookup.
     */
    @Inject
    private Instance<JobOperator> jobOperatorInstance;

    public BatchStartResponse startInvoiceJob(String month) {
        String billingMonth = normalizeMonth(month);
        Properties parameters = new Properties();
        parameters.setProperty("billingMonth", billingMonth);

        long executionId = operator().start(INVOICE_JOB, parameters);
        return startResponse(executionId, INVOICE_JOB);
    }

    public BatchStartResponse startImportJob(String inputFile) {
        Properties parameters = new Properties();
        if (inputFile != null && !inputFile.isBlank()) {
            parameters.setProperty("inputFile", inputFile);
        }

        long executionId = operator().start(IMPORT_JOB, parameters);
        return startResponse(executionId, IMPORT_JOB);
    }

    public BatchExecutionResponse getExecution(long executionId) {
        JobExecution execution = operator().getJobExecution(executionId);
        if (execution == null) {
            throw BusinessException.notFound("ジョブ実行が見つかりません: " + executionId);
        }
        return toResponse(execution, true);
    }

    public List<BatchExecutionResponse> listExecutions(String jobName, int count) {
        int limit = Math.max(1, Math.min(count, 50));
        List<String> jobNames;
        if (jobName == null || jobName.isBlank()) {
            jobNames = List.of(INVOICE_JOB, IMPORT_JOB);
        } else {
            jobNames = List.of(jobName);
        }

        List<JobExecution> executions = new ArrayList<>();
        for (String name : jobNames) {
            for (JobInstance instance : operator().getJobInstances(name, 0, limit)) {
                executions.addAll(operator().getJobExecutions(instance));
            }
        }

        return executions.stream()
                .sorted(Comparator.comparingLong(JobExecution::getExecutionId).reversed())
                .limit(limit)
                .map(execution -> toResponse(execution, false))
                .toList();
    }

    public long restart(long executionId) {
        return operator().restart(executionId, new Properties());
    }

    private JobOperator operator() {
        if (jobOperatorInstance != null && !jobOperatorInstance.isUnsatisfied()) {
            return jobOperatorInstance.get();
        }
        JobOperator operator = BatchRuntime.getJobOperator();
        if (operator == null) {
            throw new IllegalStateException("JobOperator が利用できません");
        }
        return operator;
    }

    private BatchStartResponse startResponse(long executionId, String jobName) {
        BatchStartResponse response = new BatchStartResponse();
        response.setExecutionId(executionId);
        response.setJobName(jobName);
        response.setStatus(statusOf(executionId));
        return response;
    }

    private String statusOf(long executionId) {
        JobExecution execution = operator().getJobExecution(executionId);
        return execution != null ? execution.getBatchStatus().name() : BatchStatus.STARTING.name();
    }

    private String normalizeMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now().minusMonths(1).toString();
        }
        try {
            return YearMonth.parse(month).toString();
        } catch (DateTimeParseException exception) {
            throw BusinessException.badRequest("月は YYYY-MM 形式で指定してください: " + month);
        }
    }

    private BatchExecutionResponse toResponse(JobExecution execution, boolean includeSteps) {
        BatchExecutionResponse response = new BatchExecutionResponse();
        response.setExecutionId(execution.getExecutionId());
        response.setJobName(execution.getJobName());
        response.setStatus(execution.getBatchStatus().name());
        response.setExitStatus(execution.getExitStatus());
        response.setStartTime(toLocalDateTime(execution.getStartTime()));
        response.setEndTime(toLocalDateTime(execution.getEndTime()));

        if (includeSteps) {
            List<StepExecutionResponse> steps = operator().getStepExecutions(execution.getExecutionId())
                    .stream()
                    .map(this::toStepResponse)
                    .toList();
            response.setSteps(steps);
        }
        return response;
    }

    private StepExecutionResponse toStepResponse(StepExecution step) {
        StepExecutionResponse response = new StepExecutionResponse();
        response.setStepName(step.getStepName());
        response.setStatus(step.getBatchStatus().name());
        response.setExitStatus(step.getExitStatus());
        response.setReadCount(0L);
        response.setWriteCount(0L);
        response.setCommitCount(0L);
        response.setRollbackCount(0L);
        response.setSkipCount(0L);

        for (Metric metric : step.getMetrics()) {
            switch (metric.getType()) {
                case READ_COUNT -> response.setReadCount(metric.getValue());
                case WRITE_COUNT -> response.setWriteCount(metric.getValue());
                case COMMIT_COUNT -> response.setCommitCount(metric.getValue());
                case ROLLBACK_COUNT -> response.setRollbackCount(metric.getValue());
                case READ_SKIP_COUNT, PROCESS_SKIP_COUNT, WRITE_SKIP_COUNT ->
                        response.setSkipCount(response.getSkipCount() + metric.getValue());
                default -> {
                    // FILTER_COUNT is not displayed in this sample.
                }
            }
        }
        return response;
    }

    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
