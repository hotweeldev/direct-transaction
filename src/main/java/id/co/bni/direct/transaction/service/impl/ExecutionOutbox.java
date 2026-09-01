package id.co.bni.direct.transaction.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.bni.direct.transaction.config.ExecutionProperties;
import id.co.bni.direct.transaction.entity.EventOutboxRows.OutboxInsert;
import id.co.bni.direct.transaction.repository.mapper.ExecutionOutboxMapper;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The workflow side of kafka-mode execution: answers "which mode are we in" and, in
 * kafka mode, writes the EXECUTION_REQUESTED outbox row.
 *
 * <p>{@link #enqueueExecutionRequested} deliberately has no transaction of its own: it
 * MUST be called inside the workflow transaction that sets the task QUEUED, so the
 * queued task and its pending event are one atomic commit - that atomicity is the whole
 * point of an outbox. Registered in both modes (it is just a mapper and a flag); the
 * Kafka machinery it feeds lives behind {@code mode=kafka}.
 */
@Component
public class ExecutionOutbox {

    public static final String EVENT_EXECUTION_REQUESTED = "EXECUTION_REQUESTED";

    private final ExecutionOutboxMapper outboxMapper;
    private final ExecutionProperties properties;
    private final ObjectMapper objectMapper;

    public ExecutionOutbox(ExecutionOutboxMapper outboxMapper,
                           ExecutionProperties properties,
                           ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** True when completed workflows queue an event instead of executing in-request. */
    public boolean isKafkaMode() {
        return properties.isKafkaMode();
    }

    /**
     * Writes the outbox row for one task whose workflow just completed. Caller must hold
     * the workflow transaction (see class comment).
     */
    public void enqueueExecutionRequested(String taskId, String refNo, String corpId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", taskId);
        payload.put("refNo", refNo);
        payload.put("corpId", corpId);
        outboxMapper.insert(new OutboxInsert(
                UUID.randomUUID().toString().replace("-", ""),
                EVENT_EXECUTION_REQUESTED, taskId, payload.toString()));
    }
}
