package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.config.ExecutionProperties;
import id.co.bni.direct.transaction.entity.EventOutboxRows.PendingEventRow;
import id.co.bni.direct.transaction.repository.mapper.ExecutionOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains TRX_EVENT_OUTBOX into Kafka. Only registered when {@code app.execution.mode=kafka}.
 *
 * <p>One drain pass is one transaction: lock a batch of NEW rows ({@code FOR UPDATE SKIP
 * LOCKED} - scaled-out relays skip each other's rows instead of double-sending), send
 * each SYNCHRONOUSLY (get() with a timeout, so "sent" means the broker acked with
 * acks=all, not "buffered somewhere"), and mark the row SENT or record the failure. A
 * failed send bumps ATTEMPTS and leaves the row NEW for the next poll; after
 * outbox-max-attempts it flips FAILED and is logged at ERROR - that is the alert: its
 * task sits QUEUED until a human re-queues the row (the payload is kept in full).
 *
 * <p>At-least-once by construction: a crash between broker-ack and markSent re-sends the
 * row - the consumer's QUEUED->EXECUTING VERSION claim makes the duplicate a no-op.
 */
@Component
@ConditionalOnProperty(prefix = "app.execution", name = "mode", havingValue = "kafka")
public class ExecutionOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(ExecutionOutboxRelay.class);

    /** TRX_EVENT_OUTBOX.LAST_ERROR is NVARCHAR2(1000). */
    private static final int ERROR_MAX = 1000;

    private final ExecutionOutboxMapper outboxMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutionProperties properties;
    private final TransactionTemplate drainTransaction;

    public ExecutionOutboxRelay(ExecutionOutboxMapper outboxMapper,
                                KafkaTemplate<String, String> executionKafkaTemplate,
                                ExecutionProperties properties,
                                PlatformTransactionManager transactionManager) {
        this.outboxMapper = outboxMapper;
        this.kafkaTemplate = executionKafkaTemplate;
        this.properties = properties;
        this.drainTransaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.execution.outbox-poll-ms:1000}")
    public void poll() {
        try {
            drain();
        } catch (RuntimeException e) {
            // A drain that dies (database gone, broker metadata failure outside a send)
            // must not kill the scheduler thread; the next tick retries everything.
            log.error("Outbox drain pass failed; will retry on the next poll", e);
        }
    }

    /** One drain pass; answers how many rows were sent (test seam). */
    public int drain() {
        Integer sent = drainTransaction.execute(tx -> {
            List<PendingEventRow> batch = outboxMapper.lockNewBatch(properties.getOutboxBatch());
            int ok = 0;
            for (PendingEventRow row : batch) {
                if (relay(row)) {
                    ok++;
                }
            }
            return ok;
        });
        return sent != null ? sent : 0;
    }

    private boolean relay(PendingEventRow row) {
        try {
            kafkaTemplate.send(properties.getTopicRequested(), row.aggregateId(), row.payload())
                    .get(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            outboxMapper.markSent(row.id());
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Log the throwable class, not just its message - a TimeoutException carries none.
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (reason.length() > ERROR_MAX) {
                reason = reason.substring(0, ERROR_MAX);
            }
            int attempts = (row.attempts() != null ? row.attempts() : 0) + 1;
            if (attempts >= properties.getOutboxMaxAttempts()) {
                outboxMapper.markFailed(row.id(), reason);
                log.error("Outbox event {} (task {}) FAILED after {} attempts: {} - task stays "
                                + "QUEUED until the row is re-queued or executed manually.",
                        row.id(), row.aggregateId(), attempts, reason, e);
            } else {
                outboxMapper.markSendFailure(row.id(), reason);
                log.warn("Outbox send failed for event {} (task {}) attempt {}: {}",
                        row.id(), row.aggregateId(), attempts, reason);
            }
            return false;
        }
    }
}
