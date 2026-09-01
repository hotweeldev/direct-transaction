package id.co.bni.direct.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How a task whose workflow completed gets executed.
 *
 * <p>{@code sync} (the default) is today's behavior: the releasing request calls the
 * execution seam after the workflow transaction commits and the HTTP answer carries the
 * execution verdict. {@code kafka} decouples the two through a transactional outbox
 * ({@code TRX_EVENT_OUTBOX}): the workflow transaction sets the task QUEUED and writes
 * the event row atomically, {@link id.co.bni.direct.transaction.service.impl.ExecutionOutboxRelay}
 * publishes it, and {@link id.co.bni.direct.transaction.service.impl.ExecutionEventConsumer}
 * executes it - the release/submit answer is then QUEUED and the verdict arrives on the
 * task (and on the completed topic) later.
 *
 * <p>Every Kafka bean in this service is conditional on {@code mode=kafka}, so a laptop
 * without a broker behaves exactly as before.
 */
@ConfigurationProperties(prefix = "app.execution")
public class ExecutionProperties {

    /** {@code sync} or {@code kafka}. Default sync: no broker needed, verdict in-request. */
    private String mode = "sync";

    /** How often the relay drains the outbox. */
    private long outboxPollMs = 1000;

    /** How many NEW rows one drain pass locks and sends. */
    private int outboxBatch = 100;

    /**
     * Sends-per-row before the row flips NEW -> FAILED and stops being retried. A FAILED
     * row is alert-worthy: its task sits QUEUED until the row is re-queued manually.
     */
    private int outboxMaxAttempts = 10;

    /** How long the relay waits for one Kafka send before counting it as a failed attempt. */
    private long sendTimeoutMs = 10000;

    /** Execution-request events; key = taskId, so one task's events stay ordered. */
    private String topicRequested = "direct.transfer.execution.requested";

    /** Informational verdict events (EXECUTED / FAILED / UNKNOWN), published best-effort. */
    private String topicCompleted = "direct.transfer.execution.completed";

    /** Consumer group of the execution listener. */
    private String group = "direct-transaction-executor";

    /** Listener concurrency; the VERSION-guarded claim makes parallel consumers safe. */
    private int concurrency = 3;

    public boolean isKafkaMode() {
        return "kafka".equalsIgnoreCase(mode);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public long getOutboxPollMs() {
        return outboxPollMs;
    }

    public void setOutboxPollMs(long outboxPollMs) {
        this.outboxPollMs = outboxPollMs;
    }

    public int getOutboxBatch() {
        return outboxBatch;
    }

    public void setOutboxBatch(int outboxBatch) {
        this.outboxBatch = outboxBatch;
    }

    public int getOutboxMaxAttempts() {
        return outboxMaxAttempts;
    }

    public void setOutboxMaxAttempts(int outboxMaxAttempts) {
        this.outboxMaxAttempts = outboxMaxAttempts;
    }

    public long getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    public void setSendTimeoutMs(long sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public String getTopicRequested() {
        return topicRequested;
    }

    public void setTopicRequested(String topicRequested) {
        this.topicRequested = topicRequested;
    }

    public String getTopicCompleted() {
        return topicCompleted;
    }

    public void setTopicCompleted(String topicCompleted) {
        this.topicCompleted = topicCompleted;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }
}
