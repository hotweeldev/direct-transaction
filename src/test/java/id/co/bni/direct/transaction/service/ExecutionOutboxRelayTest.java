package id.co.bni.direct.transaction.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import id.co.bni.direct.transaction.config.ExecutionProperties;
import id.co.bni.direct.transaction.config.NotificationProperties;
import id.co.bni.direct.transaction.entity.EventOutboxRows.PendingEventRow;
import id.co.bni.direct.transaction.repository.mapper.ExecutionOutboxMapper;
import id.co.bni.direct.transaction.service.impl.ExecutionOutboxRelay;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay over a mocked mapper and a mocked KafkaTemplate - no broker, no database.
 * The mocked PlatformTransactionManager lets the real TransactionTemplate run, so one
 * drain pass really is one transaction callback.
 */
class ExecutionOutboxRelayTest {

    private static final String PAYLOAD =
            "{\"taskId\":\"T1\",\"refNo\":\"R1\",\"corpId\":\"CORP1\"}";

    private ExecutionOutboxMapper outboxMapper;
    private KafkaTemplate<String, String> kafkaTemplate;
    private ExecutionProperties properties;
    private NotificationProperties notificationProperties;
    private ExecutionOutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxMapper = mock(ExecutionOutboxMapper.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new ExecutionProperties();
        properties.setMode("kafka");
        notificationProperties = new NotificationProperties();
        relay = new ExecutionOutboxRelay(outboxMapper, kafkaTemplate, properties,
                notificationProperties, mock(PlatformTransactionManager.class));
    }

    private static PendingEventRow row(String id, int attempts) {
        return new PendingEventRow(id, "EXECUTION_REQUESTED", "T1", PAYLOAD, attempts);
    }

    private CompletableFuture<SendResult<String, String>> acked() {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(properties.getTopicRequested(), "T1", PAYLOAD);
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(properties.getTopicRequested(), 0), 0, 0, 0, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(record, metadata));
    }

    @Test
    void aSuccessfulSendMarksTheRowSent() {
        when(outboxMapper.lockNewBatch(100)).thenReturn(List.of(row("E1", 0)));
        when(kafkaTemplate.send(properties.getTopicRequested(), "T1", PAYLOAD))
                .thenReturn(acked());

        int sent = relay.drain();

        assertThat(sent).isEqualTo(1);
        // Key = taskId (the aggregate), value = the stored payload, verbatim.
        verify(kafkaTemplate).send("direct.transfer.execution.requested", "T1", PAYLOAD);
        verify(outboxMapper).markSent("E1");
        verify(outboxMapper, never()).markSendFailure(anyString(), anyString());
        verify(outboxMapper, never()).markFailed(anyString(), anyString());
    }

    @Test
    void aFailedSendBumpsAttemptsAndLeavesTheRowNew() {
        when(outboxMapper.lockNewBatch(100)).thenReturn(List.of(row("E1", 0)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        int sent = relay.drain();

        assertThat(sent).isZero();
        verify(outboxMapper).markSendFailure(eq("E1"), contains("broker down"));
        verify(outboxMapper, never()).markSent(anyString());
        verify(outboxMapper, never()).markFailed(anyString(), anyString());
    }

    @Test
    void theLastAllowedAttemptFlipsTheRowFailed() {
        // attempts=9 means this send is attempt 10 of a default max of 10.
        when(outboxMapper.lockNewBatch(100)).thenReturn(List.of(row("E1", 9)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("still down")));

        relay.drain();

        verify(outboxMapper).markFailed(eq("E1"), contains("still down"));
        verify(outboxMapper, never()).markSendFailure(anyString(), anyString());
    }

    @Test
    void oneBadRowDoesNotStopTheRest() {
        when(outboxMapper.lockNewBatch(100)).thenReturn(List.of(row("E1", 0), row("E2", 0)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")))
                .thenReturn(acked());

        int sent = relay.drain();

        assertThat(sent).isEqualTo(1);
        verify(outboxMapper).markSendFailure(eq("E1"), anyString());
        verify(outboxMapper).markSent("E2");
    }

    @Test
    void theBatchSizeComesFromConfiguration() {
        properties.setOutboxBatch(7);
        when(outboxMapper.lockNewBatch(7)).thenReturn(List.of());

        assertThat(relay.drain()).isZero();
        verify(outboxMapper).lockNewBatch(7);
        ArgumentCaptor<String> none = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, never()).send(none.capture(), anyString(), anyString());
    }

    // ---- Routing by EVENT_TYPE. The failure this guards against is not cosmetic: a
    // notification event on the execution topic is read by ExecutionEventConsumer, which
    // takes its taskId and executes the transfer.

    @Test
    void aNotificationEventGoesToTheNotificationTopic() {
        PendingEventRow notification = new PendingEventRow(
                "N1", "TASK_APPROVED", "T1", "{\"taskId\":\"T1\"}", 0);
        when(outboxMapper.lockNewBatch(100)).thenReturn(List.of(notification));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(acked());

        assertThat(relay.drain()).isEqualTo(1);

        verify(kafkaTemplate).send("direct.notification.events", "T1", "{\"taskId\":\"T1\"}");
        verify(kafkaTemplate, never())
                .send(eq("direct.transfer.execution.requested"), anyString(), anyString());
        verify(outboxMapper).markSent("N1");
    }

    @Test
    void oneDrainPassRoutesBothKindsToTheirOwnTopic() {
        when(outboxMapper.lockNewBatch(100)).thenReturn(List.of(
                row("E1", 0),
                new PendingEventRow("N1", "TRANSACTION_EXECUTED", "T1", PAYLOAD, 0)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(acked());

        assertThat(relay.drain()).isEqualTo(2);

        verify(kafkaTemplate).send("direct.transfer.execution.requested", "T1", PAYLOAD);
        verify(kafkaTemplate).send("direct.notification.events", "T1", PAYLOAD);
    }

    @Test
    void theNotificationTopicIsConfigurable() {
        notificationProperties.setTopic("dev.direct.notification.events");
        when(outboxMapper.lockNewBatch(100)).thenReturn(List.of(
                new PendingEventRow("N1", "TASK_RELEASED", "T1", PAYLOAD, 0)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(acked());

        relay.drain();

        verify(kafkaTemplate).send("dev.direct.notification.events", "T1", PAYLOAD);
    }

    /** No default topic: an unknown type is FAILED where someone will see it. */
    @Test
    void anUnroutableEventTypeIsFailedAndNeverPublished() {
        when(outboxMapper.lockNewBatch(100)).thenReturn(List.of(
                new PendingEventRow("X1", "SOMETHING_NEW", "T1", PAYLOAD, 0)));

        assertThat(relay.drain()).isZero();

        verify(outboxMapper).markFailed(eq("X1"), contains("SOMETHING_NEW"));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(outboxMapper, never()).markSent(anyString());
    }
}
