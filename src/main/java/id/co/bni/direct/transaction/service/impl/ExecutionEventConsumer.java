package id.co.bni.direct.transaction.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.bni.direct.transaction.config.ExecutionProperties;
import id.co.bni.direct.transaction.entity.TrxTaskRows.TaskRow;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The consuming end of kafka-mode execution: takes one EXECUTION_REQUESTED event and
 * runs the SAME execution seam the sync path calls in-request. No status shuffling in
 * between - {@code CoreExecutionServiceImpl}'s claim accepts QUEUED directly, so a
 * redelivered or duplicated event loses the VERSION-guarded claim and no-ops exactly
 * like a concurrent double-execute does today. That guard is what makes at-least-once
 * delivery safe end to end.
 *
 * <p>Afterwards the verdict is published to the completed topic - INFORMATIONAL and
 * best-effort (the task row is the source of truth; a failed publish is logged, never
 * retried through the outbox). Infrastructure errors (unparsable payload, database gone)
 * propagate to the container's DefaultErrorHandler: a few fixed retries, then the
 * dead-letter topic. Business outcomes (FAILED / UNKNOWN) are normal returns of the
 * seam and never throw.
 */
@Component
@ConditionalOnProperty(prefix = "app.execution", name = "mode", havingValue = "kafka")
public class ExecutionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEventConsumer.class);

    private final ExecutionService executionService;
    private final TrxTaskMapper trxTaskMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutionProperties properties;
    private final ObjectMapper objectMapper;

    public ExecutionEventConsumer(ExecutionService executionService,
                                  TrxTaskMapper trxTaskMapper,
                                  KafkaTemplate<String, String> executionKafkaTemplate,
                                  ExecutionProperties properties,
                                  ObjectMapper objectMapper) {
        this.executionService = executionService;
        this.trxTaskMapper = trxTaskMapper;
        this.kafkaTemplate = executionKafkaTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.execution.topic-requested:direct.transfer.execution.requested}",
            groupId = "${app.execution.group:direct-transaction-executor}",
            concurrency = "${app.execution.concurrency:3}")
    public void onExecutionRequested(String message) {
        JsonNode payload;
        String taskId;
        try {
            payload = objectMapper.readTree(message);
            taskId = payload.path("taskId").asText(null);
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("payload has no taskId");
            }
        } catch (Exception e) {
            // Unparsable = infrastructure error: retry (pointless but cheap) then DLT,
            // where the raw message is preserved for a human.
            throw new IllegalArgumentException(
                    "Unparsable EXECUTION_REQUESTED payload: " + e.getMessage(), e);
        }

        ExecutionService.ExecutionResult result = executionService.execute(taskId);
        log.info("Task {} consumed from {}: verdict {}",
                taskId, properties.getTopicRequested(), result.status());

        publishCompleted(taskId, payload.path("refNo").asText(null),
                payload.path("corpId").asText(null), result);
    }

    /** Best-effort verdict event; the task row is the source of truth, never this topic. */
    private void publishCompleted(String taskId, String refNo, String corpId,
                                  ExecutionService.ExecutionResult result) {
        try {
            String coreJournal = null;
            if (corpId != null) {
                TaskRow task = trxTaskMapper.findTask(corpId, taskId);
                coreJournal = task != null ? task.coreJournal() : null;
            }
            ObjectNode event = objectMapper.createObjectNode();
            event.put("taskId", taskId);
            event.put("refNo", refNo);
            event.put("status", result.status());
            event.put("coreJournal", coreJournal);
            event.put("message", result.message());
            kafkaTemplate.send(properties.getTopicCompleted(), taskId, event.toString());
        } catch (Exception e) {
            log.warn("Completed event for task {} not published (verdict {} stands on the "
                    + "task row): {}", taskId, result.status(), e.getMessage());
        }
    }
}
