package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.util.List;

import id.co.bni.direct.transaction.dto.request.TransferRequests.MoneyRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.SubmitTransferRequest;
import id.co.bni.direct.transaction.entity.TransferRows.BankLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.CorpFlagsRow;
import id.co.bni.direct.transaction.entity.TransferRows.MakerRow;
import id.co.bni.direct.transaction.entity.TransferRows.MatrixBandRow;
import id.co.bni.direct.transaction.entity.TransferRows.MatrixSignatureRow;
import id.co.bni.direct.transaction.entity.TransferRows.UsageLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.WorkflowUserRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.integration.AccountNameClient;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient.Verification;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.impl.ExecutionOutbox;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mock the mappers and both clients, drive the submit pipeline, assert on the wire shape
 * and on what gets written. No Spring context, no Oracle.
 */
class TransferServiceImplTest {

    private static final String COMPANY = "CORP1";
    private static final String SOURCE = "113179933";
    private static final String BENEFICIARY = "1000533372";

    private TransferMapper transferMapper;
    private TrxTaskMapper trxTaskMapper;
    private AccountNameClient accountNameClient;
    private UmasAuthenticatorClient authenticatorClient;
    private ExecutionService executionService;
    private ExecutionOutbox executionOutbox;
    private TransferServiceImpl service;

    @BeforeEach
    void setUp() {
        transferMapper = mock(TransferMapper.class);
        trxTaskMapper = mock(TrxTaskMapper.class);
        accountNameClient = mock(AccountNameClient.class);
        authenticatorClient = mock(UmasAuthenticatorClient.class);
        executionService = mock(ExecutionService.class);
        // Mockito default: isKafkaMode() answers false = sync mode, today's behavior.
        executionOutbox = mock(ExecutionOutbox.class);
        service = new TransferServiceImpl(transferMapper, trxTaskMapper,
                accountNameClient, authenticatorClient, executionService,
                executionOutbox, mock(PlatformTransactionManager.class));
    }

    private static WorkflowUserRow user(String corpUserId, String login, String name,
                                        String groupId, String role, String level) {
        return new WorkflowUserRow(corpUserId, login, name, groupId, role, level);
    }

    private static SubmitTransferRequest request(String amount) {
        return new SubmitTransferRequest("budi", SOURCE, BENEFICIARY, "PT MAJU JAYA",
                new MoneyRequest(new BigDecimal(amount), "IDR"), "pembayaran vendor",
                new OtpRequest("CH-1", "123456"));
    }

    /** Every stub a clean multi-stage submit needs; individual tests then break one rung. */
    private void stubHappyPath() {
        when(transferMapper.findMaker(COMPANY, "budi")).thenReturn(new MakerRow(
                "CU1", "budi", "BUDI SANTOSO", "GRP1", "AG1", "CRP_USR_MK", "AL01", "SMS_OTP"));
        when(transferMapper.countDebitAccount("AG1", SOURCE)).thenReturn(1);
        when(transferMapper.countAnyAccount("AG1", BENEFICIARY)).thenReturn(0);
        when(transferMapper.findBankLimit(anyString(), eq("IDR")))
                .thenReturn(new BankLimitRow(new BigDecimal("1000"), new BigDecimal("1000000000")));
        when(transferMapper.findCorpLimit(eq(COMPANY), anyString(), eq("IDR")))
                .thenReturn(new UsageLimitRow(new BigDecimal("100000"), new BigDecimal("2000000000")));
        when(transferMapper.findGroupLimit(eq("GRP1"), anyString(), eq("IDR")))
                .thenReturn(new UsageLimitRow(BigDecimal.ZERO, new BigDecimal("1500000000")));
        when(transferMapper.findAccountDebitLimit(COMPANY, SOURCE))
                .thenReturn(new BigDecimal("900000000"));
        when(transferMapper.findMakerSchemeLimit(COMPANY, "AL01", "IDR"))
                .thenReturn(new BigDecimal("800000000"));
        when(authenticatorClient.verifyTransaction("budi", "CH-1", "123456"))
                .thenReturn(new Verification(true, "VER-1"));
        when(transferMapper.findCorpFlags(COMPANY)).thenReturn(new CorpFlagsRow("N", "N"));
        when(transferMapper.findMatrixMasterId(COMPANY, TransferServiceImpl.MENU_CD, "IDR"))
                .thenReturn("MSTR1");
        when(transferMapper.findMatrixBands("MSTR1")).thenReturn(List.of(
                new MatrixBandRow("BAND1", new BigDecimal("50000000"), 1),
                new MatrixBandRow("BAND2", new BigDecimal("500000000"), 2)));
        when(transferMapper.findBandSignatures("BAND1")).thenReturn(List.of(
                new MatrixSignatureRow(1, 2, "AL02", "2", null)));
        when(transferMapper.findBandSignatures("BAND2")).thenReturn(List.of(
                new MatrixSignatureRow(1, 1, "AL02", "1", null),
                new MatrixSignatureRow(2, 1, "AL03", "4", "TARGET_GRP")));
        when(transferMapper.lockRefNoValue(anyString(), eq(COMPANY))).thenReturn(228540L);
        // The company's workflow users, the raw material candidate materialization
        // filters: the maker (no AP/RL), two same-level approvers in different groups,
        // one approver in the '4'-option target group, and one releaser.
        when(transferMapper.findWorkflowUsers(COMPANY)).thenReturn(List.of(
                user("CU1", "budi", "BUDI SANTOSO", "GRP1", "CRP_USR_MK", "AL01"),
                user("CU2", "ani", "ANI LESTARI", "GRP1", "CRP_USR_AP", "AL02"),
                user("CU3", "cici", "CICI PARAMIDA", "GRP2", "CRP_USR_AP", "AL02"),
                user("CU4", "dodi", "DODI PRASETYO", "TARGET_GRP", "CRP_USR_AP", "AL03"),
                user("CU5", "eka", "EKA PUTRI", "GRP1", "CRP_USR_RL", null)));
    }

    private List<TrxTaskRows.CandidateInsert> submittedCandidates(String amount) {
        service.submit(COMPANY, "budi", request(amount));
        ArgumentCaptor<TrxTaskRows.CandidateInsert> captor =
                ArgumentCaptor.forClass(TrxTaskRows.CandidateInsert.class);
        verify(trxTaskMapper, org.mockito.Mockito.atLeastOnce()).insertCandidate(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void happyPathBuildsApprovalStagesPlusReleaseAndAnswersPendingApproval() {
        stubHappyPath();

        var response = service.submit(COMPANY, "budi", request("10000000"));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.taskId()).matches("[0-9a-f]{32}");
        assertThat(response.refNo()).matches("\\d{20}").endsWith("228541");
        verify(transferMapper).updateRefNoSeq(anyString(), eq(COMPANY), eq(228541L));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().status()).isEqualTo("PENDING_APPROVAL");
        assertThat(task.getValue().currentStageSeq()).isEqualTo(1);
        assertThat(task.getValue().srvcCd()).isEqualTo(TransferServiceImpl.SRVC_IN_HOUSE_3RD);
        assertThat(task.getValue().makerUserId()).isEqualTo("CU1");
        assertThat(task.getValue().makerUserName()).isEqualTo("BUDI SANTOSO");
        assertThat(task.getValue().isSingleUser()).isEqualTo("N");

        ArgumentCaptor<TrxTaskRows.StageInsert> stages = ArgumentCaptor.forClass(TrxTaskRows.StageInsert.class);
        verify(trxTaskMapper, org.mockito.Mockito.times(2)).insertStage(stages.capture());
        var approval = stages.getAllValues().get(0);
        assertThat(approval.seqNo()).isEqualTo(1);
        assertThat(approval.stageType()).isEqualTo("APPROVAL");
        assertThat(approval.aprvLvlCd()).isEqualTo("AL02");
        assertThat(approval.usrGrpOpt()).isEqualTo("2");
        assertThat(approval.requiredCount()).isEqualTo(2);
        assertThat(approval.status()).isEqualTo("ACTIVE");
        var release = stages.getAllValues().get(1);
        assertThat(release.seqNo()).isEqualTo(2);
        assertThat(release.stageType()).isEqualTo("RELEASE");
        assertThat(release.requiredCount()).isEqualTo(1);
        assertThat(release.status()).isEqualTo("WAITING");

        ArgumentCaptor<TrxTaskRows.ActionInsert> action = ArgumentCaptor.forClass(TrxTaskRows.ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().action()).isEqualTo("SUBMIT");
        assertThat(action.getValue().actorUserId()).isEqualTo("CU1");
        assertThat(action.getValue().actorGroupId()).isEqualTo("GRP1");
        assertThat(action.getValue().otpVerificationId()).isEqualTo("VER-1");
    }

    @Test
    void ownBeneficiaryResolvesTheOwnServiceCode() {
        stubHappyPath();
        when(transferMapper.countAnyAccount("AG1", BENEFICIARY)).thenReturn(1);

        service.submit(COMPANY, "budi", request("10000000"));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().srvcCd()).isEqualTo(TransferServiceImpl.SRVC_IN_HOUSE_OWN);
    }

    @Test
    void amountAboveTheTopBandUsesTheHighestBand() {
        stubHappyPath();

        // 600M is above BAND2's 500M ceiling (and inside every limit rung); the highest
        // band still answers.
        service.submit(COMPANY, "budi", request("600000000"));

        ArgumentCaptor<TrxTaskRows.StageInsert> stages = ArgumentCaptor.forClass(TrxTaskRows.StageInsert.class);
        verify(trxTaskMapper, org.mockito.Mockito.times(3)).insertStage(stages.capture());
        assertThat(stages.getAllValues().get(0).aprvLvlCd()).isEqualTo("AL02");
        assertThat(stages.getAllValues().get(1).aprvLvlCd()).isEqualTo("AL03");
        assertThat(stages.getAllValues().get(1).usrGrpOpt()).isEqualTo("4");
        assertThat(stages.getAllValues().get(1).corpUsrGrpId()).isEqualTo("TARGET_GRP");
        assertThat(stages.getAllValues().get(2).stageType()).isEqualTo("RELEASE");
    }

    @Test
    void singleUserCashLiteCompanySkipsTheMatrixAndExecutesImmediately() {
        stubHappyPath();
        when(transferMapper.findCorpFlags(COMPANY)).thenReturn(new CorpFlagsRow("Y", "Y"));
        when(executionService.execute(anyString()))
                .thenReturn(new ExecutionService.ExecutionResult("EXECUTED", null));

        var response = service.submit(COMPANY, "budi", request("10000000"));

        // Born READY_TO_EXECUTE, executed synchronously - the 201 carries the verdict.
        assertThat(response.status()).isEqualTo("EXECUTED");
        verify(transferMapper, never()).findMatrixMasterId(anyString(), anyString(), anyString());
        verify(transferMapper, never()).findWorkflowUsers(anyString());
        verify(trxTaskMapper, never()).insertStage(any());
        verify(trxTaskMapper, never()).insertCandidate(any());
        // The seam is handed the task AFTER the workflow transaction committed.
        verify(executionService).execute(response.taskId());
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().isSingleUser()).isEqualTo("Y");
        assertThat(task.getValue().currentStageSeq()).isNull();
        // Sync mode regression: nothing is queued into the outbox.
        verify(executionOutbox, never()).enqueueExecutionRequested(anyString(), any(), any());
    }

    @Test
    void inKafkaModeASingleUserSubmitIsBornQueuedWithItsOutboxRow() {
        stubHappyPath();
        when(transferMapper.findCorpFlags(COMPANY)).thenReturn(new CorpFlagsRow("Y", "Y"));
        when(executionOutbox.isKafkaMode()).thenReturn(true);

        var response = service.submit(COMPANY, "budi", request("10000000"));

        // The 201 answers QUEUED; the verdict lands via the consumer later.
        assertThat(response.status()).isEqualTo("QUEUED");
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().status()).isEqualTo("QUEUED");
        // The outbox row is written in the SAME workflow transaction, with the minted refNo.
        verify(executionOutbox).enqueueExecutionRequested(
                task.getValue().id(), response.refNo(), COMPANY);
        // And the execution seam is NOT called in the request.
        verify(executionService, never()).execute(anyString());
    }

    @Test
    void aNewCounterPairIsSeededAtOne() {
        stubHappyPath();
        when(transferMapper.lockRefNoValue(anyString(), eq(COMPANY))).thenReturn(null);

        var response = service.submit(COMPANY, "budi", request("10000000"));

        assertThat(response.refNo()).endsWith("000001");
        verify(transferMapper).insertRefNoSeq(anyString(), eq(COMPANY), eq(1L));
        verify(transferMapper, never()).updateRefNoSeq(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    private void assertRejectedWith(String code, String amount) {
        assertThatThrownBy(() -> service.submit(COMPANY, "budi", request(amount)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo(code));
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void aUserWithoutTheMakerRoleIsRejected() {
        stubHappyPath();
        when(transferMapper.findMaker(COMPANY, "budi")).thenReturn(new MakerRow(
                "CU1", "budi", "BUDI SANTOSO", "GRP1", "AG1", "CRP_USR_AP_RL", "AL01", "SMS_OTP"));
        assertRejectedWith("NOT_MAKER", "10000000");
    }

    @Test
    void anUnknownUserIsRejectedAsNotMaker() {
        stubHappyPath();
        when(transferMapper.findMaker(COMPANY, "budi")).thenReturn(null);
        assertRejectedWith("NOT_MAKER", "10000000");
    }

    @Test
    void aSourceAccountOutsideTheDebitChainIsRejected() {
        stubHappyPath();
        when(transferMapper.countDebitAccount("AG1", SOURCE)).thenReturn(0);
        assertRejectedWith("SOURCE_ACCT_FORBIDDEN", "10000000");
    }

    @Test
    void anAmountOutsideTheBankBandIsRejected() {
        stubHappyPath();
        assertRejectedWith("BANK_LIMIT", "2000000000");
    }

    @Test
    void anAmountBelowTheBankMinimumIsRejected() {
        stubHappyPath();
        assertRejectedWith("BANK_LIMIT", "500");
    }

    @Test
    void anAmountBeyondTheRemainingCompanyLimitIsRejected() {
        stubHappyPath();
        when(transferMapper.findCorpLimit(eq(COMPANY), anyString(), eq("IDR")))
                .thenReturn(new UsageLimitRow(new BigDecimal("1999000000"), new BigDecimal("2000000000")));
        assertRejectedWith("COMPANY_LIMIT", "10000000");
    }

    @Test
    void anAmountBeyondTheRemainingGroupLimitIsRejected() {
        stubHappyPath();
        when(transferMapper.findGroupLimit(eq("GRP1"), anyString(), eq("IDR")))
                .thenReturn(new UsageLimitRow(new BigDecimal("1495000000"), new BigDecimal("1500000000")));
        assertRejectedWith("GROUP_LIMIT", "10000000");
    }

    @Test
    void anAmountOverTheAccountDebitLimitIsRejected() {
        stubHappyPath();
        when(transferMapper.findAccountDebitLimit(COMPANY, SOURCE)).thenReturn(new BigDecimal("5000000"));
        assertRejectedWith("ACCOUNT_LIMIT", "10000000");
    }

    @Test
    void anAmountOverTheMakerSchemeLimitIsRejected() {
        stubHappyPath();
        when(transferMapper.findMakerSchemeLimit(COMPANY, "AL01", "IDR"))
                .thenReturn(new BigDecimal("5000000"));
        assertRejectedWith("MAKER_SCHEME_LIMIT", "10000000");
    }

    @Test
    void aFailedOtpVerificationIsRejectedAndWritesNothing() {
        stubHappyPath();
        when(authenticatorClient.verifyTransaction("budi", "CH-1", "123456"))
                .thenReturn(new Verification(false, null));
        assertRejectedWith("OTP_INVALID", "10000000");
        verify(transferMapper, never()).lockRefNoValue(anyString(), anyString());
    }

    @Test
    void aMissingMatrixIsRejected() {
        stubHappyPath();
        when(transferMapper.findMatrixMasterId(COMPANY, TransferServiceImpl.MENU_CD, "IDR"))
                .thenReturn(null);
        assertRejectedWith("NO_MATRIX", "10000000");
    }

    @Test
    void aMatrixWithNoBandsIsRejected() {
        stubHappyPath();
        when(transferMapper.findMatrixBands("MSTR1")).thenReturn(List.of());
        assertRejectedWith("NO_MATRIX", "10000000");
    }

    // ---- Candidate materialization (the frozen eligible-user set, V2) ----

    @Test
    void sameGroupOptionFreezesOnlySameGroupApproversPlusTheReleasers() {
        stubHappyPath();

        // BAND1: one APPROVAL stage, level AL02, option '2' (same group as maker GRP1).
        var candidates = submittedCandidates("10000000");

        assertThat(candidates).hasSize(2);
        var approver = candidates.get(0);
        assertThat(approver.stageSeq()).isEqualTo(1);
        assertThat(approver.userId()).isEqualTo("ani");
        assertThat(approver.corpUsrId()).isEqualTo("CU2");
        assertThat(approver.userName()).isEqualTo("ANI LESTARI");
        assertThat(approver.corpUsrGrpId()).isEqualTo("GRP1");
        var releaser = candidates.get(1);
        assertThat(releaser.stageSeq()).isEqualTo(2);
        assertThat(releaser.userId()).isEqualTo("eka");
        assertThat(releaser.corpUsrId()).isEqualTo("CU5");
    }

    @Test
    void anyAndSpecificGroupOptionsMaterializePerStage() {
        stubHappyPath();

        // BAND2: stage 1 AL02 option '1' (any group), stage 2 AL03 option '4'
        // (TARGET_GRP), then the release stage.
        var candidates = submittedCandidates("600000000");

        assertThat(candidates).hasSize(4);
        assertThat(candidates.stream().filter(c -> c.stageSeq() == 1)
                .map(TrxTaskRows.CandidateInsert::userId))
                .containsExactly("ani", "cici");
        assertThat(candidates.stream().filter(c -> c.stageSeq() == 2)
                .map(TrxTaskRows.CandidateInsert::userId))
                .containsExactly("dodi");
        assertThat(candidates.stream().filter(c -> c.stageSeq() == 3)
                .map(TrxTaskRows.CandidateInsert::userId))
                .containsExactly("eka");
    }

    @Test
    void differentGroupOptionExcludesTheMakersGroup() {
        stubHappyPath();
        when(transferMapper.findBandSignatures("BAND1")).thenReturn(List.of(
                new MatrixSignatureRow(1, 1, "AL02", "3", null)));

        var candidates = submittedCandidates("10000000");

        // AL02 approvers are ani (GRP1, the maker's group) and cici (GRP2): only cici.
        assertThat(candidates.stream().filter(c -> c.stageSeq() == 1)
                .map(TrxTaskRows.CandidateInsert::userId))
                .containsExactly("cici");
    }

    @Test
    void aNullStageLevelAcceptsApproversOfAnyLevel() {
        stubHappyPath();
        when(transferMapper.findBandSignatures("BAND1")).thenReturn(List.of(
                new MatrixSignatureRow(1, 1, null, "1", null)));

        var candidates = submittedCandidates("10000000");

        assertThat(candidates.stream().filter(c -> c.stageSeq() == 1)
                .map(TrxTaskRows.CandidateInsert::userId))
                .containsExactly("ani", "cici", "dodi");
    }

    @Test
    void theMakerIsExcludedFromApprovalButNotFromReleaseCandidates() {
        stubHappyPath();
        // The maker now ALSO holds approve+release, at the stage's own level and in a
        // matching group - and must still not approve their own task.
        when(transferMapper.findWorkflowUsers(COMPANY)).thenReturn(List.of(
                user("CU1", "budi", "BUDI SANTOSO", "GRP1", "CRP_USR_MK_AP_RL", "AL02"),
                user("CU2", "ani", "ANI LESTARI", "GRP1", "CRP_USR_AP", "AL02"),
                user("CU5", "eka", "EKA PUTRI", "GRP1", "CRP_USR_RL", null)));

        var candidates = submittedCandidates("10000000");

        assertThat(candidates.stream().filter(c -> c.stageSeq() == 1)
                .map(TrxTaskRows.CandidateInsert::userId))
                .containsExactly("ani");
        assertThat(candidates.stream().filter(c -> c.stageSeq() == 2)
                .map(TrxTaskRows.CandidateInsert::userId))
                .containsExactly("budi", "eka");
    }

    @Test
    void aStageNoOneCanApproveRejectsTheSubmitBeforeTheCounter() {
        stubHappyPath();
        when(transferMapper.findBandSignatures("BAND1")).thenReturn(List.of(
                new MatrixSignatureRow(1, 1, "AL99", "1", null)));

        assertRejectedWith("NO_ELIGIBLE_APPROVER", "10000000");
        verify(transferMapper, never()).lockRefNoValue(anyString(), anyString());
        verify(trxTaskMapper, never()).insertCandidate(any());
    }

    @Test
    void aCompanyWithNoReleaserRejectsTheSubmit() {
        stubHappyPath();
        when(transferMapper.findWorkflowUsers(COMPANY)).thenReturn(List.of(
                user("CU1", "budi", "BUDI SANTOSO", "GRP1", "CRP_USR_MK", "AL01"),
                user("CU2", "ani", "ANI LESTARI", "GRP1", "CRP_USR_AP", "AL02")));

        assertRejectedWith("NO_ELIGIBLE_RELEASER", "10000000");
        verify(transferMapper, never()).lockRefNoValue(anyString(), anyString());
    }

    // ---- Detail with the action history ----

    @Test
    void detailGroupsActionsUnderTheirStagesAndNamesTheMenu() {
        var created = java.time.LocalDateTime.of(2026, 8, 31, 10, 0);
        when(trxTaskMapper.findTask(COMPANY, "T1")).thenReturn(new TrxTaskRows.TaskRow(
                "T1", "20260831100000228541", TransferServiceImpl.MENU_CD, "PENDING_RELEASE",
                2, new BigDecimal("10000000"), "IDR", SOURCE, BENEFICIARY, "PT MAJU JAYA",
                "pembayaran vendor", "BUDI SANTOSO", created, 1L,
                "907409", "20260831110000000042", created.plusHours(2)));
        when(trxTaskMapper.findStages("T1")).thenReturn(List.of(
                new TrxTaskRows.StageRow(1, "APPROVAL", "AL02", "2", 1, 1, "DONE"),
                new TrxTaskRows.StageRow(2, "RELEASE", null, "1", 1, 0, "ACTIVE")));
        when(trxTaskMapper.findActions("T1")).thenReturn(List.of(
                new TrxTaskRows.ActionRow(null, "SUBMIT", "BUDI SANTOSO", null, created),
                new TrxTaskRows.ActionRow(1, "APPROVE", "ANI LESTARI", "ok", created.plusHours(1))));

        var detail = service.detail(COMPANY, "T1", "ani");

        assertThat(detail.menuName()).isEqualTo("Transfer ke BNI");
        assertThat(detail.makerName()).isEqualTo("BUDI SANTOSO");
        // The execution phase's additive fields ride through unchanged.
        assertThat(detail.coreJournal()).isEqualTo("907409");
        assertThat(detail.trxRefNo()).isEqualTo("20260831110000000042");
        assertThat(detail.executedAt()).isEqualTo(created.plusHours(2));
        assertThat(detail.stages()).hasSize(2);
        assertThat(detail.stages().get(0).actions()).hasSize(1);
        assertThat(detail.stages().get(0).actions().get(0).actorName()).isEqualTo("ANI LESTARI");
        assertThat(detail.stages().get(0).actions().get(0).action()).isEqualTo("APPROVE");
        // The SUBMIT action belongs to no stage and must not leak into one.
        assertThat(detail.stages().get(1).actions()).isEmpty();
    }
}
