package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.util.List;

import id.co.bni.direct.transaction.dto.request.TransferRequests.MoneyRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.SubmitTransferRequest;
import id.co.bni.direct.transaction.config.TransferTypeProperties;
import id.co.bni.direct.transaction.entity.TransferRows.BankLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.CorpFlagsRow;
import id.co.bni.direct.transaction.entity.TransferRows.BiFastPurposeRow;
import id.co.bni.direct.transaction.entity.TransferRows.DomBankRow;
import id.co.bni.direct.transaction.entity.TransferRows.MakerRow;
import id.co.bni.direct.transaction.entity.TransferRows.MatrixBandRow;
import id.co.bni.direct.transaction.entity.TransferRows.MatrixSignatureRow;
import id.co.bni.direct.transaction.entity.TransferRows.UsageLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.WorkflowUserRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.ServiceUnavailableException;
import id.co.bni.direct.transaction.dto.request.TransferRequests.InterbankInquiryRequest;
import id.co.bni.direct.transaction.integration.AccountNameClient;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient.Verification;
import id.co.bni.direct.transaction.repository.mapper.ChargeMapper;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.entity.EventOutboxRows;
import com.fasterxml.jackson.databind.JsonNode;
import id.co.bni.direct.transaction.repository.mapper.ExecutionOutboxMapper;
import id.co.bni.direct.transaction.service.impl.ExecutionOutbox;
import id.co.bni.direct.transaction.service.impl.NotificationOutbox;
import id.co.bni.direct.transaction.service.impl.TransferServiceImpl;
import id.co.bni.direct.transaction.service.TransferType;
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
import id.co.bni.direct.transaction.dto.request.TransferRequests.BiFastCreditorRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.BiFastInquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.VaInquiryRequest;
import id.co.bni.direct.transaction.exception.NotFoundException;
import id.co.bni.direct.transaction.dto.request.TransferRequests.VaBillRequest;
import id.co.bni.direct.transaction.service.VaBill;

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
    private ExecutionOutboxMapper outboxMapper;
    private NotificationOutbox notificationOutbox;
    private CoreTransferClient coreTransferClient;
    private TransferServiceImpl service;

    private ChargeMapper chargeMapper;
    private ChargeService chargeService;
    private LimitService limitService;

    /**
     * The charge engine, stubbed to the tariffs DEV actually carries for these services,
     * so the assertions written against the old flat constants keep asserting the same
     * numbers - what changed is WHERE the number comes from, not what it is.
     */
    private static ChargeService stubChargeService() {
        ChargeService stub = mock(ChargeService.class);
        when(stub.quote(anyString(), anyString(), any())).thenAnswer(inv -> {
            String srvcCd = inv.getArgument(1);
            BigDecimal fee = switch (srvcCd) {
                case TransferType.SRVC_DOM_LLG -> new BigDecimal("2900");
                case TransferType.SRVC_DOM_RTGS -> new BigDecimal("30000");
                case TransferType.SRVC_DOM_ONLINE -> new BigDecimal("6500");
                case TransferType.SRVC_DOM_BIFAST -> new BigDecimal("2500");
                default -> BigDecimal.ZERO;
            };
            if (fee.signum() == 0) {
                return ChargeService.ChargeQuote.none();
            }
            return new ChargeService.ChargeQuote(List.of(new ChargeService.ChargeComponent(
                    1, "014", "Transfer Fee", "IDR", fee, fee, null, null, null, "COMPANY")), fee);
        });
        return stub;
    }

    @BeforeEach
    void setUp() {
        transferMapper = mock(TransferMapper.class);
        trxTaskMapper = mock(TrxTaskMapper.class);
        accountNameClient = mock(AccountNameClient.class);
        authenticatorClient = mock(UmasAuthenticatorClient.class);
        executionService = mock(ExecutionService.class);
        // Mockito default: isKafkaMode() answers false = sync mode, today's behavior.
        executionOutbox = mock(ExecutionOutbox.class);
        outboxMapper = mock(ExecutionOutboxMapper.class);
        // The REAL NotificationOutbox: the TASK_SUBMITTED row it writes must be produced
        // from inside the submit transaction, over the candidate rows that transaction
        // has just inserted - a mock would hide both.
        notificationOutbox = new NotificationOutbox(outboxMapper, trxTaskMapper, new ObjectMapper());
        coreTransferClient = mock(CoreTransferClient.class);
        chargeMapper = mock(ChargeMapper.class);
        chargeService = stubChargeService();
        // Reserving nothing keeps every existing limit assertion about the OTHER ceilings
        // (bank, account debit, maker scheme) exactly as it was.
        limitService = mock(LimitService.class);
        when(limitService.reserve(anyString(), any(), anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(LimitService.Reservation.none());
        service = new TransferServiceImpl(transferMapper, trxTaskMapper,
                chargeMapper, chargeService, limitService,
                accountNameClient, coreTransferClient, authenticatorClient,
                executionService, executionOutbox, notificationOutbox, new TransferTypeProperties(),
                mock(PlatformTransactionManager.class));
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

    // The new matrix guards are for MULTI-USER companies only. A cash-lite single-user
    // company never reads the matrix, so a band with no signatures and a signature with
    // no approval level must both leave its fast path completely untouched.
    @Test
    void aSingleUserCompanyStillSubmitsWithAnUnconfiguredApprovalMatrix() {
        stubHappyPath();
        when(transferMapper.findCorpFlags(COMPANY)).thenReturn(new CorpFlagsRow("Y", "Y"));
        when(transferMapper.findBandSignatures("BAND1")).thenReturn(List.of());
        when(executionService.execute(anyString()))
                .thenReturn(new ExecutionService.ExecutionResult("EXECUTED", null));

        var response = service.submit(COMPANY, "budi", request("10000000"));

        assertThat(response.status()).isEqualTo("EXECUTED");
        verify(transferMapper, never()).findBandSignatures(anyString());
        verify(trxTaskMapper, never()).insertStage(any());
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().isSingleUser()).isEqualTo("Y");
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


    /**
     * The daily ceilings moved behind LimitService, which now CONSUMES them at submit
     * instead of merely reading them. These tests therefore stopped being about
     * CORP_LMT_PC_DTL rows and became about the contract: what the pipeline asks the
     * service to reserve, and that a refusal from it stops the submit.
     */
    private void stubReservationRefusedWith(String code) {
        when(limitService.reserve(anyString(), any(), anyString(), any(), anyString(), any(), anyString()))
                .thenThrow(new BusinessRuleException(code, "ditolak limit"));
    }

    /** The debited total the pipeline handed to the ceiling check. */
    private BigDecimal capturedReservedAmount() {
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(limitService).reserve(anyString(), any(), anyString(), any(), anyString(),
                amount.capture(), anyString());
        return amount.getValue();
    }

    @Test
    void aCompanyCeilingRefusalStopsTheSubmit() {
        stubHappyPath();
        stubReservationRefusedWith("LIMIT_AMOUNT_EXCEEDED");
        assertRejectedWith("LIMIT_AMOUNT_EXCEEDED", "10000000");
    }

    @Test
    void aDailyCountCeilingRefusalStopsTheSubmitToo() {
        stubHappyPath();
        stubReservationRefusedWith("LIMIT_COUNT_EXCEEDED");
        assertRejectedWith("LIMIT_COUNT_EXCEEDED", "10000000");
    }

    @Test
    void theReservationSeesTheDebitedTotalAndTheLadderCurrency() {
        stubHappyPath();

        service.submit(COMPANY, "budi", request("10000000"));

        // In-house: no charge in this stub, so the reserved figure is the amount itself.
        assertThat(capturedReservedAmount()).isEqualByComparingTo("10000000");
        verify(limitService).reserve(eq(COMPANY), eq("GRP1"), anyString(), any(), eq("IDR"),
                any(), anyString());
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

    // A null aprvLvlCd is what findBandSignatures' LEFT JOIN returns when the approval
    // limit scheme was never created. This used to be read as "any level" - every AP-role
    // user became a candidate and the stage was written with a null APRV_LVL_CD. For a
    // multi-user company that is an undetermined workflow, so the submit is refused.
    @Test
    void aNullStageLevelIsRejectedBecauseTheApprovalSchemeIsUnconfigured() {
        stubHappyPath();
        when(transferMapper.findBandSignatures("BAND1")).thenReturn(List.of(
                new MatrixSignatureRow(1, 1, null, "1", null)));

        assertRejectedWith("NO_APRV_LVL_SCHEME", "10000000");
        verify(transferMapper, never()).lockRefNoValue(anyString(), anyString());
        verify(trxTaskMapper, never()).insertStage(any());
        verify(trxTaskMapper, never()).insertCandidate(any());
    }

    // A band with no signature rows used to fall through to PENDING_RELEASE, skipping
    // approval entirely for a multi-user company.
    @Test
    void aBandWithNoSignaturesIsRejectedInsteadOfSkippingApproval() {
        stubHappyPath();
        when(transferMapper.findBandSignatures("BAND1")).thenReturn(List.of());

        assertRejectedWith("NO_MATRIX_SIGNATURE", "10000000");
        verify(transferMapper, never()).lockRefNoValue(anyString(), anyString());
        verify(trxTaskMapper, never()).insertStage(any());
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
                "T1", "20260831100000228541", TransferServiceImpl.MENU_CD,
                TransferServiceImpl.SRVC_IN_HOUSE_3RD, "PENDING_RELEASE",
                2, new BigDecimal("10000000"), "IDR", SOURCE, BENEFICIARY, "PT MAJU JAYA",
                "pembayaran vendor", "BUDI SANTOSO", created, 1L,
                "907409", "20260831110000000042", created.plusHours(2),
                null, null, null, null, null, null, null));
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

    // ---- P1: LLG/RTGS submit (type routing, bank shape, fee-inclusive ladder) ----

    /** A domestic submit: the full-argument wire shape with the P1 fields set. */
    private static SubmitTransferRequest domesticRequest(String type, String amount, String postal) {
        return new SubmitTransferRequest("budi", SOURCE, "3049530495", "YOSUA PRISKWILA",
                new MoneyRequest(new BigDecimal(amount), "IDR"), "bayar vendor",
                new OtpRequest("CH-1", "123456"),
                type, "DB1", "Jl. Melati 1", null, null, "0811", postal,
                null, null, null, null, null);
    }

    private void stubDomestic() {
        stubHappyPath();
        when(transferMapper.findDomBank("DB1"))
                .thenReturn(new DomBankRow("DB1", "0140397", "BANK BCA", "CENAIDJA", "014"));
        when(transferMapper.findMatrixMasterId(COMPANY,
                TransferServiceImpl.MENU_CD_BANK_LAIN, "IDR")).thenReturn("MSTR1");
    }

    @Test
    void llgSubmitResolvesTheDomesticServiceMenuAndFee() {
        stubDomestic();

        var response = service.submit(COMPANY, "budi", domesticRequest("LLG", "10000000", null));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().menuCd()).isEqualTo(TransferServiceImpl.MENU_CD_BANK_LAIN);
        assertThat(task.getValue().srvcCd()).isEqualTo(TransferType.SRVC_DOM_LLG);
        var dom = task.getValue().domestic();
        assertThat(dom.benDomBnkId()).isEqualTo("DB1");
        assertThat(dom.benBnkCd()).isEqualTo("0140397");
        assertThat(dom.benBnkBic()).isEqualTo("CENAIDJA");
        assertThat(dom.benType()).isEqualTo("1");
        assertThat(dom.lldIsRemRes()).isEqualTo("1");
        assertThat(dom.lldIsBenRes()).isEqualTo("1");
        assertThat(dom.feeAmt()).isEqualByComparingTo("2900");
        // The ladder keys on the DOM service code (task 1.5), and the beneficiary
        // own/3rd probe never runs for a domestic transfer.
        verify(transferMapper).findBankLimit(TransferType.SRVC_DOM_LLG, "IDR");
        verify(transferMapper, never()).countAnyAccount(anyString(), anyString());
    }

    @Test
    void rtgsSubmitUsesTheBicAndItsOwnResidencyDefaults() {
        stubDomestic();

        service.submit(COMPANY, "budi", domesticRequest("RTGS", "10000000", "12345"));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().srvcCd()).isEqualTo(TransferType.SRVC_DOM_RTGS);
        var dom = task.getValue().domestic();
        // RTGS routes by BIC, carries no beneficiary type, and speaks its own residency
        // vocabulary (0 = Resident).
        assertThat(dom.benBnkCd()).isEqualTo("CENAIDJA");
        assertThat(dom.benType()).isNull();
        assertThat(dom.lldIsRemRes()).isEqualTo("0");
        assertThat(dom.lldIsBenRes()).isEqualTo("0");
        assertThat(dom.feeAmt()).isEqualByComparingTo("30000");
        verify(transferMapper).findBankLimit(TransferType.SRVC_DOM_RTGS, "IDR");
    }

    @Test
    void theDomesticLadderValidatesAmountPlusFee() {
        stubDomestic();

        service.submit(COMPANY, "budi", domesticRequest("LLG", "10000000", null));

        // What the ceiling measures is the DEBITED total: the amount plus the 2,900 charge.
        assertThat(capturedReservedAmount()).isEqualByComparingTo("10002900");
    }

    @Test
    void llgRejectsABankRowWithoutAClearingCode() {
        stubDomestic();
        // A BIC-keyed COM_MT_DOM_BANK row (no 7-digit sandi) cannot be routed via LLG.
        when(transferMapper.findDomBank("DB1"))
                .thenReturn(new DomBankRow("DB1", "CENAIDJA", "BANK BCA", "CENAIDJA", null));

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                domesticRequest("LLG", "10000000", null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("BENEFICIARY_BANK_INVALID"));
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void rtgsWithoutAPostalCodeIsRejected() {
        stubDomestic();

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                domesticRequest("RTGS", "10000000", null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"));
    }

    @Test
    void anUnknownTransferTypeIsRejected() {
        stubHappyPath();

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                domesticRequest("SWIFT", "10000000", null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"));
    }

    @Test
    void theBankPickerAnswersRoutableCodesPerMethod() {
        when(transferMapper.findClearingBanks()).thenReturn(List.of(
                new DomBankRow("DB1", "0140397", "BANK BCA", "CENAIDJAXXX", "014")));
        when(transferMapper.findRtgsBanks()).thenReturn(List.of(
                new DomBankRow("DB2", "PINBIDJA", "BANK PANIN", null, null)));

        var llg = service.banks(COMPANY, "LLG");
        assertThat(llg).hasSize(1);
        assertThat(llg.get(0).code()).isEqualTo("0140397");
        // An 11-char branch-qualified BIC is trimmed to the 8 chars kliring mandates.
        assertThat(llg.get(0).bic()).isEqualTo("CENAIDJA");

        var rtgs = service.banks(COMPANY, "RTGS");
        assertThat(rtgs).hasSize(1);
        assertThat(rtgs.get(0).code()).isEqualTo("PINBIDJA");
        assertThat(rtgs.get(0).bic()).isEqualTo("PINBIDJA");
    }

    @Test
    void methodInfoAnswersTheDevSysParamValues() {
        // The two real DEV SYS_PARAM rows, verbatim.
        when(transferMapper.findSysParamValue("SYS_PARAM_TRF_SME_LLG"))
                .thenReturn("2 - 3 hari|IDR 1|IDR 10,000,000,000,00|IDR 2,900");
        when(transferMapper.findSysParamValue("SYS_PARAM_TRF_SME_RTGS"))
                .thenReturn("3 - 4 jam|IDR 1,000,000,000|IDR 10,000,000,000,00|IDR 30,000");

        var llg = service.methodInfo(COMPANY, "LLG");
        assertThat(llg.method()).isEqualTo("LLG");
        assertThat(llg.currency()).isEqualTo("IDR");
        assertThat(llg.minAmount()).isEqualByComparingTo("1");
        assertThat(llg.maxAmount()).isEqualByComparingTo("10000000000");
        assertThat(llg.fee()).isEqualByComparingTo("2900");
        assertThat(llg.estimatedDuration()).isEqualTo("2 - 3 hari");

        var rtgs = service.methodInfo(COMPANY, "RTGS");
        assertThat(rtgs.method()).isEqualTo("RTGS");
        assertThat(rtgs.currency()).isEqualTo("IDR");
        assertThat(rtgs.minAmount()).isEqualByComparingTo("1000000000");
        assertThat(rtgs.maxAmount()).isEqualByComparingTo("10000000000");
        assertThat(rtgs.fee()).isEqualByComparingTo("30000");
        assertThat(rtgs.estimatedDuration()).isEqualTo("3 - 4 jam");
    }

    @Test
    void methodInfoFallsBackToTheConfiguredFeeWhenTheParamIsMissing() {
        // The mapper answers null (row missing or IS_DELETE='Y'): everything is null
        // except the fee, which falls back to app.transfer.*.fee so the FE always has one.
        var rtgs = service.methodInfo(COMPANY, "RTGS");
        assertThat(rtgs.minAmount()).isNull();
        assertThat(rtgs.maxAmount()).isNull();
        assertThat(rtgs.estimatedDuration()).isNull();
        assertThat(rtgs.fee()).isEqualByComparingTo("30000");

        var llg = service.methodInfo(COMPANY, "LLG");
        assertThat(llg.fee()).isEqualByComparingTo("2900");

        var online = service.methodInfo(COMPANY, "ONLINE");
        assertThat(online.fee()).isEqualByComparingTo("6500");
    }

    @Test
    void methodInfoRejectsANonDomesticMethod() {
        for (String bad : new String[]{"BNI", "SWIFT"}) {
            assertThatThrownBy(() -> service.methodInfo(COMPANY, bad))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                            .isEqualTo("TRANSFER_FIELDS_INVALID"));
        }
    }

    // ---- P2: ONLINE (RTOL / ATM Bersama) ----

    @Test
    void onlineSubmitResolvesTheOnlineServiceAndIgnoresTheAddressBlock() {
        stubDomestic();

        var response = service.submit(COMPANY, "budi", domesticRequest("ONLINE", "10000000", "12345"));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().menuCd()).isEqualTo(TransferServiceImpl.MENU_CD_BANK_LAIN);
        assertThat(task.getValue().srvcCd()).isEqualTo(TransferType.SRVC_DOM_ONLINE);
        var dom = task.getValue().domestic();
        assertThat(dom.benDomBnkId()).isEqualTo("DB1");
        // The bank code is the 3-digit interbank/ATM-Bersama code, never sandi or BIC.
        assertThat(dom.benBnkCd()).isEqualTo("014");
        assertThat(dom.benBnkNm()).isEqualTo("BANK BCA");
        assertThat(dom.benBnkBic()).isNull();
        // The interbank wire carries no address/postal/residency/type fields: values
        // the FE sent are accepted and ignored, not frozen onto the task.
        assertThat(dom.benAddr1()).isNull();
        assertThat(dom.benPostalCd()).isNull();
        assertThat(dom.benType()).isNull();
        assertThat(dom.lldIsRemRes()).isNull();
        assertThat(dom.lldIsBenRes()).isNull();
        assertThat(dom.feeAmt()).isEqualByComparingTo("6500");
        // The ladder keys on the ONLINE service code; no own/3rd probe for domestic.
        verify(transferMapper).findBankLimit(TransferType.SRVC_DOM_ONLINE, "IDR");
        verify(transferMapper, never()).countAnyAccount(anyString(), anyString());
    }

    @Test
    void onlineRejectsABankRowWithoutAnOnlineCode() {
        stubDomestic();
        when(transferMapper.findDomBank("DB1"))
                .thenReturn(new DomBankRow("DB1", "0140397", "BANK BCA", "CENAIDJA", null));

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                domesticRequest("ONLINE", "10000000", null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("BENEFICIARY_BANK_INVALID"));
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void theOnlineLadderValidatesAmountPlusFee() {
        stubDomestic();

        service.submit(COMPANY, "budi", domesticRequest("ONLINE", "10000000", null));

        // The ceiling measures the DEBITED total: amount plus the charge.
        assertThat(capturedReservedAmount()).isEqualByComparingTo("10006500");
    }

    @Test
    void theOnlineBankPickerAnswersTheInterbankCode() {
        when(transferMapper.findOnlineBanks()).thenReturn(List.of(
                new DomBankRow("DB3", "0140397", "BANK BCA", "CENAIDJA2", "014")));

        var online = service.banks(COMPANY, "ONLINE");

        assertThat(online).hasSize(1);
        assertThat(online.get(0).id()).isEqualTo("DB3");
        assertThat(online.get(0).code()).isEqualTo("014");
        assertThat(online.get(0).bic()).isNull();
    }

    @Test
    void methodInfoParsesTheDevOnlineRowWithItsProseToken() {
        when(transferMapper.findSysParamValue("SYS_PARAM_TRF_SME_ONLINE"))
                .thenReturn("Real Time|IDR 20,000|IDR 1000,000,000 per transaksi, sehari 1 M|IDR 6,500");

        var online = service.methodInfo(COMPANY, "ONLINE");

        assertThat(online.method()).isEqualTo("ONLINE");
        assertThat(online.currency()).isEqualTo("IDR");
        assertThat(online.minAmount()).isEqualByComparingTo("20000");
        assertThat(online.maxAmount()).isEqualByComparingTo("1000000000");
        assertThat(online.fee()).isEqualByComparingTo("6500");
        assertThat(online.estimatedDuration()).isEqualTo("Real Time");
    }

    @Test
    void interbankInquiryRoutesTheOnlineCodeAndAnswersTheBeneficiary() {
        when(transferMapper.findDomBank("DB1"))
                .thenReturn(new DomBankRow("DB1", "0140397", "BANK BCA", "CENAIDJA", "014"));
        when(coreTransferClient.inquireInterbank(any())).thenReturn(
                new CoreTransferClient.InterbankInquiry("YOSUA PRISKWILA", "BANK CENTRAL ASIA",
                        "3049530495", "RRN0001", "PT DEMO"));

        var response = service.interbankInquiry(COMPANY, new InterbankInquiryRequest(
                "budi", SOURCE, "3049530495", "DB1", new BigDecimal("250000")));

        assertThat(response.beneficiaryName()).isEqualTo("YOSUA PRISKWILA");
        assertThat(response.beneficiaryBankName()).isEqualTo("BANK CENTRAL ASIA");
        assertThat(response.retrievalRefNo()).isEqualTo("RRN0001");
        ArgumentCaptor<CoreTransferClient.InterbankInstruction> instruction =
                ArgumentCaptor.forClass(CoreTransferClient.InterbankInstruction.class);
        verify(coreTransferClient).inquireInterbank(instruction.capture());
        assertThat(instruction.getValue().fromAccount()).isEqualTo(SOURCE);
        assertThat(instruction.getValue().beneficiaryAccount()).isEqualTo("3049530495");
        assertThat(instruction.getValue().beneficiaryBankCode()).isEqualTo("014");
        assertThat(instruction.getValue().amount()).isEqualTo("250000.00");
        // References ride in the TRX_REF_NO shape but never touch the shared counter.
        assertThat(instruction.getValue().refNo()).matches("\\d{20}");
        assertThat(instruction.getValue().customerRefNo()).matches("\\d{20}");
        verify(transferMapper, never()).lockRefNoValue(anyString(), anyString());
    }

    @Test
    void interbankInquiryRejectsABankThatCannotBeRoutedOnline() {
        when(transferMapper.findDomBank("DB1"))
                .thenReturn(new DomBankRow("DB1", "0140397", "BANK BCA", "CENAIDJA", null));

        assertThatThrownBy(() -> service.interbankInquiry(COMPANY, new InterbankInquiryRequest(
                "budi", SOURCE, "3049530495", "DB1", new BigDecimal("250000"))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("BENEFICIARY_BANK_INVALID"));
    }

    // ---- P5: multi-currency (cross, loan source, trxPBI gate, decimal rules) ----

    /** A P5 multi-currency submit: the BNI wire plus the additive currency fields. */
    private static SubmitTransferRequest crossRequest(String amount, String creditCcy,
                                                      String debitAmount, String rateType,
                                                      String docType, String docNumber) {
        return new SubmitTransferRequest("budi", SOURCE, BENEFICIARY, "PT MAJU JAYA",
                new MoneyRequest(new BigDecimal(amount), creditCcy), "pembayaran vendor",
                new OtpRequest("CH-1", "123456"),
                null, null, null, null, null, null, null, null, null, null, null, null,
                creditCcy, debitAmount != null ? new BigDecimal(debitAmount) : null,
                rateType, docType, docNumber, null, null, null);
    }

    private void stubShortDetails(String ccy, String productType) {
        when(accountNameClient.fetchShortDetails(SOURCE)).thenReturn(
                new AccountNameClient.ShortDetails(SOURCE, "PT DEMO", ccy, "BUKA", productType));
    }

    @Test
    void usdToIdrTakesBaseAmountFromTheIdrCreditLegWithoutARateCall() {
        stubHappyPath();
        stubShortDetails("USD", "DEP");

        var response = service.submit(COMPANY, "budi",
                crossRequest("165000000", "IDR", "10000", null, null, null));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        var cross = task.getValue().cross();
        assertThat(cross.debitCcyCd()).isEqualTo("USD");
        assertThat(cross.debitAmt()).isEqualByComparingTo("10000");
        assertThat(cross.baseAmt()).isEqualByComparingTo("165000000");
        assertThat(cross.exchangeRate()).isNull();
        assertThat(cross.rateType()).isEqualTo("02");
        assertThat(cross.sourceProductType()).isEqualTo("DEP");
        // The ladder guards the DEBIT side, in the debit currency; no rate, no gate.
        verify(transferMapper).findBankLimit(TransferServiceImpl.SRVC_IN_HOUSE_3RD, "USD");
        verify(coreTransferClient, never()).fetchRates(anyString());
        verify(coreTransferClient, never()).checkUnderlying(any());
    }

    @Test
    void idrDebitLegMakesTheDebitAmountTheBaseAmount() {
        stubHappyPath();
        stubShortDetails("IDR", "DEP");
        when(transferMapper.findCorpHostCif(COMPANY)).thenReturn("9000055012");
        when(coreTransferClient.checkUnderlying(any())).thenReturn(
                new CoreTransferClient.UnderlyingCheck(null, false, false));

        service.submit(COMPANY, "budi",
                crossRequest("10000", "USD", "165000000", null, null, null));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        var cross = task.getValue().cross();
        assertThat(cross.debitCcyCd()).isEqualTo("IDR");
        assertThat(cross.baseAmt()).isEqualByComparingTo("165000000");
        assertThat(cross.exchangeRate()).isNull();
        verify(coreTransferClient, never()).fetchRates(anyString());
    }

    @Test
    void valasToValasBuysTheDebitCurrencysRateAndRoundsBaseAmountToIdr() {
        stubHappyPath();
        stubShortDetails("USD", "DEP");
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of(
                new CoreTransferClient.Rate("USD", "IDR", "01", null, "16500.55", "16600")));

        service.submit(COMPANY, "budi",
                crossRequest("2000", "SGD", "123.45", null, null, null));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        var cross = task.getValue().cross();
        assertThat(cross.exchangeRate()).isEqualByComparingTo("16500.55");
        // 123.45 x 16500.55 = 2 036 992.899 75 -> IDR is a whole number: 2 036 993.
        assertThat(cross.baseAmt()).isEqualByComparingTo("2036993");
        verify(coreTransferClient, never()).checkUnderlying(any());
    }

    @Test
    void aPerHundredQuotedRateIsScaledByItsUnits() {
        stubHappyPath();
        stubShortDetails("JPY", "DEP");
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of(
                new CoreTransferClient.Rate("JPY", "IDR", "100", null, "10940", "11000")));

        service.submit(COMPANY, "budi",
                crossRequest("50", "USD", "10000", null, null, null));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().cross().exchangeRate()).isEqualByComparingTo("109.4");
        assertThat(task.getValue().cross().baseAmt()).isEqualByComparingTo("1094000");
    }

    private void stubGateCorridor() {
        stubHappyPath();
        stubShortDetails("IDR", "DEP");
        when(transferMapper.findCorpHostCif(COMPANY)).thenReturn("9000055012");
    }

    @Test
    void idrToValasWithUnderlyingRequiredAndNoDocumentIs422UnderlyingRequired() {
        stubGateCorridor();
        when(coreTransferClient.checkUnderlying(any())).thenReturn(
                new CoreTransferClient.UnderlyingCheck(
                        "Nasabah WAJIB menyerahkan dokumen underlying.", false, true));

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                crossRequest("10000", "USD", "165000000", null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> {
                    assertThat(((BusinessRuleException) e).code()).isEqualTo("UNDERLYING_REQUIRED");
                    assertThat(e.getMessage()).contains("WAJIB menyerahkan dokumen");
                });
        verify(trxTaskMapper, never()).insertTask(any());

        // The check instruction speaks the corridor: the customer sells IDR, buys USD.
        ArgumentCaptor<CoreTransferClient.UnderlyingCheckInstruction> check =
                ArgumentCaptor.forClass(CoreTransferClient.UnderlyingCheckInstruction.class);
        verify(coreTransferClient).checkUnderlying(check.capture());
        assertThat(check.getValue().cif()).isEqualTo("9000055012");
        assertThat(check.getValue().sellCurrency()).isEqualTo("IDR");
        assertThat(check.getValue().buyCurrency()).isEqualTo("USD");
        assertThat(check.getValue().amount()).isEqualTo("10000.00");
    }

    @Test
    void underlyingRequiredWithADeclaredDocumentPassesAndFreezesTheDocument() {
        stubGateCorridor();
        when(coreTransferClient.checkUnderlying(any())).thenReturn(
                new CoreTransferClient.UnderlyingCheck("wajib underlying", false, true));

        var response = service.submit(COMPANY, "budi",
                crossRequest("10000", "USD", "165000000", null, "INV", "INV-2026-001"));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().cross().undDocType()).isEqualTo("INV");
        assertThat(task.getValue().cross().undDocNo()).isEqualTo("INV-2026-001");
    }

    @Test
    void statementRequiredNeverBlocksAndRidesAsTheAdvisory() {
        stubGateCorridor();
        when(coreTransferClient.checkUnderlying(any())).thenReturn(
                new CoreTransferClient.UnderlyingCheck(
                        "Nasabah WAJIB melengkapi surat pernyataan (eqv USD 10.000)", true, false));

        var response = service.submit(COMPANY, "budi",
                crossRequest("10000", "USD", "165000000", null, null, null));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.advisoryMessage()).contains("surat pernyataan");
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().cross().advisoryMsg()).contains("surat pernyataan");
    }

    @Test
    void anUnreachableUnderlyingCheckFailsClosedWithA503() {
        stubGateCorridor();
        when(coreTransferClient.checkUnderlying(any())).thenThrow(
                new ServiceUnavailableException(
                        "Pemeriksaan underlying Bank Indonesia sedang tidak tersedia."));

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                crossRequest("10000", "USD", "165000000", null, null, null)))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void anIdrAmountWithDecimalsIsRejectedBeforeAnyProbe() {
        stubHappyPath();

        assertThatThrownBy(() -> service.submit(COMPANY, "budi", request("10000000.55")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"));
        verify(accountNameClient, never()).fetchShortDetails(anyString());
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void aDebitAmountBreakingTheDebitCurrencysDecimalRuleIsRejected() {
        stubHappyPath();
        stubShortDetails("JPY", "DEP");

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                crossRequest("50", "USD", "10000.5", null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"));
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void aCrossSubmitWithoutTheDebitAmountIsRejected() {
        stubHappyPath();
        stubShortDetails("USD", "DEP");

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                crossRequest("165000000", "IDR", null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"));
    }

    @Test
    void aMissingRateForTheDebitCurrencyIs422RateUnavailable() {
        stubHappyPath();
        stubShortDetails("USD", "DEP");
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of());

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                crossRequest("2000", "SGD", "123.45", null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("RATE_UNAVAILABLE"));
    }

    @Test
    void aLoanSourceSameCurrencySubmitFreezesTheProductTypeAlone() {
        stubHappyPath();
        stubShortDetails("IDR", "LON");

        service.submit(COMPANY, "budi", crossRequest("10000000", "IDR", null, null, null, null));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        var cross = task.getValue().cross();
        assertThat(cross.sourceProductType()).isEqualTo("LON");
        assertThat(cross.debitCcyCd()).isNull();
        assertThat(cross.baseAmt()).isNull();
        verify(coreTransferClient, never()).checkUnderlying(any());
    }

    @Test
    void theP0WireShapeNeverProbesTheSourceAccount() {
        stubHappyPath();

        service.submit(COMPANY, "budi", request("10000000"));

        verify(accountNameClient, never()).fetchShortDetails(anyString());
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().cross()).isNull();
    }

    @Test
    void aBeneficiaryCurrencyContradictingTheAmountCurrencyIsRejected() {
        stubHappyPath();
        var contradictory = new SubmitTransferRequest("budi", SOURCE, BENEFICIARY,
                "PT MAJU JAYA", new MoneyRequest(new BigDecimal("10000"), "IDR"), "x",
                new OtpRequest("CH-1", "123456"),
                null, null, null, null, null, null, null, null, null, null, null, null,
                "USD", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.submit(COMPANY, "budi", contradictory))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"));
        verify(accountNameClient, never()).fetchShortDetails(anyString());
    }

    // ---- P6: cross-currency Transfer ke Bank Lain (valas source, IDR wire) ----

    private static SubmitTransferRequest domesticCrossRequest(String type, String amount,
                                                              String debitAmount, String rateType) {
        return new SubmitTransferRequest("budi", SOURCE, "3049530495", "YOSUA PRISKWILA",
                new MoneyRequest(new BigDecimal(amount), "IDR"), "bayar vendor",
                new OtpRequest("CH-1", "123456"),
                type, "DB1", "Jl. Melati 1", null, null, "0811", "12345",
                null, null, null, null, null,
                null, debitAmount != null ? new BigDecimal(debitAmount) : null,
                rateType, null, null, null, null, null);
    }

    @Test
    void llgFromAValasSourceFreezesTheDebitBlockAndLaddersTheDebitSide() {
        stubDomestic();
        stubShortDetails("USD", "DEP");

        var response = service.submit(COMPANY, "budi",
                domesticCrossRequest("LLG", "10000000", "610.50", null));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().srvcCd()).isEqualTo(TransferType.SRVC_DOM_LLG);
        assertThat(task.getValue().trxCcyCd()).isEqualTo("IDR");
        // The P1 domestic block is unchanged...
        assertThat(task.getValue().domestic().benBnkCd()).isEqualTo("0140397");
        assertThat(task.getValue().domestic().feeAmt()).isEqualByComparingTo("2900");
        // ...and the V7 block is frozen: source currency, debit amount, base = the IDR
        // credit, regular rate type, no rate call (credit side is IDR), no gate.
        var cross = task.getValue().cross();
        assertThat(cross.debitCcyCd()).isEqualTo("USD");
        assertThat(cross.debitAmt()).isEqualByComparingTo("610.50");
        assertThat(cross.baseAmt()).isEqualByComparingTo("10000000");
        assertThat(cross.exchangeRate()).isNull();
        assertThat(cross.rateType()).isEqualTo("02");
        assertThat(cross.sourceProductType()).isEqualTo("DEP");
        verify(coreTransferClient, never()).fetchRates(anyString());
        verify(coreTransferClient, never()).checkUnderlying(any());
        // The ladder sees the debit side in USD - the debit amount alone, no IDR fee added.
        verify(transferMapper).findBankLimit(TransferType.SRVC_DOM_LLG, "USD");
        // The daily ceiling is reserved, and it is reserved against the DEBIT side: the
        // source currency and the debit amount, with no IDR charge added to a valas figure.
        verify(limitService).reserve(eq(COMPANY), any(), eq(TransferType.SRVC_DOM_LLG),
                eq("USD"), eq("USD"), any(), anyString());
        // No USD matrix: the IDR one is used with the bands read against the IDR base.
        verify(transferMapper).findMatrixMasterId(COMPANY, TransferServiceImpl.MENU_CD_BANK_LAIN, "USD");
        verify(transferMapper).findMatrixMasterId(COMPANY, TransferServiceImpl.MENU_CD_BANK_LAIN, "IDR");
    }

    @Test
    void aValasSourceLlgWithoutTheDebitAmountIsRejected() {
        stubDomestic();
        stubShortDetails("USD", "DEP");

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                domesticCrossRequest("LLG", "10000000", null, "02")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"))
                .hasMessageContaining("Nominal debit");
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void anIdrSourceIgnoresTheDebitFieldsAndStaysSingleLeg() {
        stubDomestic();
        stubShortDetails("IDR", "DEP");

        service.submit(COMPANY, "budi", domesticCrossRequest("RTGS", "10000000", "10030000", null));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().cross()).isNull();
        // Single-leg math: amount + fee, in IDR.
        verify(transferMapper).findBankLimit(TransferType.SRVC_DOM_RTGS, "IDR");
        verify(transferMapper, never()).findBankLimit(anyString(), eq("USD"));
    }

    @Test
    void aPlainIdrDomesticSubmitNeverProbesTheSourceAccount() {
        stubDomestic();

        service.submit(COMPANY, "budi", domesticRequest("LLG", "10000000", null));

        verify(accountNameClient, never()).fetchShortDetails(anyString());
    }

    @Test
    void onlineFromAValasSourceIsRejected() {
        stubDomestic();
        stubShortDetails("USD", "DEP");

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                domesticCrossRequest("ONLINE", "10000000", "610.50", null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"))
                .hasMessageContaining("Online");
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void aNonIdrCreditCurrencyOnLlgIsStillRejected() {
        stubDomestic();

        var request = new SubmitTransferRequest("budi", SOURCE, "3049530495", "YOSUA PRISKWILA",
                new MoneyRequest(new BigDecimal("1000"), "USD"), "bayar vendor",
                new OtpRequest("CH-1", "123456"),
                "LLG", "DB1", "Jl. Melati 1", null, null, "0811", null,
                null, null, null, null, null);
        assertThatThrownBy(() -> service.submit(COMPANY, "budi", request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("hanya tersedia untuk mata uang IDR");
        verify(accountNameClient, never()).fetchShortDetails(anyString());
    }

    @Test
    void aDebitAmountBreakingTheSourceCurrencysDecimalRuleIsRejectedOnBankLain() {
        stubDomestic();
        stubShortDetails("JPY", "DEP");

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                domesticCrossRequest("LLG", "10000000", "100000.50", null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"));
        verify(trxTaskMapper, never()).insertTask(any());
    }


    // ---- P3: Transfer ke Virtual Account ----

    private static final String VA_NUMBER = "8241002201234567";

    private static SubmitTransferRequest vaRequest(String amount, String currency,
                                                   String vaNumber, String inquiryRequestId) {
        return new SubmitTransferRequest("budi", SOURCE, vaNumber, "PT TOKOPEDIA",
                new MoneyRequest(new BigDecimal(amount), currency), "bayar tagihan",
                new OtpRequest("CH-1", "123456"),
                "VA", null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                inquiryRequestId);
    }

    @Test
    void vaSubmitBooksUnderTheInHouseMenuWithTheVaServiceFeeAndInquiryId() {
        stubHappyPath();
        // The legacy VA format parameter binds the number check.
        when(transferMapper.findSysParamValue(TransferServiceImpl.SYS_PARAM_VA_FORMAT))
                .thenReturn("99[0-9]{14}|9[0-9]{15}|8[0-9]{15}");

        var response = service.submit(COMPANY, "budi", vaRequest("150000", "IDR", VA_NUMBER, "INQ-123"));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().menuCd()).isEqualTo(TransferServiceImpl.MENU_CD_VA);
        assertThat(task.getValue().srvcCd()).isEqualTo(TransferType.SRVC_VA);
        assertThat(task.getValue().benAcctNo()).isEqualTo(VA_NUMBER);
        assertThat(task.getValue().benAcctNm()).isEqualTo("PT TOKOPEDIA");
        assertThat(task.getValue().vaInquiryReqId()).isEqualTo("INQ-123");
        assertThat(task.getValue().cross()).isNull();
        // The flat VA fee rides FEE_AMT with the rest of the domestic block empty.
        var dom = task.getValue().domestic();
        assertThat(dom.feeAmt()).isEqualByComparingTo("0");
        assertThat(dom.benDomBnkId()).isNull();
        // The ladder keys on the VA service code, the matrix on the in-house menu, and
        // the own/3rd in-house probe never runs for a VA.
        verify(transferMapper).findBankLimit(TransferType.SRVC_VA, "IDR");
        verify(transferMapper).findMatrixMasterId(COMPANY, TransferServiceImpl.MENU_CD, "IDR");
        verify(transferMapper, never()).countAnyAccount(anyString(), anyString());
        verify(accountNameClient, never()).fetchShortDetails(anyString());
    }

    @Test
    void aConfiguredVaFeeIsFrozenOnTheTaskAndLadderedWithTheAmount() {
        stubHappyPath();

        service.submit(COMPANY, "budi", vaRequest("150000", "IDR", VA_NUMBER, null));

        // The ceiling measures the DEBITED total: amount plus the charge.
        assertThat(capturedReservedAmount()).isEqualByComparingTo("150000");
    }

    @Test
    void aVaSubmitInAForeignCurrencyIsRejected() {
        stubHappyPath();

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                vaRequest("100", "USD", VA_NUMBER, null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"));
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void aVaNumberOutsideTheLegacyFormatIsRejectedBeforeTheLadder() {
        stubHappyPath();
        when(transferMapper.findSysParamValue(TransferServiceImpl.SYS_PARAM_VA_FORMAT))
                .thenReturn("8[0-9]{15}");

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                vaRequest("150000", "IDR", "1234567890", null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("VA_NUMBER_INVALID"));
        verify(transferMapper, never()).findBankLimit(anyString(), anyString());
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void aMalformedVaFormatParameterFallsBackToTheDigitRule() {
        stubHappyPath();
        when(transferMapper.findSysParamValue(TransferServiceImpl.SYS_PARAM_VA_FORMAT))
                .thenReturn("8[0-9{15}");

        var response = service.submit(COMPANY, "budi", vaRequest("150000", "IDR", VA_NUMBER, null));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                vaRequest("150000", "IDR", "12345", null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("VA_NUMBER_INVALID"));
    }

    @Test
    void vaInquiryProxiesTheBillAndAnswersTheConfiguredFee() {
        stubHappyPath();
        var props = new TransferTypeProperties();
        props.getVa().setFee(new BigDecimal("3000"));
        service = new TransferServiceImpl(transferMapper, trxTaskMapper,
                chargeMapper, chargeService, limitService,
                accountNameClient, coreTransferClient, authenticatorClient,
                executionService, executionOutbox, notificationOutbox, props,
                mock(PlatformTransactionManager.class));
        when(coreTransferClient.inquireVa(anyString(), eq(VA_NUMBER), eq(SOURCE)))
                .thenReturn(new CoreTransferClient.VaInquiry(VA_NUMBER, "INQ-123",
                        "PT TOKOPEDIA", new BigDecimal("150000"), null, "00", "OK"));

        var answer = service.vaInquiry(COMPANY, new VaInquiryRequest("budi", SOURCE, " " + VA_NUMBER + " "));

        assertThat(answer.vaNumber()).isEqualTo(VA_NUMBER);
        assertThat(answer.name()).isEqualTo("PT TOKOPEDIA");
        assertThat(answer.amount()).isEqualByComparingTo("150000");
        assertThat(answer.currency()).isEqualTo("IDR");
        assertThat(answer.inquiryRequestId()).isEqualTo("INQ-123");
        assertThat(answer.fee()).isEqualByComparingTo("3000");
        assertThat(answer.responseCode()).isEqualTo("00");
        // The inquiry reference is a 20-digit TRX_REF_NO-shaped number that never
        // touches the shared counter.
        ArgumentCaptor<String> reference = ArgumentCaptor.forClass(String.class);
        verify(coreTransferClient).inquireVa(reference.capture(), eq(VA_NUMBER), eq(SOURCE));
        assertThat(reference.getValue()).matches("\\d{20}");
        verify(transferMapper, never()).lockRefNoValue(anyString(), anyString());
    }

    @Test
    void vaInquiryPassesTheServicesRefusalThroughAsTheMachineCode() {
        stubHappyPath();
        when(coreTransferClient.inquireVa(anyString(), eq(VA_NUMBER), eq(SOURCE)))
                .thenThrow(new BusinessRuleException("VA_INQUIRY_REJECTED", "Client Tidak Ditemukan."));

        assertThatThrownBy(() -> service.vaInquiry(COMPANY, new VaInquiryRequest("budi", SOURCE, VA_NUMBER)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Client Tidak Ditemukan.")
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("VA_INQUIRY_REJECTED"));
    }

    @Test
    void vaInquiryRefusesASourceAccountOutsideTheDebitChainWithoutCallingUpstream() {
        stubHappyPath();

        assertThatThrownBy(() -> service.vaInquiry(COMPANY, new VaInquiryRequest("budi", "999", VA_NUMBER)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("SOURCE_ACCT_FORBIDDEN"));
        verify(coreTransferClient, never()).inquireVa(anyString(), anyString(), anyString());
    }

    @Test
    void vaInquiryForAnUnknownUserIs404() {
        assertThatThrownBy(() -> service.vaInquiry(COMPANY, new VaInquiryRequest("ghost", SOURCE, VA_NUMBER)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void detailNamesAVaTaskByItsServiceNotTheSharedMenuCode() {
        var task = new TrxTaskRows.TaskRow("T1", "REF1", TransferServiceImpl.MENU_CD_VA,
                TransferType.SRVC_VA, "EXECUTED", null, new BigDecimal("150000"), "IDR",
                SOURCE, VA_NUMBER, "PT TOKOPEDIA", "bayar tagihan", "BUDI SANTOSO",
                java.time.LocalDateTime.now(), 4L, "907409", "20260903100000000042",
                java.time.LocalDateTime.now(), null, null, null, null, BigDecimal.ZERO, null, null);
        when(trxTaskMapper.findTask(COMPANY, "T1")).thenReturn(task);
        when(trxTaskMapper.findActions("T1")).thenReturn(List.of());
        when(trxTaskMapper.findStages("T1")).thenReturn(List.of());

        var detail = service.detail(COMPANY, "T1", "budi");

        assertThat(detail.menuName()).isEqualTo(TransferServiceImpl.MENU_NAME_VA);
        assertThat(detail.transferType()).isEqualTo("VA");
        assertThat(detail.beneficiaryAccountNo()).isEqualTo(VA_NUMBER);
        assertThat(detail.coreJournal()).isEqualTo("907409");
    }


    // ---- P7: Transfer ke Bank Lain via BI-Fast ----

    private static final String BIFAST_SYS_PARAM =
            "Real Time|IDR 1,000|IDR 250,000,000 per transaksi, sehari 1 M|IDR 2,500";

    private static SubmitTransferRequest bifastRequest(String amount, String currency,
                                                       String purpose, BiFastCreditorRequest creditor) {
        return new SubmitTransferRequest("budi", SOURCE, "9876543210", "TUMPAL YAN RAYMOND TEST",
                new MoneyRequest(new BigDecimal(amount), currency), "bayar vendor",
                new OtpRequest("CH-1", "123456"),
                "BIFAST", "DB2", null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, purpose, null, null, creditor);
    }

    private static BiFastCreditorRequest creditor() {
        return new BiFastCreditorRequest("23231453124123", "01", "SVGS", "01", "0300", "2026-09-04");
    }

    private void stubBiFast() {
        stubHappyPath();
        when(transferMapper.findDomBank("DB2"))
                .thenReturn(new DomBankRow("DB2", "00800172", "BANK MANDIRI", "BMRIIDJA", "008", "BMRIIDJA"));
        when(transferMapper.findMatrixMasterId(COMPANY,
                TransferServiceImpl.MENU_CD_BANK_LAIN, "IDR")).thenReturn("MSTR1");
        when(transferMapper.countBiFastPurpose("01")).thenReturn(1);
        when(transferMapper.findSysParamValue(TransferServiceImpl.SYS_PARAM_BIFAST))
                .thenReturn(BIFAST_SYS_PARAM);
    }

    @Test
    void bifastSubmitFreezesTheParticipantBicPurposeCreditorBlockAndSysParamFee() {
        stubBiFast();

        var response = service.submit(COMPANY, "budi", bifastRequest("10000000", "IDR", "01", creditor()));

        assertThat(response.status()).isEqualTo("PENDING_APPROVAL");
        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().menuCd()).isEqualTo(TransferServiceImpl.MENU_CD_BANK_LAIN);
        assertThat(task.getValue().srvcCd()).isEqualTo(TransferType.SRVC_DOM_BIFAST);
        var dom = task.getValue().domestic();
        assertThat(dom.benDomBnkId()).isEqualTo("DB2");
        assertThat(dom.benBnkCd()).isEqualTo("BMRIIDJA");
        assertThat(dom.benBnkBic()).isEqualTo("BMRIIDJA");
        assertThat(dom.benType()).isNull();
        // The fee comes from the legacy SYS_PARAM row, not the config fallback.
        assertThat(dom.feeAmt()).isEqualByComparingTo("2500");
        var bifast = task.getValue().bifast();
        assertThat(bifast.purposeCd()).isEqualTo("01");
        assertThat(bifast.credId()).isEqualTo("23231453124123");
        assertThat(bifast.credType()).isEqualTo("01");
        assertThat(bifast.credAcctType()).isEqualTo("SVGS");
        assertThat(bifast.credRsdntSts()).isEqualTo("01");
        assertThat(bifast.credTown()).isEqualTo("0300");
        assertThat(bifast.settlementDt()).isEqualTo("2026-09-04");
        assertThat(bifast.proxyType()).isNull();
        assertThat(task.getValue().cross()).isNull();
        // Ladder on the BI-Fast service code, matrix on the shared Bank Lain menu, no
        // own/3rd probe, no source-currency probe for a plain IDR submit.
        verify(transferMapper).findBankLimit(TransferType.SRVC_DOM_BIFAST, "IDR");
        verify(transferMapper).findMatrixMasterId(COMPANY, TransferServiceImpl.MENU_CD_BANK_LAIN, "IDR");
        verify(transferMapper, never()).countAnyAccount(anyString(), anyString());
        verify(accountNameClient, never()).fetchShortDetails(anyString());
    }

    @Test
    void theBiFastLadderValidatesAmountPlusFeeAndFallsBackToTheConfiguredFee() {
        stubBiFast();

        service.submit(COMPANY, "budi", bifastRequest("10000000", "IDR", "01", creditor()));

        // The ceiling measures the DEBITED total: amount plus the charge.
        assertThat(capturedReservedAmount()).isEqualByComparingTo("10002500");
    }

    @Test
    void aBiFastPurposeOutsideTheLegacyTableIsRejectedBeforeTheLadder() {
        stubBiFast();
        when(transferMapper.countBiFastPurpose("07")).thenReturn(0);

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                bifastRequest("10000000", "IDR", "07", creditor())))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("BIFAST_PURPOSE_INVALID"));
        verify(transferMapper, never()).findBankLimit(anyString(), anyString());
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void aBiFastSubmitInAForeignCurrencyIsRejected() {
        stubBiFast();

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                bifastRequest("100", "USD", "01", creditor())))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"));
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void aBankWithoutABiFastCodeIsRefusedOnTheBiFastTab() {
        stubBiFast();
        when(transferMapper.findDomBank("DB2"))
                .thenReturn(new DomBankRow("DB2", "0140397", "BANK BCA", "CENAIDJA", "014", null));

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                bifastRequest("10000000", "IDR", "01", creditor())))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("BENEFICIARY_BANK_INVALID"));
    }

    @Test
    void aBiFastFromAValasSourceIsRejectedLikeOnline() {
        stubBiFast();
        stubShortDetails("USD", "DEP");
        var valas = new SubmitTransferRequest("budi", SOURCE, "9876543210", "TUMPAL",
                new MoneyRequest(new BigDecimal("10000000"), "IDR"), "bayar vendor",
                new OtpRequest("CH-1", "123456"),
                "BIFAST", "DB2", null, null, null, null, null,
                null, null, null, null, null,
                null, new BigDecimal("650"), null, null, null, null, null, null,
                null, "01", null, null, creditor());

        assertThatThrownBy(() -> service.submit(COMPANY, "budi", valas))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TRANSFER_FIELDS_INVALID"));
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void bifastSubmitFreezesTheProxyRouteWhenOneIsSent() {
        stubBiFast();
        var proxy = new SubmitTransferRequest("budi", SOURCE, "081234567890", "TUMPAL",
                new MoneyRequest(new BigDecimal("10000000"), "IDR"), "bayar vendor",
                new OtpRequest("CH-1", "123456"),
                "BIFAST", "DB2", null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, "01", "01", "081234567890", null);

        service.submit(COMPANY, "budi", proxy);

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().bifast().proxyType()).isEqualTo("01");
        assertThat(task.getValue().bifast().proxyId()).isEqualTo("081234567890");
        // No creditor echo sent: the block stays null and the switch decides at release.
        assertThat(task.getValue().bifast().credId()).isNull();
    }

    @Test
    void banksForBiFastAnswerTheParticipantBicAsBothCodeAndBic() {
        when(transferMapper.findBiFastBanks()).thenReturn(List.of(
                new DomBankRow("DB2", "00800172", "BANK MANDIRI", "BMRIIDJA", "008", "BMRIIDJA")));

        var banks = service.banks(COMPANY, "BI-FAST");

        assertThat(banks).hasSize(1);
        assertThat(banks.get(0).id()).isEqualTo("DB2");
        assertThat(banks.get(0).code()).isEqualTo("BMRIIDJA");
        assertThat(banks.get(0).bic()).isEqualTo("BMRIIDJA");
        verify(transferMapper, never()).findClearingBanks();
    }

    @Test
    void bifastPurposesListTheActiveLegacyCodes() {
        when(transferMapper.findBiFastPurposes()).thenReturn(List.of(
                new BiFastPurposeRow("01", "Investment"), new BiFastPurposeRow("99", "Others")));

        var purposes = service.bifastPurposes(COMPANY);

        assertThat(purposes).extracting("code").containsExactly("01", "99");
        assertThat(purposes.get(0).name()).isEqualTo("Investment");
    }

    @Test
    void methodInfoForBiFastReadsItsOwnSysParamRow() {
        when(transferMapper.findSysParamValue(TransferServiceImpl.SYS_PARAM_BIFAST))
                .thenReturn(BIFAST_SYS_PARAM);

        var info = service.methodInfo(COMPANY, "BIFAST");

        assertThat(info.method()).isEqualTo("BIFAST");
        assertThat(info.minAmount()).isEqualByComparingTo("1000");
        assertThat(info.maxAmount()).isEqualByComparingTo("250000000");
        assertThat(info.fee()).isEqualByComparingTo("2500");
        assertThat(info.estimatedDuration()).isEqualTo("Real Time");
    }

    @Test
    void bifastInquiryRoutesTheParticipantBicPricesTheFeeAndAnswersTheCreditor() {
        stubBiFast();
        when(coreTransferClient.inquireBiFast(any()))
                .thenReturn(new CoreTransferClient.BiFastInquiry("Vastarion", "23231453124123", "01",
                        "SVGS", "01", "0300", "", "2026-09-04", "U000"));

        var answer = service.bifastInquiry(COMPANY, new BiFastInquiryRequest(
                "budi", SOURCE, "DB2", " 9876543210 ", new BigDecimal("10000"), "01", null, null));

        assertThat(answer.beneficiaryName()).isEqualTo("Vastarion");
        assertThat(answer.receivingBic()).isEqualTo("BMRIIDJA");
        assertThat(answer.creditorId()).isEqualTo("23231453124123");
        assertThat(answer.creditorType()).isEqualTo("01");
        assertThat(answer.creditorAccountType()).isEqualTo("SVGS");
        assertThat(answer.creditorResidentStatus()).isEqualTo("01");
        assertThat(answer.creditorTownName()).isEqualTo("0300");
        assertThat(answer.settlementDate()).isEqualTo("2026-09-04");
        assertThat(answer.fee()).isEqualByComparingTo("2500");
        assertThat(answer.amount()).isEqualByComparingTo("10000");
        ArgumentCaptor<CoreTransferClient.BiFastInquiryInstruction> sent =
                ArgumentCaptor.forClass(CoreTransferClient.BiFastInquiryInstruction.class);
        verify(coreTransferClient).inquireBiFast(sent.capture());
        assertThat(sent.getValue().reference()).matches("\\d{20}");
        assertThat(sent.getValue().fromAccount()).isEqualTo(SOURCE);
        assertThat(sent.getValue().amount()).isEqualTo("10000.00");
        assertThat(sent.getValue().fee()).isEqualTo("2500.00");
        assertThat(sent.getValue().receivingBic()).isEqualTo("BMRIIDJA");
        assertThat(sent.getValue().toAccount()).isEqualTo("9876543210");
        assertThat(sent.getValue().proxyValue()).isNull();
        assertThat(sent.getValue().transactionPurpose()).isEqualTo("01");
        verify(transferMapper, never()).lockRefNoValue(anyString(), anyString());
    }

    @Test
    void bifastInquiryPassesTheSwitchRefusalThroughAsTheMachineCode() {
        stubBiFast();
        when(coreTransferClient.inquireBiFast(any()))
                .thenThrow(new BusinessRuleException("BIFAST_INQUIRY_REJECTED",
                        "(SOA) ACCOUNT NOT ABLE TO DO TRANSACTION"));

        assertThatThrownBy(() -> service.bifastInquiry(COMPANY, new BiFastInquiryRequest(
                "budi", SOURCE, "DB2", "9876543210", new BigDecimal("10000"), "01", null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("(SOA) ACCOUNT NOT ABLE TO DO TRANSACTION")
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("BIFAST_INQUIRY_REJECTED"));
    }

    @Test
    void bifastInquiryRefusesASourceOutsideTheDebitChainAndANonParticipantBankWithoutCallingUpstream() {
        stubBiFast();

        assertThatThrownBy(() -> service.bifastInquiry(COMPANY, new BiFastInquiryRequest(
                "budi", "999", "DB2", "9876543210", new BigDecimal("10000"), "01", null, null)))
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("SOURCE_ACCT_FORBIDDEN"));
        assertThatThrownBy(() -> service.bifastInquiry(COMPANY, new BiFastInquiryRequest(
                "budi", SOURCE, "DB1", "9876543210", new BigDecimal("10000"), "01", null, null)))
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("BENEFICIARY_BANK_INVALID"));
        verify(coreTransferClient, never()).inquireBiFast(any());
    }

    @Test
    void detailExposesTheBiFastIdentifiersAndPurpose() {
        var task = new TrxTaskRows.TaskRow("T1", "REF1", TransferServiceImpl.MENU_CD_BANK_LAIN,
                TransferType.SRVC_DOM_BIFAST, "EXECUTED", null, new BigDecimal("123999"), "IDR",
                SOURCE, "9876543210", "TUMPAL YAN RAYMOND TEST", "bayar vendor", "BUDI SANTOSO",
                java.time.LocalDateTime.now(), 4L, "900067", "20260904100000000042",
                java.time.LocalDateTime.now(), "DB2", "BANK MANDIRI", "BMRIIDJA", "BMRIIDJA",
                new BigDecimal("2500"), null, null, null, null, null, null, null, null, null, null,
                "20250925BNINIDJA01075210687", "20250925BNINIDJA010O0175210687", "01");
        when(trxTaskMapper.findTask(COMPANY, "T1")).thenReturn(task);
        when(trxTaskMapper.findActions("T1")).thenReturn(List.of());
        when(trxTaskMapper.findStages("T1")).thenReturn(List.of());

        var detail = service.detail(COMPANY, "T1", "budi");

        assertThat(detail.transferType()).isEqualTo("BIFAST");
        assertThat(detail.menuName()).isEqualTo(TransferServiceImpl.MENU_NAME_BANK_LAIN);
        assertThat(detail.trxId()).isEqualTo("20250925BNINIDJA01075210687");
        assertThat(detail.endToEndId()).isEqualTo("20250925BNINIDJA010O0175210687");
        assertThat(detail.transactionPurpose()).isEqualTo("01");
        assertThat(detail.totalAmount()).isEqualByComparingTo("126499");
    }

    // ---- V11: the VA bill block ----

    private static VaBillRequest openBill(String feeAmount) {
        return new VaBillRequest("o", "No.VA", "Nama", "Minimum Bayar", "OPEN PAYMENT",
                BigDecimal.ZERO, "Biaya admin", "Rp" + feeAmount, new BigDecimal(feeAmount),
                null, "1496387780", "320", null, null, null, null, null, null);
    }

    private static VaBillRequest fixedBill(String billedAmount, String feeAmount) {
        return new VaBillRequest("c", "No.VA", "Nama", "Nominal", "Rp" + billedAmount,
                new BigDecimal(billedAmount), "Biaya admin", "Rp" + feeAmount,
                new BigDecimal(feeAmount), "1000665901", "1496387781", "320",
                "Periode", null, null, "2026-09", null, null);
    }

    private static SubmitTransferRequest vaRequestWithBill(String amount, VaBillRequest bill) {
        return new SubmitTransferRequest("budi", SOURCE, VA_NUMBER, "PT TOKOPEDIA",
                new MoneyRequest(new BigDecimal(amount), "IDR"), "bayar tagihan",
                new OtpRequest("CH-1", "123456"),
                "VA", null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, bill);
    }

    @Test
    void vaInquiryAnswersTheOpenFixedFlagTheServiceFeeAndTheBillBlock() {
        stubHappyPath();
        var props = new TransferTypeProperties();
        props.getVa().setFee(new BigDecimal("3000"));
        service = new TransferServiceImpl(transferMapper, trxTaskMapper,
                chargeMapper, chargeService, limitService,
                accountNameClient, coreTransferClient, authenticatorClient,
                executionService, executionOutbox, notificationOutbox, props,
                mock(PlatformTransactionManager.class));
        when(coreTransferClient.inquireVa(anyString(), eq(VA_NUMBER), eq(SOURCE)))
                .thenReturn(new CoreTransferClient.VaInquiry(VA_NUMBER, null, "test66666",
                        BigDecimal.ZERO, null, "000", "Success",
                        VA_NUMBER, "test66666", "o", "No.VA", "Nama",
                        "Minimum Bayar", "OPEN PAYMENT", new BigDecimal("2500"),
                        "Biaya admin", "Rp2500", null, "1496387780", "320",
                        null, null, null, null, null, null));

        var answer = service.vaInquiry(COMPANY, new VaInquiryRequest("budi", SOURCE, VA_NUMBER));

        assertThat(answer.name()).isEqualTo("test66666");
        assertThat(answer.trxType()).isEqualTo("OPEN");
        // The fee the VA service quotes beats the configured placeholder.
        assertThat(answer.fee()).isEqualByComparingTo("2500");
        assertThat(answer.inquiryRequestId()).isNull();
        assertThat(answer.bill().trxType()).isEqualTo("o");
        assertThat(answer.bill().billedAmountValue()).isEqualTo("OPEN PAYMENT");
        assertThat(answer.bill().feeAmountLabel()).isEqualTo("Biaya admin");
        assertThat(answer.bill().feeAmount()).isEqualByComparingTo("2500");
        assertThat(answer.bill().trxId()).isEqualTo("1496387780");
        assertThat(answer.bill().clientId()).isEqualTo("320");
    }

    @Test
    void vaInquiryWithoutABillBlockFallsBackToTheConfiguredFeeAndReadsAsOpen() {
        stubHappyPath();
        var props = new TransferTypeProperties();
        props.getVa().setFee(new BigDecimal("3000"));
        service = new TransferServiceImpl(transferMapper, trxTaskMapper,
                chargeMapper, chargeService, limitService,
                accountNameClient, coreTransferClient, authenticatorClient,
                executionService, executionOutbox, notificationOutbox, props,
                mock(PlatformTransactionManager.class));
        when(coreTransferClient.inquireVa(anyString(), eq(VA_NUMBER), eq(SOURCE)))
                .thenReturn(new CoreTransferClient.VaInquiry(VA_NUMBER, "INQ-1",
                        "PT TOKOPEDIA", new BigDecimal("150000"), null, "00", "OK"));

        var answer = service.vaInquiry(COMPANY, new VaInquiryRequest("budi", SOURCE, VA_NUMBER));

        assertThat(answer.fee()).isEqualByComparingTo("3000");
        assertThat(answer.trxType()).isEqualTo("OPEN");
        assertThat(answer.bill().trxType()).isNull();
    }

    @Test
    void aVaSubmitFreezesTheEchoedBillAndLaddersTheServiceFee() {
        stubHappyPath();
        var props = new TransferTypeProperties();
        props.getVa().setFee(new BigDecimal("3000"));
        service = new TransferServiceImpl(transferMapper, trxTaskMapper,
                chargeMapper, chargeService, limitService,
                accountNameClient, coreTransferClient, authenticatorClient,
                executionService, executionOutbox, notificationOutbox, props,
                mock(PlatformTransactionManager.class));

        service.submit(COMPANY, "budi", vaRequestWithBill("150000", openBill("2500")));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        // The fee the VA service quoted is the one frozen and laddered, not the placeholder.
        assertThat(task.getValue().domestic().feeAmt()).isEqualByComparingTo("2500");
        assertThat(task.getValue().vaBillJson())
                .contains("\"trxType\":\"o\"")
                .contains("\"billedAmountValue\":\"OPEN PAYMENT\"")
                .contains("\"feeAmount\":2500")
                .contains("\"trxId\":\"1496387780\"")
                .doesNotContain("accountNumberTo");
        // Round-trips through the domain record the release reads.
        VaBill frozen = VaBill.fromJson(task.getValue().vaBillJson());
        assertThat(frozen.isOpen()).isTrue();
        assertThat(frozen.feeAmount()).isEqualByComparingTo("2500");
    }

    @Test
    void aVaSubmitWithoutABillBlockFreezesNothingAndKeepsTheConfiguredFee() {
        stubHappyPath();
        when(transferMapper.findSysParamValue(TransferServiceImpl.SYS_PARAM_VA_FORMAT))
                .thenReturn("99[0-9]{14}|9[0-9]{15}|8[0-9]{15}");

        service.submit(COMPANY, "budi", vaRequest("150000", "IDR", VA_NUMBER, "INQ-123"));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        assertThat(task.getValue().vaBillJson()).isNull();
        assertThat(task.getValue().domestic().feeAmt()).isEqualByComparingTo("0");
    }

    @Test
    void aFixedVaBillRefusesAnAmountThatDiffersFromTheBill() {
        stubHappyPath();

        assertThatThrownBy(() -> service.submit(COMPANY, "budi",
                vaRequestWithBill("100000", fixedBill("150000", "2500"))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("VA_AMOUNT_MISMATCH"))
                .hasMessageContaining("Rp150000");
        verify(trxTaskMapper, never()).insertTask(any());
    }

    @Test
    void aFixedVaBillAcceptsTheExactAmountAndFreezesTheAdditionalLabels() {
        stubHappyPath();

        service.submit(COMPANY, "budi", vaRequestWithBill("150000", fixedBill("150000", "2500")));

        ArgumentCaptor<TrxTaskRows.TaskInsert> task = ArgumentCaptor.forClass(TrxTaskRows.TaskInsert.class);
        verify(trxTaskMapper).insertTask(task.capture());
        VaBill frozen = VaBill.fromJson(task.getValue().vaBillJson());
        assertThat(frozen.isOpen()).isFalse();
        assertThat(frozen.kind()).isEqualTo("FIXED");
        assertThat(frozen.billedAmount()).isEqualByComparingTo("150000");
        assertThat(frozen.accountNumberTo()).isEqualTo("1000665901");
        assertThat(frozen.additionalLabel1()).isEqualTo("Periode");
        assertThat(frozen.additionalValue1()).isEqualTo("2026-09");
    }

    // ---- TASK_SUBMITTED (notification contract sections 0 and 1) ----

    @Test
    void aSubmitEnqueuesTaskSubmittedForTheFirstStagesCandidates() throws Exception {
        stubHappyPath();
        when(trxTaskMapper.findNotificationCandidates(anyString(), eq(1))).thenReturn(List.of(
                new TrxTaskRows.NotificationRecipientRow("CU2", "ani", "ANI LESTARI", "APPROVER"),
                new TrxTaskRows.NotificationRecipientRow("CU3", "cici", "CICI PARAMIDA", "APPROVER")));

        var response = service.submit(COMPANY, "budi", request("10000000"));

        ArgumentCaptor<EventOutboxRows.OutboxInsert> row =
                ArgumentCaptor.forClass(EventOutboxRows.OutboxInsert.class);
        verify(outboxMapper).insert(row.capture());
        assertThat(row.getValue().eventType()).isEqualTo("TASK_SUBMITTED");
        assertThat(row.getValue().aggregateId()).isEqualTo(response.taskId());

        JsonNode payload = new ObjectMapper().readTree(row.getValue().payload());
        assertThat(payload.get("corpId").asText()).isEqualTo(COMPANY);
        assertThat(payload.get("refNo").asText()).isEqualTo(response.refNo());
        assertThat(payload.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(payload.get("serviceCode").asText())
                .isEqualTo(TransferServiceImpl.SRVC_IN_HOUSE_3RD);
        assertThat(payload.get("recipients").findValuesAsText("userId"))
                .containsExactly("CU2", "CU3");
        // Stage 1 only: the release stage's candidates hear about it when it opens.
        verify(trxTaskMapper, never()).findNotificationCandidates(anyString(), eq(2));
    }

    /**
     * Notification rows are written in sync mode too - they belong to the workflow commit,
     * not to the execution pipeline. (They then sit NEW until a relay with a broker drains
     * them; on a laptop that never happens, which is the documented behaviour.) The
     * execution outbox, by contrast, stays untouched in sync mode.
     */
    @Test
    void notificationRowsAreWrittenInSyncModeWhileTheExecutionOutboxStaysUntouched() {
        stubHappyPath();
        when(trxTaskMapper.findNotificationCandidates(anyString(), eq(1))).thenReturn(List.of(
                new TrxTaskRows.NotificationRecipientRow("CU2", "ani", "ANI LESTARI", "APPROVER")));

        service.submit(COMPANY, "budi", request("10000000"));

        verify(outboxMapper).insert(any());
        verify(executionOutbox, never()).enqueueExecutionRequested(anyString(), any(), any());
    }

    /** A single-user task runs no workflow, so TASK_SUBMITTED has nobody to address. */
    @Test
    void aSingleUserSubmitEnqueuesNoTaskSubmitted() {
        stubHappyPath();
        when(transferMapper.findCorpFlags(COMPANY)).thenReturn(new CorpFlagsRow("Y", "Y"));
        when(executionService.execute(anyString()))
                .thenReturn(new ExecutionService.ExecutionResult("EXECUTED", null));

        service.submit(COMPANY, "budi", request("10000000"));

        verify(outboxMapper, never()).insert(any());
    }
}
