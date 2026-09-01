package id.co.bni.direct.transaction.repository.mapper;

import java.util.List;

import id.co.bni.direct.transaction.entity.EventOutboxRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * TRX_EVENT_OUTBOX (V4) - the transactional outbox behind {@code app.execution.mode=kafka}.
 * Statements live in {@code resources/mapper/ExecutionOutboxMapper.xml}; every method here
 * must have a matching statement id there ({@code MapperStatementsTest}).
 */
@Mapper
public interface ExecutionOutboxMapper {

    /**
     * Written inside the SAME workflow transaction that sets the task QUEUED, so the
     * queued task and its pending event commit (or roll back) together.
     */
    int insert(EventOutboxRows.OutboxInsert event);

    /**
     * The relay's drain read: the oldest NEW rows, locked {@code FOR UPDATE SKIP LOCKED}
     * so two relay instances never send the same row twice - a second poller simply skips
     * what the first one holds.
     */
    List<EventOutboxRows.PendingEventRow> lockNewBatch(@Param("limit") int limit);

    /** The send succeeded (acked by the broker): NEW -> SENT, stamp SENT_DT. */
    int markSent(@Param("id") String id);

    /** The send failed but attempts remain: bump ATTEMPTS, record why, stays NEW. */
    int markSendFailure(@Param("id") String id, @Param("error") String error);

    /** The send failed for the last time: bump ATTEMPTS, record why, NEW -> FAILED. */
    int markFailed(@Param("id") String id, @Param("error") String error);
}
