package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.config.ExecutionProperties;
import id.co.bni.direct.transaction.entity.TrxTaskRows.TaskRow;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.impl.ExecutionEventConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The consuming end over a mocked seam and a mocked KafkaTemplate. The claim semantics
 * (QUEUED claimable, duplicates no-op) are CoreExecutionServiceImplTest's subject; here
 * the contract is: parse, execute, publish the verdict best-effort.
 */
class ExecutionEventConsumerTest {

    private static final String EVENT =
            "{\"taskId\":\"T1\",\"refNo\":\"20260831100000228541\",\"corpId\":\"CORP1\"}";

    private ExecutionService executionService;
    private TrxTaskMapper trxTaskMapper;
    private KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExecutionEventConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executionService = mock(ExecutionService.class);
        trxTaskMapper = mock(TrxTaskMapper.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        consumer = new ExecutionEventConsumer(executionService, trxTaskMapper,
                kafkaTemplate, new ExecutionProperties(), objectMapper);
    }

    private static TaskRow task(String status, String coreJournal) {
        return new TaskRow("T1", "20260831100000228541", "MNU_GCME_050200",
                status, null, new BigDecimal("10000000"), "IDR",
                "113179933", "1000533372", "PT MAJU JAYA", "pembayaran vendor",
                "BUDI SANTOSO", LocalDateTime.of(2026, 8, 31, 10, 0), 5L,
                coreJournal, "00000000000000000042", LocalDateTime.of(2026, 8, 31, 10, 5));
    }

    @Test
    void anEventExecutesTheTaskAndPublishesTheVerdict() throws Exception {
        when(executionService.execute("T1"))
                .thenReturn(new ExecutionService.ExecutionResult("EXECUTED", null));
        when(trxTaskMapper.findTask("CORP1", "T1")).thenReturn(task("EXECUTED", "907409"));

        consumer.onExecutionRequested(EVENT);

        verify(executionService).execute("T1");
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq("direct.transfer.execution.completed"), eq("T1"), value.capture());
        JsonNode event = objectMapper.readTree(value.getValue());
        assertThat(event.get("taskId").asText()).isEqualTo("T1");
        assertThat(event.get("refNo").asText()).isEqualTo("20260831100000228541");
        assertThat(event.get("status").asText()).isEqualTo("EXECUTED");
        assertThat(event.get("coreJournal").asText()).isEqualTo("907409");
        assertThat(event.get("message").isNull()).isTrue();
    }

    @Test
    void aFailedVerdictIsANormalReturnNeverAThrow() {
        // FAILED is a business outcome: the listener completes normally, so the record is
        // committed and never retried or dead-lettered.
        when(executionService.execute("T1")).thenReturn(
                new ExecutionService.ExecutionResult("FAILED", "Core banking menolak transaksi."));
        when(trxTaskMapper.findTask("CORP1", "T1")).thenReturn(task("FAILED", null));

        consumer.onExecutionRequested(EVENT);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), eq("T1"), value.capture());
        assertThat(value.getValue()).contains("\"status\":\"FAILED\"")
                .contains("Core banking menolak transaksi.");
    }

    @Test
    void aFailedCompletedPublishIsSwallowed() {
        // The completed topic is informational; the verdict on the task row is the truth.
        when(executionService.execute("T1"))
                .thenReturn(new ExecutionService.ExecutionResult("EXECUTED", null));
        when(trxTaskMapper.findTask("CORP1", "T1")).thenReturn(task("EXECUTED", "907409"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("broker down"));

        consumer.onExecutionRequested(EVENT);  // must not throw

        verify(executionService).execute("T1");
    }

    @Test
    void anUnparsablePayloadThrowsForTheErrorHandler() {
        // Infrastructure error: propagate, so DefaultErrorHandler retries then dead-letters.
        assertThatThrownBy(() -> consumer.onExecutionRequested("not json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.onExecutionRequested("{\"refNo\":\"R1\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
