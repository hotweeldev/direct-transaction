package id.co.bni.direct.transaction.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.bni.direct.transaction.entity.EventOutboxRows.OutboxInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.NotificationRecipientRow;
import id.co.bni.direct.transaction.repository.mapper.ExecutionOutboxMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The notification half of TRX_EVENT_OUTBOX - the sibling of {@link ExecutionOutbox},
 * writing rows to the same table with a different EVENT_TYPE. V4's own DDL comment
 * called this: "a column so the next event type is a row, not a table".
 *
 * <p><b>NO TRANSACTION OF ITS OWN</b>, for the same reason as {@link ExecutionOutbox}:
 * every method here MUST be called from inside the workflow transaction that caused the
 * state change, so the notification event and the state change are one atomic commit. An
 * event about a task that rolled back can never be sent, and the HTTP request never talks
 * to Kafka. Annotating anything here {@code @Transactional} (or calling it after the
 * workflow commits) would silently break both properties.
 *
 * <p><b>A NOTIFICATION MUST NEVER COST A TRANSACTION.</b> The outbox row is written inside
 * the transaction but the write is wrapped: a failure building or inserting the event is
 * logged at ERROR and swallowed, never propagated. The alternative - letting a bad
 * recipient query roll back a released transfer - is far worse than a missing bell badge,
 * and it is the same lesson V4 recorded for the missing foreign key ("the outbox row must
 * never be the reason a workflow transaction rolls back"). Atomicity is preserved in the
 * direction that matters: no event is ever sent for work that did not commit.
 *
 * <p><b>MODE.</b> Unlike execution events, notification events are written in {@code sync}
 * mode too. They are part of the workflow commit, not of the execution pipeline, and
 * nothing about writing them needs a broker: the rows simply sit STATUS='NEW' until a
 * relay with a broker drains them. Which means, plainly: ON A LAPTOP RUNNING
 * {@code app.execution.mode=sync} (the default) NO NOTIFICATION EVER ARRIVES - the rows
 * accumulate in TRX_EVENT_OUTBOX and {@code ExecutionOutboxRelay} is not even registered.
 * That is intended; do not "fix" it by publishing from the request.
 *
 * <p><b>RECIPIENTS ARE RESOLVED HERE</b>, producer-side, from TRX_TASK.MAKER_USER_ID,
 * TRX_TASK_CANDIDATE (the frozen set - V2) and TRX_TASK_ACTION, and travel inside the
 * payload. direct-notification must never join back to TRX_TASK; that coupling is the one
 * this design exists to avoid.
 */
@Component
public class NotificationOutbox {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutbox.class);

    public static final String EVENT_TASK_SUBMITTED = "TASK_SUBMITTED";
    public static final String EVENT_TASK_APPROVED = "TASK_APPROVED";
    public static final String EVENT_TASK_REJECTED = "TASK_REJECTED";
    public static final String EVENT_TASK_RELEASED = "TASK_RELEASED";
    public static final String EVENT_TRANSACTION_EXECUTED = "TRANSACTION_EXECUTED";
    public static final String EVENT_TRANSACTION_FAILED = "TRANSACTION_FAILED";
    public static final String EVENT_TRANSACTION_UNKNOWN = "TRANSACTION_UNKNOWN";

    /**
     * The whole v1 vocabulary. The relay routes by membership of this set rather than by
     * "anything that is not EXECUTION_REQUESTED", so a future third event type has to be
     * routed deliberately instead of defaulting onto somebody's topic.
     */
    private static final Set<String> NOTIFICATION_EVENT_TYPES = Set.of(
            EVENT_TASK_SUBMITTED, EVENT_TASK_APPROVED, EVENT_TASK_REJECTED,
            EVENT_TASK_RELEASED, EVENT_TRANSACTION_EXECUTED, EVENT_TRANSACTION_FAILED,
            EVENT_TRANSACTION_UNKNOWN);

    /** The events the FE renders in red; everything else is an ordinary progress message. */
    private static final Set<String> ERROR_EVENT_TYPES = Set.of(
            EVENT_TASK_REJECTED, EVENT_TRANSACTION_FAILED, EVENT_TRANSACTION_UNKNOWN);

    /** The actions that make someone a recipient of a verdict: they moved the task along. */
    private static final List<String> ACTED_APPROVE_RELEASE = List.of("APPROVE", "RELEASE");

    /** "Everyone who already acted", for a reject - a REJECT row included. */
    private static final List<String> ACTED_ANY = List.of("APPROVE", "RELEASE", "REJECT");

    /**
     * The contract froze an ISO-8601 stamp WITH an offset ({@code 2026-09-08T10:15:30+07:00}),
     * unlike {@code Timestamps} (the offsetless HTTP wire format this service uses
     * everywhere else). Deliberate: this value crosses a broker into another service's
     * database and is rendered back to users much later, where a bare wall clock has no
     * meaning. Seconds precision - a notification is not a trace.
     */
    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** The FE route the bell deep-links into. */
    private static final String DEEP_LINK_PREFIX = "/transaksi/pending-task/";

    private final ExecutionOutboxMapper outboxMapper;
    private final TrxTaskMapper trxTaskMapper;
    private final ObjectMapper objectMapper;

    public NotificationOutbox(ExecutionOutboxMapper outboxMapper,
                              TrxTaskMapper trxTaskMapper,
                              ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.trxTaskMapper = trxTaskMapper;
        this.objectMapper = objectMapper;
    }

    /** True when the relay must send this event type to the notification topic. */
    public static boolean isNotificationEvent(String eventType) {
        return NOTIFICATION_EVENT_TYPES.contains(eventType);
    }

    /**
     * What one event says about the task it is about. Assembled by the caller from the row
     * it already holds - no extra read of TRX_TASK, and the values are the ones the caller
     * just wrote, not what a re-read would find after the next actor moved.
     *
     * @param status the task's status AFTER the change that caused the event
     */
    public record NotifiableTask(String taskId, String corpId, String refNo,
                                 String serviceName, String serviceCode,
                                 BigDecimal amount, String currency, String status) {
    }

    // ------------------------------------------------------------------ events

    /**
     * A maker submitted a task into a workflow: the candidates of the stage that is now
     * ACTIVE are told there is something to do.
     *
     * <p>Not emitted for a single-user (no-workflow) task: there is no stage and therefore
     * nobody to notify - the maker hears about that task through its TRANSACTION_* verdict.
     */
    public void taskSubmitted(NotifiableTask task, Integer activeStageSeq) {
        emit(EVENT_TASK_SUBMITTED, task, () -> candidatesOf(task.taskId(), activeStageSeq));
    }

    /**
     * An approval landed: the candidates of the now-ACTIVE stage, plus the maker.
     *
     * <p>{@code activeStageSeq} is the stage the task sits on after the approval - the
     * NEXT stage when this approval completed the current one, and the SAME stage when it
     * did not (a 2-of-N level still waiting on its second approver). Both readings are
     * "candidates of the newly active stage" and both are what the contract asks for.
     */
    public void taskApproved(NotifiableTask task, Integer activeStageSeq) {
        emit(EVENT_TASK_APPROVED, task, () -> {
            List<NotificationRecipientRow> recipients =
                    new ArrayList<>(candidatesOf(task.taskId(), activeStageSeq));
            recipients.add(trxTaskMapper.findNotificationMaker(task.taskId()));
            return recipients;
        });
    }

    /** A rejection: the maker, plus everyone who had already acted (the rejecter included). */
    public void taskRejected(NotifiableTask task) {
        emit(EVENT_TASK_REJECTED, task, () -> {
            List<NotificationRecipientRow> recipients = new ArrayList<>();
            recipients.add(trxTaskMapper.findNotificationMaker(task.taskId()));
            recipients.addAll(trxTaskMapper.findNotificationActors(task.taskId(), ACTED_ANY));
            return recipients;
        });
    }

    /**
     * The workflow completed - the task has been released and is on its way to execution.
     * The maker alone: the approvers already got TASK_APPROVED, and the releaser is about
     * to get the verdict.
     */
    public void taskReleased(NotifiableTask task) {
        emit(EVENT_TASK_RELEASED, task,
                () -> List.of(trxTaskMapper.findNotificationMaker(task.taskId())));
    }

    /**
     * The execution verdict, mapped from the task's terminal status: EXECUTED / FAILED /
     * UNKNOWN. Recipients are the maker and every actor who approved or released it - the
     * people who put their name on the money moving.
     */
    public void transactionVerdict(NotifiableTask task, String verdictStatus) {
        String eventType = switch (verdictStatus) {
            case "EXECUTED" -> EVENT_TRANSACTION_EXECUTED;
            case "FAILED" -> EVENT_TRANSACTION_FAILED;
            case "UNKNOWN" -> EVENT_TRANSACTION_UNKNOWN;
            // Anything else is not a verdict (READY_TO_EXECUTE when the core hop is off on
            // a laptop, a lost double-execute claim answering the live status). Nothing
            // ended, so nothing is announced.
            default -> null;
        };
        if (eventType == null) {
            return;
        }
        emit(eventType, task, () -> {
            List<NotificationRecipientRow> recipients = new ArrayList<>();
            recipients.add(trxTaskMapper.findNotificationMaker(task.taskId()));
            recipients.addAll(trxTaskMapper.findNotificationActors(
                    task.taskId(), ACTED_APPROVE_RELEASE));
            return recipients;
        });
    }

    // ------------------------------------------------------------------ internals

    private List<NotificationRecipientRow> candidatesOf(String taskId, Integer stageSeq) {
        return stageSeq == null ? List.of()
                : trxTaskMapper.findNotificationCandidates(taskId, stageSeq);
    }

    /**
     * Resolve, build, insert - all inside the caller's transaction, all inside one
     * try/catch (see the class comment: a notification must never cost a transaction).
     *
     * <p>An event with no recipients is not written. It would travel the whole pipeline to
     * produce zero NOTIFICATION rows, and the empty list is itself the signal worth
     * logging - a stage with no candidates means the submit-time freeze went wrong.
     */
    private void emit(String eventType, NotifiableTask task,
                      java.util.function.Supplier<List<NotificationRecipientRow>> resolver) {
        try {
            List<NotificationRecipientRow> recipients = dedupe(resolver.get());
            if (recipients.isEmpty()) {
                log.warn("Notification {} for task {} has no recipients; nothing enqueued.",
                        eventType, task.taskId());
                return;
            }
            // Both id spaces must reach the consumer: it matches a caller's token against
            // either the surrogate or the login id, and a null login id can cost that
            // person sight of their own notification. Not fatal (the surrogate still
            // matches) but it means a CORP_USR row is missing under a live actor, which
            // is worth knowing about.
            recipients.stream().filter(r -> r.loginId() == null).forEach(r ->
                    log.warn("Notification {} for task {}: recipient {} has no login id - "
                                    + "no CORP_USR row behind that CORP_USR.ID.",
                            eventType, task.taskId(), r.userId()));
            String eventId = newId();
            outboxMapper.insert(new OutboxInsert(eventId, eventType, task.taskId(),
                    payload(eventId, eventType, task, recipients)));
        } catch (RuntimeException e) {
            log.error("Notification {} for task {} not enqueued - the workflow write is "
                            + "unaffected and stands committed.", eventType, task.taskId(), e);
        }
    }

    /**
     * One person, one notification. The maker who also holds a releaser role is a
     * candidate AND the maker; the consumer's UK_NOTIFICATION_EVENT (eventId,
     * recipientUserId) would reject the second row anyway - better to not send it. First
     * occurrence wins, and the callers add the maker last, so a maker who is also a
     * candidate keeps the role that explains why they are being told.
     */
    private static List<NotificationRecipientRow> dedupe(List<NotificationRecipientRow> rows) {
        Map<String, NotificationRecipientRow> byUserId = new LinkedHashMap<>();
        for (NotificationRecipientRow row : rows) {
            if (row != null && row.userId() != null) {
                byUserId.putIfAbsent(row.userId(), row);
            }
        }
        return List.copyOf(byUserId.values());
    }

    /** The frozen envelope of contract §1, field for field. */
    private String payload(String eventId, String eventType, NotifiableTask task,
                           List<NotificationRecipientRow> recipients) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventId", eventId);
        event.put("eventType", eventType);
        event.put("occurredAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                .format(OCCURRED_AT));
        event.put("corpId", task.corpId());
        event.put("taskId", task.taskId());
        event.put("refNo", task.refNo());
        event.put("serviceName", task.serviceName());
        event.put("serviceCode", task.serviceCode());
        event.put("amount", task.amount());
        event.put("currency", task.currency());
        event.put("status", task.status());
        event.put("isError", ERROR_EVENT_TYPES.contains(eventType));
        event.put("deepLink", DEEP_LINK_PREFIX + task.taskId());
        ArrayNode array = event.putArray("recipients");
        for (NotificationRecipientRow recipient : recipients) {
            ObjectNode node = array.addObject();
            node.put("userId", recipient.userId());
            node.put("loginId", recipient.loginId());
            node.put("userName", recipient.userName());
            node.put("role", recipient.role());
        }
        return event.toString();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
