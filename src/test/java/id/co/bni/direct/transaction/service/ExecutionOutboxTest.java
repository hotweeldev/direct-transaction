package id.co.bni.direct.transaction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.config.ExecutionProperties;
import id.co.bni.direct.transaction.entity.EventOutboxRows.OutboxInsert;
import id.co.bni.direct.transaction.repository.mapper.ExecutionOutboxMapper;
import id.co.bni.direct.transaction.service.impl.ExecutionOutbox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The enqueue half of the outbox: mode flag and the exact event row written. */
class ExecutionOutboxTest {

    private final ExecutionOutboxMapper mapper = mock(ExecutionOutboxMapper.class);
    private final ExecutionProperties properties = new ExecutionProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void defaultModeIsSync() {
        assertThat(new ExecutionOutbox(mapper, properties, objectMapper).isKafkaMode()).isFalse();
    }

    @Test
    void enqueueWritesOneNewRowWithTheExactPayload() throws Exception {
        properties.setMode("kafka");
        ExecutionOutbox outbox = new ExecutionOutbox(mapper, properties, objectMapper);

        outbox.enqueueExecutionRequested("T1", "20260831100000228541", "CORP1");

        ArgumentCaptor<OutboxInsert> row = ArgumentCaptor.forClass(OutboxInsert.class);
        verify(mapper).insert(row.capture());
        assertThat(row.getValue().id()).hasSize(32);
        assertThat(row.getValue().eventType()).isEqualTo("EXECUTION_REQUESTED");
        // aggregateId is the taskId - and becomes the Kafka message key.
        assertThat(row.getValue().aggregateId()).isEqualTo("T1");
        JsonNode payload = objectMapper.readTree(row.getValue().payload());
        assertThat(payload.get("taskId").asText()).isEqualTo("T1");
        assertThat(payload.get("refNo").asText()).isEqualTo("20260831100000228541");
        assertThat(payload.get("corpId").asText()).isEqualTo("CORP1");
        assertThat(payload.size()).isEqualTo(3);
    }
}
