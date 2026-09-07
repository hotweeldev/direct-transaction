package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import id.co.bni.direct.transaction.dto.request.TaskRequests.BulkActionRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpRequest;
import id.co.bni.direct.transaction.entity.TrxTaskRows.CandidateRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.StageRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.TaskRow;
import id.co.bni.direct.transaction.exception.BulkAbortedException;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient.Verification;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.impl.ExecutionOutbox;
import id.co.bni.direct.transaction.service.impl.TaskApprovalServiceImpl;
import id.co.bni.direct.transaction.service.impl.TransferServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The batch path: many tasks, one token, all-or-nothing.
 *
 * <p>The assertions worth reading are the ones about ORDER. Whether the OTP is spent
 * before or after the eligibility sweep is not a style question - it decides whether a
 * user whose selection was stale has to re-open their token to be told so. Two tests pin
 * that down (nothing verified on abort; verified exactly once on success), because it is
 * the kind of thing a later refactor moves without noticing.
 */
class TaskApprovalBulkTest {

    private static final String COMPANY = "CORP1";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 31, 10, 0);
    private static final OtpRequest OTP = new OtpRequest("CH-9", "654321");

    private TrxTaskMapper trxTaskMapper;
    private UmasAuthenticatorClient authenticatorClient;
    private ExecutionService executionService;
    private ExecutionOutbox executionOutbox;
    private TaskApprovalServiceImpl service;

    @BeforeEach
    void setUp() {
        trxTaskMapper = mock(TrxTaskMapper.class);
        authenticatorClient = mock(UmasAuthenticatorClient.class);
        executionService = mock(ExecutionService.class);
        executionOutbox = mock(ExecutionOutbox.class);
        service = new TaskApprovalServiceImpl(trxTaskMapper, mock(TransferMapper.class),
                mock(LimitService.class), authenticatorClient,
                executionService, executionOutbox, mock(PlatformTransactionManager.class), 50);
        when(authenticatorClient.verifyTransaction("ani", "CH-9", "654321"))
                .thenReturn(new Verification(true, "VER-9"));
        when(trxTaskMapper.updateTaskProgress(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(1);
    }

    // ---- helpers ----

    private static TaskRow task(String id, String status, Integer currentStageSeq) {
        return new TaskRow(id, "REF-" + id, TransferServiceImpl.MENU_CD,
                TransferServiceImpl.SRVC_IN_HOUSE_3RD,
                status, currentStageSeq, new BigDecimal("10000000"), "IDR",
                "113179933", "1000533372", "PT MAJU JAYA", "pembayaran vendor",
                "BUDI SANTOSO", CREATED, 3L, null, null, null,
                null, null, null, null, null, null, null);
    }

    /** One task waiting on a single APPROVAL stage that ani may act on. */
    private void stubApprovable(String taskId) {
        when(trxTaskMapper.findTask(COMPANY, taskId)).thenReturn(task(taskId, "PENDING_APPROVAL", 1));
        when(trxTaskMapper.findStages(taskId)).thenReturn(List.of(
                new StageRow(1, "APPROVAL", "AL02", "2", 1, 0, "ACTIVE"),
                new StageRow(2, "RELEASE", null, null, 1, 0, "WAITING")));
        when(trxTaskMapper.findCandidate(taskId, 1, "ani"))
                .thenReturn(new CandidateRow("CU2", "ANI LESTARI", "GRP1"));
        when(trxTaskMapper.countStageActions(taskId, 1, "CU2")).thenReturn(0);
    }

    private static BulkActionRequest bulk(List<String> ids, String note, boolean dryRun) {
        return new BulkActionRequest("ani", ids, note, dryRun ? null : OTP, dryRun);
    }

    // ---- the happy path ----

    @Test
    void approvingThreeTasksSpendsExactlyOneTokenVerification() {
        stubApprovable("T1");
        stubApprovable("T2");
        stubApprovable("T3");

        var response = service.bulkApprove(COMPANY, "ani", bulk(List.of("T3", "T1", "T2"), null, false));

        assertThat(response.requested()).isEqualTo(3);
        assertThat(response.succeeded()).isEqualTo(3);
        assertThat(response.dryRun()).isFalse();
        assertThat(response.results()).extracting("taskId").containsExactly("T1", "T2", "T3");
        // Every task moved on to its release stage - one approval level, so the stage completes.
        assertThat(response.results()).allMatch(r -> "PENDING_RELEASE".equals(r.status()));
        verify(authenticatorClient, times(1)).verifyTransaction("ani", "CH-9", "654321");
    }

    @Test
    void everyActionRowOfOneBatchCarriesTheSameVerificationId() {
        stubApprovable("T1");
        stubApprovable("T2");

        service.bulkApprove(COMPANY, "ani", bulk(List.of("T1", "T2"), null, false));

        var captor = ArgumentCaptor.forClass(id.co.bni.direct.transaction.entity.TrxTaskRows.ActionInsert.class);
        verify(trxTaskMapper, times(2)).insertAction(captor.capture());
        assertThat(captor.getAllValues()).extracting("otpVerificationId")
                .containsExactly("VER-9", "VER-9");
    }

    // ---- all-or-nothing ----

    @Test
    void oneIneligibleTaskAbortsTheBatchAndReportsEveryOffender() {
        stubApprovable("T1");
        stubApprovable("T2");
        // T2 was already approved by this user; T3 does not exist at all.
        when(trxTaskMapper.countStageActions("T2", 1, "CU2")).thenReturn(1);
        when(trxTaskMapper.findTask(COMPANY, "T3")).thenReturn(null);

        assertThatThrownBy(() -> service.bulkApprove(COMPANY, "ani", bulk(List.of("T1", "T2", "T3"), null, false)))
                .isInstanceOf(BulkAbortedException.class)
                .satisfies(ex -> assertThat(((BulkAbortedException) ex).failures())
                        .extracting("taskId", "errorCode")
                        .containsExactly(
                                org.assertj.core.api.Assertions.tuple("T2", "ALREADY_ACTED"),
                                org.assertj.core.api.Assertions.tuple("T3", "TASK_NOT_FOUND")));

        // Nothing was written - not even for the task that WAS eligible.
        verify(trxTaskMapper, never()).insertAction(any());
        verify(trxTaskMapper, never()).updateTaskProgress(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void anAbortedBatchNeverTouchesTheToken() {
        stubApprovable("T1");
        when(trxTaskMapper.findTask(COMPANY, "T2")).thenReturn(null);

        assertThatThrownBy(() -> service.bulkApprove(COMPANY, "ani", bulk(List.of("T1", "T2"), null, false)))
                .isInstanceOf(BulkAbortedException.class);

        // The whole point of checking everything first: a doomed batch costs no OTP, so the
        // user fixes their selection and retries with the token they still hold.
        verify(authenticatorClient, never()).verifyTransaction(anyString(), anyString(), anyString());
    }

    // ---- homogeneous batches ----

    @Test
    void aTaskAwaitingReleaseIsRefusedByBulkApprove() {
        when(trxTaskMapper.findTask(COMPANY, "T1")).thenReturn(task("T1", "PENDING_RELEASE", 2));
        when(trxTaskMapper.findStages("T1")).thenReturn(List.of(
                new StageRow(1, "APPROVAL", "AL02", "2", 1, 1, "DONE"),
                new StageRow(2, "RELEASE", null, null, 1, 0, "ACTIVE")));
        when(trxTaskMapper.findCandidate("T1", 2, "ani"))
                .thenReturn(new CandidateRow("CU2", "ANI LESTARI", "GRP1"));

        assertThatThrownBy(() -> service.bulkApprove(COMPANY, "ani", bulk(List.of("T1"), null, false)))
                .isInstanceOf(BulkAbortedException.class)
                .satisfies(ex -> assertThat(((BulkAbortedException) ex).failures())
                        .extracting("errorCode").containsExactly("STAGE_MISMATCH"));
    }

    @Test
    void bulkReleaseActsOnTheReleaseStage() {
        when(trxTaskMapper.findTask(COMPANY, "T1")).thenReturn(task("T1", "PENDING_RELEASE", 2));
        when(trxTaskMapper.findStages("T1")).thenReturn(List.of(
                new StageRow(1, "APPROVAL", "AL02", "2", 1, 1, "DONE"),
                new StageRow(2, "RELEASE", null, null, 1, 0, "ACTIVE")));
        when(trxTaskMapper.findCandidate("T1", 2, "ani"))
                .thenReturn(new CandidateRow("CU2", "ANI LESTARI", "GRP1"));
        when(trxTaskMapper.countStageActions("T1", 2, "CU2")).thenReturn(0);
        when(executionService.execute("T1"))
                .thenReturn(new ExecutionService.ExecutionResult("EXECUTED", null));

        var response = service.bulkRelease(COMPANY, "ani", bulk(List.of("T1"), null, false));

        // The release completed the workflow, so the seam ran after the commit and the
        // answer carries the execution verdict rather than READY_TO_EXECUTE.
        assertThat(response.results().get(0).status()).isEqualTo("EXECUTED");
        verify(executionService).execute("T1");
    }

    // ---- reject ----

    @Test
    void bulkRejectDemandsANoteBeforeItLooksAtAnything() {
        assertThatThrownBy(() -> service.bulkReject(COMPANY, "ani", bulk(List.of("T1"), "  ", false)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).code()).isEqualTo("NOTE_REQUIRED"));
        verify(trxTaskMapper, never()).findTask(anyString(), anyString());
    }

    @Test
    void bulkRejectClosesTheStagesOfEveryTask() {
        stubApprovable("T1");
        stubApprovable("T2");

        var response = service.bulkReject(COMPANY, "ani", bulk(List.of("T1", "T2"), "data salah", false));

        assertThat(response.results()).allMatch(r -> "REJECTED".equals(r.status()));
        verify(trxTaskMapper).closeOpenStages(eq("T1"), anyString());
        verify(trxTaskMapper).closeOpenStages(eq("T2"), anyString());
    }

    // ---- shape guards ----

    @Test
    void aRepeatedTaskIdIsRefusedAsACallerBug() {
        assertThatThrownBy(() -> service.bulkApprove(COMPANY, "ani", bulk(List.of("T1", "T1"), null, false)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).code()).isEqualTo("DUPLICATE_TASK_ID"));
    }

    @Test
    void aBatchOverTheConfiguredCeilingIsRefused() {
        var small = new TaskApprovalServiceImpl(trxTaskMapper, mock(TransferMapper.class),
                mock(LimitService.class), authenticatorClient,
                executionService, executionOutbox, mock(PlatformTransactionManager.class), 2);

        assertThatThrownBy(() -> small.bulkApprove(COMPANY, "ani", bulk(List.of("T1", "T2", "T3"), null, false)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).code()).isEqualTo("BATCH_TOO_LARGE"));
    }

    // ---- dry run ----

    @Test
    void aDryRunValidatesTheSelectionAndWritesNothing() {
        stubApprovable("T1");
        stubApprovable("T2");

        var response = service.bulkApprove(COMPANY, "ani", bulk(List.of("T1", "T2"), null, true));

        assertThat(response.dryRun()).isTrue();
        assertThat(response.requested()).isEqualTo(2);
        assertThat(response.succeeded()).isZero();
        assertThat(response.results()).allMatch(r -> "PENDING_APPROVAL".equals(r.status()));
        verify(authenticatorClient, never()).verifyTransaction(anyString(), anyString(), anyString());
        verify(trxTaskMapper, never()).insertAction(any());
    }

    @Test
    void aDryRunStillReportsAStaleSelection() {
        stubApprovable("T1");
        when(trxTaskMapper.findTask(COMPANY, "T2")).thenReturn(null);

        assertThatThrownBy(() -> service.bulkApprove(COMPANY, "ani", bulk(List.of("T1", "T2"), null, true)))
                .isInstanceOf(BulkAbortedException.class);
    }

    @Test
    void aRealRunWithoutATokenIsRefused() {
        assertThatThrownBy(() -> service.bulkApprove(COMPANY, "ani",
                new BulkActionRequest("ani", List.of("T1"), null, null, false)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).code()).isEqualTo("OTP_REQUIRED"));
    }

    // ---- concurrency ----

    @Test
    void aTaskAnotherActorMovedFailsTheClaimAndAbortsTheBatch() {
        stubApprovable("T1");
        stubApprovable("T2");
        // The optimistic claim is what settles a race: 0 rows means somebody else acted
        // between the read and the write.
        when(trxTaskMapper.updateTaskProgress(eq("T2"), any(), anyString(), any(), anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.bulkApprove(COMPANY, "ani", bulk(List.of("T1", "T2"), null, false)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).code()).isEqualTo("TASK_NOT_ACTIONABLE"));
    }
}
