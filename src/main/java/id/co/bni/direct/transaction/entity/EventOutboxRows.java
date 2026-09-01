package id.co.bni.direct.transaction.entity;

/** Row records for TRX_EVENT_OUTBOX (V4) - the transactional outbox of kafka mode. */
public final class EventOutboxRows {

    private EventOutboxRows() {
    }

    /** One event to enqueue; written inside the workflow transaction that queues the task. */
    public record OutboxInsert(String id, String eventType, String aggregateId, String payload) {
    }

    /** A locked NEW row as the relay reads it; attempts is the count BEFORE this send. */
    public record PendingEventRow(String id, String eventType, String aggregateId,
                                  String payload, Integer attempts) {
    }
}
