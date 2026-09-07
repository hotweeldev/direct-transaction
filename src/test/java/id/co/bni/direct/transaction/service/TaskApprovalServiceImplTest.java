package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import id.co.bni.direct.transaction.dto.request.TaskRequests.ApproveTaskRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.RejectTaskRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpRequest;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.CandidateRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.InboxRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.StageRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.TaskRow;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.NotFoundException;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mock the mapper, the authenticator and the execution seam; drive the inbox and the
 * approve/reject ladder. No Spring context, no Oracle.
 */
class TaskApprovalServiceImplTest {

    private static final String COMPANY = "CORP1";
    private static final String TASK = "T1";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 31, 10, 0);

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
        // Mockito default: isKafkaMode() answers false, so every test runs sync mode
        // unless it stubs kafka mode explicitly.
        executionOutbox = mock(ExecutionOutbox.class);
        // A mocked manager makes the TransactionTemplate run its callback with no real
        // transaction - the commit-before-execute ordering is structural, not asserted.
        service = new TaskApprovalServiceImpl(trxTaskMapper, mock(TransferMapper.class),
                mock(LimitService.class), authenticatorClient,
                executionService, executionOutbox, mock(PlatformTransactionManager.class), 50);
    }

    private static TaskRow task(String status, Integer currentStageSeq, long version) {
        return new TaskRow(TASK, "20260831100000228541", TransferServiceImpl.MENU_CD,
                TransferServiceImpl.SRVC_IN_HOUSE_3RD,
                status, currentStageSeq, new BigDecimal("10000000"), "IDR",
                "113179933", "1000533372", "PT MAJU JAYA", "pembayaran vendor",
                "BUDI SANTOSO", CREATED, version, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static StageRow stage(int seq, String type, int required, int completed, String status) {
        return new StageRow(seq, type, "AL02", "2", required, completed, status);
    }

    private static ApproveTaskRequest approve(String userId) {
        return new ApproveTaskRequest(userId, "setuju", new OtpRequest("CH-9", "654321"));
    }

    private static RejectTaskRequest reject(String userId) {
        return new RejectTaskRequest(userId, "data salah", new OtpRequest("CH-9", "654321"));
    }

    /** ani is a frozen candidate on the given stage, has not acted, and her OTP is good. */
    private void stubEligible(int stageSeq) {
        when(trxTaskMapper.findCandidate(TASK, stageSeq, "ani"))
                .thenReturn(new CandidateRow("CU2", "ANI LESTARI", "GRP1"));
        when(trxTaskMapper.countStageActions(TASK, stageSeq, "CU2")).thenReturn(0);
        when(authenticatorClient.verifyTransaction("ani", "CH-9", "654321"))
                .thenReturn(new Verification(true, "VER-9"));
        when(trxTaskMapper.updateTaskProgress(eq(TASK), any(), anyString(), any(), anyString()))
                .thenReturn(1);
    }

    // ---- Inbox ----

    @Test
    void inboxMapsRowsOntoTheWireShape() {
        when(trxTaskMapper.findInbox(COMPANY, "ani")).thenReturn(List.of(new InboxRow(
                TASK, "20260831100000228541", TransferServiceImpl.MENU_CD, "PENDING_APPROVAL",
                new BigDecimal("10000000"), "IDR", "113179933", "1000533372", "PT MAJU JAYA",
                "pembayaran vendor", "BUDI SANTOSO", CREATED, 1, "APPROVAL", 2, 1)));

        var inbox = service.inbox(COMPANY, "ani");

        assertThat(inbox.items()).hasSize(1);
        var item = inbox.items().get(0);
        assertThat(item.taskId()).isEqualTo(TASK);
        assertThat(item.menuName()).isEqualTo("Transfer ke BNI");
        assertThat(item.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(item.amount()).isEqualByComparingTo("10000000");
        assertThat(item.currency()).isEqualTo("IDR");
        assertThat(item.sourceAccountNo()).isEqualTo("113179933");
        assertThat(item.beneficiaryAccountNo()).isEqualTo("1000533372");
        assertThat(item.beneficiaryName()).isEqualTo("PT MAJU JAYA");
        assertThat(item.makerName()).isEqualTo("BUDI SANTOSO");
        assertThat(item.myStage().seqNo()).isEqualTo(1);
        assertThat(item.myStage().stageType()).isEqualTo("APPROVAL");
        assertThat(item.myStage().requiredCount()).isEqualTo(2);
        assertThat(item.myStage().completedCount()).isEqualTo(1);
    }

    @Test
    void anEmptyInboxAnswersAnEmptyList() {
        when(trxTaskMapper.findInbox(COMPANY, "ani")).thenReturn(List.of());
        assertThat(service.inbox(COMPANY, "ani").items()).isEmpty();
    }

    // ---- Approve progression ----

    @Test
    void anApprovalShortOfTheRequiredCountKeepsTheStageActive() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 1, 0));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 2, 0, "ACTIVE"),
                stage(2, "RELEASE", 1, 0, "WAITING")));
        stubEligible(1);

        var response = service.approve(COMPANY, "actor", TASK, approve("ani"));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        // The claim keeps status and stage; only VERSION moves.
        verify(trxTaskMapper).updateTaskProgress(TASK, 0L, "PENDING_APPROVAL", 1, "actor");
        verify(trxTaskMapper).updateStageProgress(TASK, 1, 1, "ACTIVE", "actor");
        verify(trxTaskMapper, never()).updateStageStatus(anyString(), anyInt(), anyString(), anyString());
        verify(executionService, never()).execute(anyString());

        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().action()).isEqualTo("APPROVE");
        assertThat(action.getValue().actorUserId()).isEqualTo("CU2");
        assertThat(action.getValue().actorUserName()).isEqualTo("ANI LESTARI");
        assertThat(action.getValue().stageSeq()).isEqualTo(1);
        assertThat(action.getValue().otpVerificationId()).isEqualTo("VER-9");
        assertThat(action.getValue().note()).isEqualTo("setuju");
    }

    @Test
    void completingAnApprovalStageActivatesTheNextApprovalStage() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 1, 3));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 1, 0, "ACTIVE"),
                stage(2, "APPROVAL", 1, 0, "WAITING"),
                stage(3, "RELEASE", 1, 0, "WAITING")));
        stubEligible(1);

        var response = service.approve(COMPANY, "actor", TASK, approve("ani"));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        verify(trxTaskMapper).updateTaskProgress(TASK, 3L, "PENDING_APPROVAL", 2, "actor");
        verify(trxTaskMapper).updateStageProgress(TASK, 1, 1, "DONE", "actor");
        verify(trxTaskMapper).updateStageStatus(TASK, 2, "ACTIVE", "actor");
    }

    @Test
    void completingTheLastApprovalStageMovesTheTaskToPendingRelease() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 1, 1));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 2, 1, "ACTIVE"),
                stage(2, "RELEASE", 1, 0, "WAITING")));
        stubEligible(1);

        var response = service.approve(COMPANY, "actor", TASK, approve("ani"));

        assertThat(response.status()).isEqualTo("PENDING_RELEASE");
        verify(trxTaskMapper).updateTaskProgress(TASK, 1L, "PENDING_RELEASE", 2, "actor");
        verify(trxTaskMapper).updateStageProgress(TASK, 1, 2, "DONE", "actor");
        verify(trxTaskMapper).updateStageStatus(TASK, 2, "ACTIVE", "actor");
        verify(executionService, never()).execute(anyString());
    }

    @Test
    void completingTheReleaseStageExecutesAndAnswersTheVerdict() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_RELEASE", 2, 2));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 1, 1, "DONE"),
                stage(2, "RELEASE", 1, 0, "ACTIVE")));
        stubEligible(2);
        when(executionService.execute(TASK))
                .thenReturn(new ExecutionService.ExecutionResult("EXECUTED", null));

        var response = service.approve(COMPANY, "actor", TASK, approve("ani"));

        // The answer carries the EXECUTION verdict, not READY_TO_EXECUTE.
        assertThat(response.status()).isEqualTo("EXECUTED");
        assertThat(response.message()).isNull();
        verify(trxTaskMapper).updateTaskProgress(TASK, 2L, "READY_TO_EXECUTE", null, "actor");
        verify(trxTaskMapper).updateStageProgress(TASK, 2, 1, "DONE", "actor");

        // A release writes a RELEASE action, not an APPROVE - and wakes the seam.
        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().action()).isEqualTo("RELEASE");
        verify(executionService).execute(TASK);
        // Sync mode regression: nothing is queued into the outbox.
        verify(executionOutbox, never()).enqueueExecutionRequested(anyString(), any(), any());
    }

    @Test
    void inKafkaModeACompletedReleaseQueuesTheTaskAndDoesNotExecuteInRequest() {
        when(executionOutbox.isKafkaMode()).thenReturn(true);
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_RELEASE", 2, 2));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 1, 1, "DONE"),
                stage(2, "RELEASE", 1, 0, "ACTIVE")));
        stubEligible(2);

        var response = service.approve(COMPANY, "actor", TASK, approve("ani"));

        // The answer is QUEUED; the verdict arrives via the consumer later.
        assertThat(response.status()).isEqualTo("QUEUED");
        // Task status and outbox row are written in the SAME workflow transaction...
        verify(trxTaskMapper).updateTaskProgress(TASK, 2L, "QUEUED", null, "actor");
        verify(executionOutbox).enqueueExecutionRequested(
                TASK, "20260831100000228541", COMPANY);
        // ...and the seam is NOT called in the request.
        verify(executionService, never()).execute(anyString());
    }

    @Test
    void inKafkaModeAMidWorkflowApprovalQueuesNothing() {
        when(executionOutbox.isKafkaMode()).thenReturn(true);
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 1, 1));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 2, 1, "ACTIVE"),
                stage(2, "RELEASE", 1, 0, "WAITING")));
        stubEligible(1);

        var response = service.approve(COMPANY, "actor", TASK, approve("ani"));

        assertThat(response.status()).isEqualTo("PENDING_RELEASE");
        verify(executionOutbox, never()).enqueueExecutionRequested(anyString(), any(), any());
        verify(executionService, never()).execute(anyString());
    }

    @Test
    void aFailedExecutionAtReleaseAnswersTheReadableReason() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_RELEASE", 2, 2));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 1, 1, "DONE"),
                stage(2, "RELEASE", 1, 0, "ACTIVE")));
        stubEligible(2);
        when(executionService.execute(TASK)).thenReturn(
                new ExecutionService.ExecutionResult("FAILED", "Core banking menolak transaksi."));

        var response = service.approve(COMPANY, "actor", TASK, approve("ani"));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Core banking menolak transaksi.");
    }

    // ---- Reject ----

    @Test
    void aRejectClosesTheTaskAndEveryOpenStage() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 1, 0));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 2, 1, "ACTIVE"),
                stage(2, "RELEASE", 1, 0, "WAITING")));
        stubEligible(1);

        var response = service.reject(COMPANY, "actor", TASK, reject("ani"));

        assertThat(response.status()).isEqualTo("REJECTED");
        verify(trxTaskMapper).updateTaskProgress(TASK, 0L, "REJECTED", null, "actor");
        verify(trxTaskMapper).closeOpenStages(TASK, "actor");
        verify(trxTaskMapper, never()).updateStageProgress(anyString(), anyInt(), anyInt(), anyString(), anyString());

        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().action()).isEqualTo("REJECT");
        assertThat(action.getValue().note()).isEqualTo("data salah");
        assertThat(action.getValue().stageSeq()).isEqualTo(1);
    }

    @Test
    void aReleaserMayRejectAtTheReleaseStage() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_RELEASE", 2, 5));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 1, 1, "DONE"),
                stage(2, "RELEASE", 1, 0, "ACTIVE")));
        stubEligible(2);

        var response = service.reject(COMPANY, "actor", TASK, reject("ani"));

        assertThat(response.status()).isEqualTo("REJECTED");
        verify(trxTaskMapper).updateTaskProgress(TASK, 5L, "REJECTED", null, "actor");
    }

    // ---- The 422 ladder ----

    private void assertRefused(String code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo(code));
        verify(trxTaskMapper, never()).insertAction(any());
        verify(trxTaskMapper, never()).updateStageProgress(anyString(), anyInt(), anyInt(), anyString(), anyString());
        verify(executionService, never()).execute(anyString());
    }

    @Test
    void anUnknownTaskIs404() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(null);
        assertThatThrownBy(() -> service.approve(COMPANY, "actor", TASK, approve("ani")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aTaskPastItsWorkflowIsNotActionable() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("READY_TO_EXECUTE", null, 4));
        assertRefused("TASK_NOT_ACTIONABLE",
                () -> service.approve(COMPANY, "actor", TASK, approve("ani")));
    }

    @Test
    void aRejectedTaskIsNotActionable() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("REJECTED", null, 4));
        assertRefused("TASK_NOT_ACTIONABLE",
                () -> service.reject(COMPANY, "actor", TASK, reject("ani")));
    }

    @Test
    void aStatusStageMismatchIsNotActionable() {
        // The task claims PENDING_APPROVAL but the active stage is the RELEASE stage -
        // inconsistent data must refuse, not release under an approval status.
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 2, 1));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 1, 1, "DONE"),
                stage(2, "RELEASE", 1, 0, "ACTIVE")));
        assertRefused("TASK_NOT_ACTIONABLE",
                () -> service.approve(COMPANY, "actor", TASK, approve("ani")));
    }

    @Test
    void aUserOutsideTheFrozenCandidateSetIsNotEligible() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 1, 0));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 1, 0, "ACTIVE"),
                stage(2, "RELEASE", 1, 0, "WAITING")));
        when(trxTaskMapper.findCandidate(TASK, 1, "ani")).thenReturn(null);

        assertRefused("NOT_ELIGIBLE",
                () -> service.approve(COMPANY, "actor", TASK, approve("ani")));
    }

    @Test
    void aCandidateWhoAlreadyActedOnTheStageIsRefused() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 1, 0));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 2, 1, "ACTIVE"),
                stage(2, "RELEASE", 1, 0, "WAITING")));
        when(trxTaskMapper.findCandidate(TASK, 1, "ani"))
                .thenReturn(new CandidateRow("CU2", "ANI LESTARI", "GRP1"));
        when(trxTaskMapper.countStageActions(TASK, 1, "CU2")).thenReturn(1);

        assertRefused("ALREADY_ACTED",
                () -> service.approve(COMPANY, "actor", TASK, approve("ani")));
    }

    @Test
    void aFailedOtpRefusesTheApproveBeforeAnyWrite() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 1, 0));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 1, 0, "ACTIVE"),
                stage(2, "RELEASE", 1, 0, "WAITING")));
        when(trxTaskMapper.findCandidate(TASK, 1, "ani"))
                .thenReturn(new CandidateRow("CU2", "ANI LESTARI", "GRP1"));
        when(trxTaskMapper.countStageActions(TASK, 1, "CU2")).thenReturn(0);
        when(authenticatorClient.verifyTransaction("ani", "CH-9", "654321"))
                .thenReturn(new Verification(false, null));

        assertRefused("OTP_INVALID",
                () -> service.approve(COMPANY, "actor", TASK, approve("ani")));
        verify(trxTaskMapper, never()).updateTaskProgress(anyString(), any(), anyString(), any(), anyString());
    }

    // ---- The double-approve guard ----

    @Test
    void aStaleVersionClaimWritesNothingAndAnswersNotActionable() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 1, 7));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 1, 0, "ACTIVE"),
                stage(2, "RELEASE", 1, 0, "WAITING")));
        stubEligible(1);
        // Another approve/reject bumped VERSION between this caller's read and write.
        when(trxTaskMapper.updateTaskProgress(eq(TASK), any(), anyString(), any(), anyString()))
                .thenReturn(0);

        assertRefused("TASK_NOT_ACTIONABLE",
                () -> service.approve(COMPANY, "actor", TASK, approve("ani")));
        verify(trxTaskMapper).updateTaskProgress(TASK, 7L, "PENDING_RELEASE", 2, "actor");
        verify(trxTaskMapper, never()).updateStageStatus(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void aStaleVersionOnRejectAlsoWritesNothing() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(task("PENDING_APPROVAL", 1, 7));
        when(trxTaskMapper.findStages(TASK)).thenReturn(List.of(
                stage(1, "APPROVAL", 1, 0, "ACTIVE"),
                stage(2, "RELEASE", 1, 0, "WAITING")));
        stubEligible(1);
        when(trxTaskMapper.updateTaskProgress(eq(TASK), any(), anyString(), isNull(), anyString()))
                .thenReturn(0);

        assertRefused("TASK_NOT_ACTIONABLE",
                () -> service.reject(COMPANY, "actor", TASK, reject("ani")));
        verify(trxTaskMapper, never()).closeOpenStages(anyString(), anyString());
    }
}
