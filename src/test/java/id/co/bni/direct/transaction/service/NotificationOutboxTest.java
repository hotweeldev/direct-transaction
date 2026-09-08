package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.entity.EventOutboxRows.OutboxInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.NotificationRecipientRow;
import id.co.bni.direct.transaction.repository.mapper.ExecutionOutboxMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.impl.NotificationOutbox;
import id.co.bni.direct.transaction.service.impl.NotificationOutbox.NotifiableTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notification producer over mocked mappers: the frozen envelope, and - the part that
 * actually decides whether a person is told about their transfer - WHO each event type
 * resolves as its recipients.
 */
class NotificationOutboxTest {

    private static final String TASK = "T1";

    private static final NotifiableTask TRANSFER = new NotifiableTask(
            TASK, "PTNUSA", "20260908101530238601", "Transfer ke BNI", "GCM_FTR_IH_3RD",
            new BigDecimal("1300000.00"), "IDR", "PENDING_APPROVAL");

    private ExecutionOutboxMapper outboxMapper;
    private TrxTaskMapper trxTaskMapper;
    private NotificationOutbox outbox;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(ExecutionOutboxMapper.class);
        trxTaskMapper = mock(TrxTaskMapper.class);
        outbox = new NotificationOutbox(outboxMapper, trxTaskMapper, new ObjectMapper());
    }

    private static NotificationRecipientRow maker() {
        return new NotificationRecipientRow("CU-MK", "NUSAMK1", "Budi", "MAKER");
    }

    private static NotificationRecipientRow approver(String suffix) {
        return new NotificationRecipientRow("CU-AP" + suffix, "NUSAAP" + suffix,
                "Approver " + suffix, "APPROVER");
    }

    private OutboxInsert captureInsert() {
        ArgumentCaptor<OutboxInsert> row = ArgumentCaptor.forClass(OutboxInsert.class);
        verify(outboxMapper).insert(row.capture());
        return row.getValue();
    }

    private JsonNode capturePayload() throws Exception {
        return new ObjectMapper().readTree(captureInsert().payload());
    }

    private static List<String> userIds(JsonNode payload) {
        return payload.get("recipients").findValuesAsText("userId");
    }

    @Test
    void theEnvelopeCarriesEveryFrozenField() throws Exception {
        when(trxTaskMapper.findNotificationCandidates(TASK, 1))
                .thenReturn(List.of(approver("1")));

        outbox.taskSubmitted(TRANSFER, 1);

        OutboxInsert row = captureInsert();
        assertThat(row.eventType()).isEqualTo("TASK_SUBMITTED");
        // The aggregate id is the taskId - and becomes the Kafka message key, so one
        // task's notification events stay ordered on one partition.
        assertThat(row.aggregateId()).isEqualTo(TASK);

        JsonNode payload = new ObjectMapper().readTree(row.payload());
        // eventId IS the outbox row id: the consumer's idempotency key.
        assertThat(payload.get("eventId").asText()).isEqualTo(row.id()).hasSize(32);
        assertThat(payload.get("eventType").asText()).isEqualTo("TASK_SUBMITTED");
        assertThat(payload.get("corpId").asText()).isEqualTo("PTNUSA");
        assertThat(payload.get("taskId").asText()).isEqualTo(TASK);
        assertThat(payload.get("refNo").asText()).isEqualTo("20260908101530238601");
        assertThat(payload.get("serviceName").asText()).isEqualTo("Transfer ke BNI");
        assertThat(payload.get("serviceCode").asText()).isEqualTo("GCM_FTR_IH_3RD");
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("1300000.00");
        assertThat(payload.get("currency").asText()).isEqualTo("IDR");
        assertThat(payload.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(payload.get("isError").asBoolean()).isFalse();
        assertThat(payload.get("deepLink").asText()).isEqualTo("/transaksi/pending-task/" + TASK);
        // An offset, unlike this service's HTTP timestamps: the value crosses a broker.
        assertThat(payload.get("occurredAt").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}");

        JsonNode recipient = payload.get("recipients").get(0);
        assertThat(recipient.get("userId").asText()).isEqualTo("CU-AP1");
        assertThat(recipient.get("loginId").asText()).isEqualTo("NUSAAP1");
        assertThat(recipient.get("userName").asText()).isEqualTo("Approver 1");
        assertThat(recipient.get("role").asText()).isEqualTo("APPROVER");
    }

    @Test
    void submittedGoesToTheCandidatesOfTheActiveStageOnly() throws Exception {
        when(trxTaskMapper.findNotificationCandidates(TASK, 1))
                .thenReturn(List.of(approver("1"), approver("2")));

        outbox.taskSubmitted(TRANSFER, 1);

        assertThat(userIds(capturePayload())).containsExactly("CU-AP1", "CU-AP2");
        // The maker submitted it; telling them what they just did is noise.
        verify(trxTaskMapper, never()).findNotificationMaker(anyString());
    }

    @Test
    void approvedGoesToTheNewlyActiveStageAndTheMaker() throws Exception {
        when(trxTaskMapper.findNotificationCandidates(TASK, 2))
                .thenReturn(List.of(approver("2")));
        when(trxTaskMapper.findNotificationMaker(TASK)).thenReturn(maker());

        outbox.taskApproved(TRANSFER, 2);

        assertThat(userIds(capturePayload())).containsExactly("CU-AP2", "CU-MK");
    }

    @Test
    void rejectedGoesToTheMakerAndEveryoneWhoActed() throws Exception {
        when(trxTaskMapper.findNotificationMaker(TASK)).thenReturn(maker());
        when(trxTaskMapper.findNotificationActors(TASK, List.of("APPROVE", "RELEASE", "REJECT")))
                .thenReturn(List.of(approver("1")));

        outbox.taskRejected(new NotifiableTask(TASK, "PTNUSA", "REF", "Transfer ke BNI",
                "GCM_FTR_IH_3RD", BigDecimal.TEN, "IDR", "REJECTED"));

        JsonNode payload = capturePayload();
        assertThat(userIds(payload)).containsExactly("CU-MK", "CU-AP1");
        // A rejection is the FE's red notification.
        assertThat(payload.get("isError").asBoolean()).isTrue();
    }

    @Test
    void releasedGoesToTheMakerAlone() throws Exception {
        when(trxTaskMapper.findNotificationMaker(TASK)).thenReturn(maker());

        outbox.taskReleased(TRANSFER);

        assertThat(userIds(capturePayload())).containsExactly("CU-MK");
        verify(trxTaskMapper, never()).findNotificationActors(anyString(), any());
    }

    @Test
    void aVerdictGoesToTheMakerAndEveryoneWhoApprovedOrReleased() throws Exception {
        when(trxTaskMapper.findNotificationMaker(TASK)).thenReturn(maker());
        when(trxTaskMapper.findNotificationActors(TASK, List.of("APPROVE", "RELEASE")))
                .thenReturn(List.of(approver("1"),
                        new NotificationRecipientRow("CU-RL", "NUSARL1", "Rina", "RELEASER")));

        outbox.transactionVerdict(new NotifiableTask(TASK, "PTNUSA", "REF", "Transfer ke BNI",
                "GCM_FTR_IH_3RD", BigDecimal.TEN, "IDR", "EXECUTED"), "EXECUTED");

        JsonNode payload = capturePayload();
        assertThat(payload.get("eventType").asText()).isEqualTo("TRANSACTION_EXECUTED");
        assertThat(payload.get("isError").asBoolean()).isFalse();
        assertThat(userIds(payload)).containsExactly("CU-MK", "CU-AP1", "CU-RL");
    }

    @Test
    void failedAndUnknownVerdictsAreErrorEvents() throws Exception {
        when(trxTaskMapper.findNotificationMaker(TASK)).thenReturn(maker());

        outbox.transactionVerdict(TRANSFER, "FAILED");
        outbox.transactionVerdict(TRANSFER, "UNKNOWN");

        ArgumentCaptor<OutboxInsert> rows = ArgumentCaptor.forClass(OutboxInsert.class);
        verify(outboxMapper, org.mockito.Mockito.times(2)).insert(rows.capture());
        ObjectMapper json = new ObjectMapper();
        assertThat(rows.getAllValues()).extracting(OutboxInsert::eventType)
                .containsExactly("TRANSACTION_FAILED", "TRANSACTION_UNKNOWN");
        for (OutboxInsert row : rows.getAllValues()) {
            assertThat(json.readTree(row.payload()).get("isError").asBoolean()).isTrue();
        }
    }

    /**
     * READY_TO_EXECUTE is what the seam answers on a laptop with the core hop disabled,
     * and the live status is what it answers when it loses the double-execute claim.
     * Neither is a verdict; announcing them would tell a maker their transfer finished.
     */
    @Test
    void aNonVerdictStatusAnnouncesNothing() {
        outbox.transactionVerdict(TRANSFER, "READY_TO_EXECUTE");
        outbox.transactionVerdict(TRANSFER, "EXECUTING");

        verify(outboxMapper, never()).insert(any());
    }

    @Test
    void onePersonInTwoRolesGetsOneRecipientEntry() throws Exception {
        // A maker who also holds the releaser role is both the maker and a candidate.
        when(trxTaskMapper.findNotificationCandidates(TASK, 2))
                .thenReturn(List.of(new NotificationRecipientRow("CU-MK", "NUSAMK1", "Budi",
                        "RELEASER")));
        when(trxTaskMapper.findNotificationMaker(TASK)).thenReturn(maker());

        outbox.taskApproved(TRANSFER, 2);

        JsonNode payload = capturePayload();
        assertThat(userIds(payload)).containsExactly("CU-MK");
        // The candidate role wins: it is the one that explains why they are being told.
        assertThat(payload.get("recipients").get(0).get("role").asText()).isEqualTo("RELEASER");
    }

    @Test
    void anEventWithNoRecipientsIsNotWritten() {
        when(trxTaskMapper.findNotificationCandidates(TASK, 1)).thenReturn(List.of());

        outbox.taskSubmitted(TRANSFER, 1);

        verify(outboxMapper, never()).insert(any());
    }

    /**
     * The property the whole design rests on: a notification must never be the reason a
     * released transfer rolls back. A resolver that blows up is logged and swallowed.
     */
    @Test
    void aFailureResolvingRecipientsNeverEscapesIntoTheWorkflow() {
        when(trxTaskMapper.findNotificationMaker(TASK))
                .thenThrow(new IllegalStateException("ORA-00942: table or view does not exist"));

        outbox.taskReleased(TRANSFER);

        verify(outboxMapper, never()).insert(any());
    }

    @Test
    void aFailedInsertNeverEscapesIntoTheWorkflowEither() {
        when(trxTaskMapper.findNotificationMaker(TASK)).thenReturn(maker());
        when(outboxMapper.insert(any())).thenThrow(new IllegalStateException("ORA-01653"));

        outbox.taskReleased(TRANSFER);
        // Nothing thrown - the assertion is the absence of an exception.
    }

    @Test
    void theRelayRoutingSetIsExactlyTheContractVocabulary() {
        for (String eventType : List.of("TASK_SUBMITTED", "TASK_APPROVED", "TASK_REJECTED",
                "TASK_RELEASED", "TRANSACTION_EXECUTED", "TRANSACTION_FAILED",
                "TRANSACTION_UNKNOWN")) {
            assertThat(NotificationOutbox.isNotificationEvent(eventType))
                    .as(eventType).isTrue();
        }
        assertThat(NotificationOutbox.isNotificationEvent("EXECUTION_REQUESTED")).isFalse();
        assertThat(NotificationOutbox.isNotificationEvent("SOMETHING_ELSE")).isFalse();
    }

    /** A null stage seq (a single-user task has no stage) resolves to nobody, not a crash. */
    @Test
    void aSubmitWithNoStageAnnouncesNothing() {
        outbox.taskSubmitted(TRANSFER, null);

        verify(outboxMapper, never()).insert(any());
        verify(trxTaskMapper, never()).findNotificationCandidates(anyString(), eq(null));
    }
}
