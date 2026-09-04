package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.util.List;

import id.co.bni.direct.transaction.config.TransferTypeProperties;
import id.co.bni.direct.transaction.entity.TransferRows.BankLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.BaseFtDomInsert;
import id.co.bni.direct.transaction.entity.TransferRows.BaseFtInsert;
import id.co.bni.direct.transaction.entity.TransferRows.CorpContactRow;
import id.co.bni.direct.transaction.entity.SimsemRows.SimsemAccount;
import id.co.bni.direct.transaction.entity.TransferRows.UsageLockRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ExecutionTaskRow;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.integration.CoreTransferClient.BiFastOutcome;
import id.co.bni.direct.transaction.integration.CoreTransferClient.Status;
import id.co.bni.direct.transaction.integration.CoreTransferClient.TransferOutcome;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.impl.CoreExecutionServiceImpl;
import id.co.bni.direct.transaction.service.impl.SimsemRefunder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import id.co.bni.direct.transaction.entity.TransferRows.VaFtInsert;

/**
 * The execution ladder over mocked mappers and a mocked core client. The
 * PlatformTransactionManager is a mock, so the REAL TransactionTemplate logic runs
 * (callbacks execute, exceptions trigger rollback()) without a database - which lets the
 * tests assert the transaction-boundary DESIGN: what rolls back, what commits, and that
 * the timeout path never rolls back.
 */
class CoreExecutionServiceImplTest {

    private static final String TASK = "T1";
    private static final BigDecimal AMOUNT = new BigDecimal("10000000");

    private TrxTaskMapper trxTaskMapper;
    private TransferMapper transferMapper;
    private CoreTransferClient coreTransferClient;
    private PlatformTransactionManager txManager;
    private SimsemPool simsemPool;
    private CoreExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        trxTaskMapper = mock(TrxTaskMapper.class);
        transferMapper = mock(TransferMapper.class);
        coreTransferClient = mock(CoreTransferClient.class);
        txManager = mock(PlatformTransactionManager.class);
        simsemPool = mock(SimsemPool.class);
        service = new CoreExecutionServiceImpl(trxTaskMapper, transferMapper,
                coreTransferClient, new TransferTypeProperties(), simsemPool,
                new SimsemRefunder(coreTransferClient), txManager);
        when(coreTransferClient.isEnabled()).thenReturn(true);
    }

    private static ExecutionTaskRow task(String status) {
        return new ExecutionTaskRow(TASK, "CORP1", "MNU_GCME_050200", "GCM_FTR_IH_3RD",
                "20260831100000228541", status, "113179933", "1000533372", "PT MAJU JAYA",
                AMOUNT, "IDR", "pembayaran vendor", "CU1", 3L);
    }

    /**
     * A P1 domestic task with the full frozen instruction block (V5 columns). LLG rows
     * carry the 7-digit sandi + a beneficiary type and kliring residency codes; RTGS
     * rows carry the BIC as the bank code, no beneficiary type, RTGS residency codes.
     */
    private static ExecutionTaskRow domesticTask(String srvcCd, String benType) {
        boolean llg = srvcCd.endsWith("LLG");
        return new ExecutionTaskRow(TASK, "CORP1", "MNU_GCME_050300", srvcCd,
                "20260831100000228541", "READY_TO_EXECUTE", "113179933", "3049530495",
                "YOSUA PRISKWILA", AMOUNT, "IDR", "bayar vendor", "CU1", 3L,
                "DB1", llg ? "0140397" : "CENAIDJA", "CENAIDJA",
                "Jl. Melati 1", null, null, "0811", "12345", "01", "3171", benType,
                llg ? "1" : "0", llg ? "1" : "0",
                new BigDecimal(llg ? "2900" : "30000"));
    }

    private void stubClaimableDomestic(String srvcCd, String benType) {
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(domesticTask(srvcCd, benType));
        when(trxTaskMapper.claimExecution(TASK, 3L, "CU1")).thenReturn(1);
        when(trxTaskMapper.findReleaseActorId(TASK)).thenReturn("CU9");
        when(transferMapper.lockCorpLimit("CORP1", srvcCd, "IDR"))
                .thenReturn(new UsageLockRow("CL1", BigDecimal.ZERO, new BigDecimal("2000000000")));
        when(transferMapper.findUserGroupId("CU1")).thenReturn("GRP1");
        when(transferMapper.lockGroupLimit("GRP1", srvcCd, "IDR"))
                .thenReturn(new UsageLockRow("GL1", BigDecimal.ZERO, new BigDecimal("1500000000")));
        when(transferMapper.lockRefNoValue(srvcCd, "CORP1")).thenReturn(41L);
        when(transferMapper.findCorpContact("CORP1"))
                .thenReturn(new CorpContactRow("PT DEMO TRANSAKSI", "Jl. Demo No. 1", "0211234567"));
    }

    /** A P2 ONLINE task: the 3-digit interbank code, no BIC, no address block, fee 6,500. */
    private static ExecutionTaskRow onlineTask() {
        return new ExecutionTaskRow(TASK, "CORP1", "MNU_GCME_050300", "GCM_FTR_DOM_ONLINE",
                "20260831100000228541", "READY_TO_EXECUTE", "113179933", "3049530495",
                "YOSUA PRISKWILA", AMOUNT, "IDR", "bayar vendor", "CU1", 3L,
                "DB1", "014", null,
                null, null, null, null, null, null, null, null,
                null, null,
                new BigDecimal("6500"));
    }

    private void stubClaimableOnline() {
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(onlineTask());
        when(trxTaskMapper.claimExecution(TASK, 3L, "CU1")).thenReturn(1);
        when(trxTaskMapper.findReleaseActorId(TASK)).thenReturn("CU9");
        when(transferMapper.lockCorpLimit("CORP1", "GCM_FTR_DOM_ONLINE", "IDR"))
                .thenReturn(new UsageLockRow("CL1", BigDecimal.ZERO, new BigDecimal("2000000000")));
        when(transferMapper.findUserGroupId("CU1")).thenReturn("GRP1");
        when(transferMapper.lockGroupLimit("GRP1", "GCM_FTR_DOM_ONLINE", "IDR"))
                .thenReturn(new UsageLockRow("GL1", BigDecimal.ZERO, new BigDecimal("1500000000")));
        when(transferMapper.lockRefNoValue("GCM_FTR_DOM_ONLINE", "CORP1")).thenReturn(41L);
    }

    /** Claimable task, both ceiling rows present with room, releaser on record. */
    private void stubClaimable() {
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(task("READY_TO_EXECUTE"));
        when(trxTaskMapper.claimExecution(TASK, 3L, "CU1")).thenReturn(1);
        when(trxTaskMapper.findReleaseActorId(TASK)).thenReturn("CU9");
        when(transferMapper.lockCorpLimit("CORP1", "GCM_FTR_IH_3RD", "IDR"))
                .thenReturn(new UsageLockRow("CL1", new BigDecimal("100000"), new BigDecimal("2000000000")));
        when(transferMapper.findUserGroupId("CU1")).thenReturn("GRP1");
        when(transferMapper.lockGroupLimit("GRP1", "GCM_FTR_IH_3RD", "IDR"))
                .thenReturn(new UsageLockRow("GL1", BigDecimal.ZERO, new BigDecimal("1500000000")));
        when(transferMapper.lockRefNoValue("GCM_FTR_IH_3RD", "CORP1")).thenReturn(41L);
    }

    // ---- Happy path ----

    @Test
    void aSuccessfulTransferIncrementsUsageWritesBaseFtAndLandsExecuted() {
        stubClaimable();
        when(coreTransferClient.transfer("113179933", "1000533372", AMOUNT, "IDR", "pembayaran vendor"))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907409", "PT MAJU JAYA"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.message()).isNull();

        // Usage incremented on exactly the locked rows (the decision: usage moves at release).
        verify(transferMapper).incrementCorpLimitUsage("CL1", AMOUNT);
        verify(transferMapper).incrementGroupLimitUsage("GL1", AMOUNT);

        // BASE_FT: submit-time REF_NO kept, TRX_REF_NO minted NOW off the shared counter,
        // maker as creator, releaser as updater.
        ArgumentCaptor<BaseFtInsert> baseFt = ArgumentCaptor.forClass(BaseFtInsert.class);
        verify(transferMapper).insertBaseFt(baseFt.capture());
        assertThat(baseFt.getValue().refNo()).isEqualTo("20260831100000228541");
        assertThat(baseFt.getValue().trxRefNo()).hasSize(20).endsWith("000042");
        assertThat(baseFt.getValue().srvcCd()).isEqualTo("GCM_FTR_IH_3RD");
        assertThat(baseFt.getValue().remAcctNo()).isEqualTo("113179933");
        assertThat(baseFt.getValue().benAcctNo()).isEqualTo("1000533372");
        assertThat(baseFt.getValue().trxAmt()).isEqualByComparingTo(AMOUNT);
        assertThat(baseFt.getValue().createdBy()).isEqualTo("CU1");
        assertThat(baseFt.getValue().updatedBy()).isEqualTo("CU9");
        verify(transferMapper).updateRefNoSeq("GCM_FTR_IH_3RD", "CORP1", 42L);

        verify(trxTaskMapper).markExecuted(eq(TASK), eq("907409"), anyString(), eq("CU9"));
        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().action()).isEqualTo("EXECUTE");
        assertThat(action.getValue().note()).contains("907409");

        // TX-A (claim) and TX-B (work) both committed, nothing rolled back.
        verify(txManager, times(2)).commit(any());
        verify(txManager, never()).rollback(any());
    }

    // ---- P1: type routing, fee-inclusive usage, domestic BASE_FT ----

    @Test
    void anLlgTaskRoutesToKliringAndWritesTheDomesticBaseFtRow() {
        stubClaimableDomestic("GCM_FTR_DOM_LLG", "1");
        when(coreTransferClient.transferKliring(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907409", "OK"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        // The in-house call never fires; the instruction carries the frozen payload,
        // the corp sender block and the config constants (TSA 50, branch 760).
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
        ArgumentCaptor<CoreTransferClient.KliringInstruction> kliring =
                ArgumentCaptor.forClass(CoreTransferClient.KliringInstruction.class);
        verify(coreTransferClient).transferKliring(kliring.capture());
        assertThat(kliring.getValue().fromAccount()).isEqualTo("113179933");
        assertThat(kliring.getValue().amount()).isEqualTo("10000000.00");
        assertThat(kliring.getValue().fee()).isEqualTo("2900.00");
        assertThat(kliring.getValue().clearingBankCode()).isEqualTo("0140397");
        assertThat(kliring.getValue().finalBankBic()).isEqualTo("CENAIDJA");
        assertThat(kliring.getValue().beneficiaryAccount()).isEqualTo("3049530495");
        assertThat(kliring.getValue().beneficiaryType()).isEqualTo("1");
        assertThat(kliring.getValue().senderName()).isEqualTo("PT DEMO TRANSAKSI");
        assertThat(kliring.getValue().senderAddress()).isEqualTo("Jl. Demo No. 1");
        assertThat(kliring.getValue().senderResidencyCode()).isEqualTo("1");
        assertThat(kliring.getValue().tsaCode()).isEqualTo("50");
        assertThat(kliring.getValue().intermediaryBranch()).isEqualTo("760");

        // Usage moves by the DEBITED total (amount + fee), mirroring the submit ladder.
        verify(transferMapper).incrementCorpLimitUsage("CL1", new BigDecimal("10002900"));
        verify(transferMapper).incrementGroupLimitUsage("GL1", new BigDecimal("10002900"));

        ArgumentCaptor<BaseFtDomInsert> baseFt = ArgumentCaptor.forClass(BaseFtDomInsert.class);
        verify(transferMapper).insertBaseFtDom(baseFt.capture());
        verify(transferMapper, never()).insertBaseFt(any());
        assertThat(baseFt.getValue().ftClass()).contains("LLGFT");
        assertThat(baseFt.getValue().srvcCd()).isEqualTo("GCM_FTR_DOM_LLG");
        assertThat(baseFt.getValue().refNo()).isEqualTo("20260831100000228541");
        assertThat(baseFt.getValue().trxRefNo()).hasSize(20).endsWith("000042");
        assertThat(baseFt.getValue().benDomBnkId()).isEqualTo("DB1");
        assertThat(baseFt.getValue().bicSwiftCd()).isEqualTo("CENAIDJA");
        assertThat(baseFt.getValue().benType()).isEqualTo("1");
        assertThat(baseFt.getValue().lldIsRemRes()).isEqualTo("1");
        assertThat(baseFt.getValue().lldIsBenRes()).isEqualTo("1");
        // BASE_FT.TRX_AMT stays the plain amount - the fee is a charge, not principal.
        assertThat(baseFt.getValue().trxAmt()).isEqualByComparingTo(AMOUNT);
        verify(trxTaskMapper).markExecuted(eq(TASK), eq("907409"), anyString(), eq("CU9"));
    }

    @Test
    void anRtgsTaskRoutesToTheRtgsEndpoint() {
        stubClaimableDomestic("GCM_FTR_DOM_RTGS", null);
        when(coreTransferClient.transferRtgs(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907410", "OK"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
        ArgumentCaptor<CoreTransferClient.RtgsInstruction> rtgs =
                ArgumentCaptor.forClass(CoreTransferClient.RtgsInstruction.class);
        verify(coreTransferClient).transferRtgs(rtgs.capture());
        assertThat(rtgs.getValue().rtgsBankCode()).isEqualTo("CENAIDJA");
        assertThat(rtgs.getValue().fee()).isEqualTo("30000.00");
        assertThat(rtgs.getValue().beneficiaryPostalCode()).isEqualTo("12345");
        assertThat(rtgs.getValue().senderResidencyCode()).isEqualTo("0");
        assertThat(rtgs.getValue().tsaCode()).isEqualTo("IFT00000");
        assertThat(rtgs.getValue().intermediaryBranch()).isEqualTo("760");

        verify(transferMapper).incrementCorpLimitUsage("CL1", new BigDecimal("10030000"));
        ArgumentCaptor<BaseFtDomInsert> baseFt = ArgumentCaptor.forClass(BaseFtDomInsert.class);
        verify(transferMapper).insertBaseFtDom(baseFt.capture());
        assertThat(baseFt.getValue().ftClass()).contains("RTGSFT");
        assertThat(baseFt.getValue().benType()).isNull();
    }

    @Test
    void aRefusedDomesticTransferRollsTheFeeInclusiveIncrementsBack() {
        stubClaimableDomestic("GCM_FTR_DOM_LLG", "1");
        when(coreTransferClient.transferKliring(any()))
                .thenReturn(new TransferOutcome(Status.REFUSED, null, "saldo tidak cukup"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        // TX-B rolled back (increments undone with it), TX-C wrote the verdict.
        verify(txManager).rollback(any());
        verify(transferMapper, never()).insertBaseFtDom(any());
    }

    // ---- Ceiling breach at release ----

    @Test
    void aCeilingBreachAtReleaseFailsWithoutTouchingTheCounters() {
        stubClaimable();
        // Usage moved between submit and release: the corp ceiling no longer has room.
        when(transferMapper.lockCorpLimit("CORP1", "GCM_FTR_IH_3RD", "IDR"))
                .thenReturn(new UsageLockRow("CL1", new BigDecimal("1995000000"), new BigDecimal("2000000000")));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("Limit harian perusahaan");
        verify(transferMapper, never()).incrementCorpLimitUsage(anyString(), any());
        verify(transferMapper, never()).incrementGroupLimitUsage(anyString(), any());
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
        verify(transferMapper, never()).insertBaseFt(any());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "FAILED", "CU9");
        verify(txManager, never()).rollback(any());
    }

    // ---- Decision A: the bank's per-transaction ceilings are re-read at release ----

    @Test
    void aBankLimitLoweredWhileTheTaskWaitedFailsAtReleaseWithoutIncrementing() {
        stubClaimable();
        // The bank cut the per-transaction max below this task's 10.000.000 on day two.
        when(transferMapper.findBankLimit("GCM_FTR_IH_3RD", "IDR"))
                .thenReturn(new BankLimitRow(BigDecimal.ZERO, new BigDecimal("5000000")));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("Limit transaksi bank berubah");
        verify(transferMapper, never()).incrementCorpLimitUsage(anyString(), any());
        verify(transferMapper, never()).incrementGroupLimitUsage(anyString(), any());
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "FAILED", "CU9");
    }

    @Test
    void anAccountDebitLimitLoweredWhileTheTaskWaitedFailsAtRelease() {
        stubClaimable();
        when(transferMapper.findBankLimit("GCM_FTR_IH_3RD", "IDR"))
                .thenReturn(new BankLimitRow(BigDecimal.ZERO, new BigDecimal("999999999999")));
        when(transferMapper.findAccountDebitLimit("CORP1", "113179933"))
                .thenReturn(new BigDecimal("9999999"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("Limit debit rekening sumber berubah");
        verify(transferMapper, never()).incrementCorpLimitUsage(anyString(), any());
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void bankAndAccountLimitsStillSatisfiedAtReleaseLetTheTransferProceed() {
        stubClaimable();
        when(transferMapper.findBankLimit("GCM_FTR_IH_3RD", "IDR"))
                .thenReturn(new BankLimitRow(BigDecimal.ZERO, new BigDecimal("999999999999")));
        when(transferMapper.findAccountDebitLimit("CORP1", "113179933"))
                .thenReturn(new BigDecimal("1000000000000"));
        when(coreTransferClient.transfer(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new TransferOutcome(Status.REFUSED, null, "stop here"));

        service.execute(TASK);

        // Both checks passed: the usage increment ran, i.e. we reached the core call.
        verify(transferMapper).incrementCorpLimitUsage("CL1", AMOUNT);
        verify(coreTransferClient).transfer(anyString(), anyString(), any(), anyString(), any());
    }

    // ---- Core refusal ----

    @Test
    void aCoreRefusalRollsBackTheIncrementsAndLandsFailedWithTheMessage() {
        stubClaimable();
        when(coreTransferClient.transfer(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new TransferOutcome(Status.REFUSED, null, "Nomor rekening tidak valid."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("Nomor rekening tidak valid.");
        // The increments DID run inside TX-B - and TX-B was rolled back, undoing them.
        verify(transferMapper).incrementCorpLimitUsage("CL1", AMOUNT);
        verify(txManager).rollback(any());
        verify(transferMapper, never()).insertBaseFt(any());
        // The FAILED verdict landed in its own transaction, with the reason in the note.
        verify(trxTaskMapper).markExecutionOutcome(TASK, "FAILED", "CU9");
        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().note()).contains("Nomor rekening tidak valid.");
    }

    // ---- Timeout ----

    @Test
    void aTimeoutLandsUnknownAndKeepsTheUsageIncrements() {
        stubClaimable();
        when(coreTransferClient.transfer(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new TransferOutcome(Status.UNKNOWN, null,
                        "Core banking tidak menjawab tepat waktu."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        // The transfer MAY have happened: nothing rolls back, the increments stay
        // counted until reconciliation, and no BASE_FT row pretends success.
        verify(transferMapper).incrementCorpLimitUsage("CL1", AMOUNT);
        verify(transferMapper).incrementGroupLimitUsage("GL1", AMOUNT);
        verify(txManager, never()).rollback(any());
        verify(transferMapper, never()).insertBaseFt(any());
        verify(trxTaskMapper, never()).markExecuted(anyString(), anyString(), anyString(), anyString());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "UNKNOWN", "CU9");
    }

    // ---- Double-execute guard ----

    @Test
    void aLostVersionClaimExecutesNothingAndAnswersTheLiveStatus() {
        when(trxTaskMapper.findTaskForExecution(TASK))
                .thenReturn(task("READY_TO_EXECUTE"))
                .thenReturn(task("EXECUTING"));
        // Another executor bumped VERSION between the read and the claim.
        when(trxTaskMapper.claimExecution(TASK, 3L, "CU1")).thenReturn(0);

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTING");
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
        verify(transferMapper, never()).incrementCorpLimitUsage(anyString(), any());
        verify(trxTaskMapper, never()).insertAction(any());
    }

    @Test
    void aQueuedTaskIsClaimableLikeReadyToExecute() {
        // Kafka mode: the consumer hands a QUEUED task straight to the seam - no status
        // shuffle - and the claim takes it under the same VERSION guard.
        stubClaimable();
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(task("QUEUED"));
        when(coreTransferClient.transfer(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907409", null));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        verify(trxTaskMapper).claimExecution(TASK, 3L, "CU1");
        verify(trxTaskMapper).markExecuted(eq(TASK), eq("907409"), anyString(), eq("CU9"));
    }

    @Test
    void aRedeliveredEventForAFinishedTaskNoOps() {
        // The at-least-once guarantee's other half: a duplicate EXECUTION_REQUESTED event
        // finds the task no longer claimable and answers the live status, writing nothing.
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(task("EXECUTED"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        verify(trxTaskMapper, never()).claimExecution(anyString(), any(), anyString());
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
        verify(trxTaskMapper, never()).insertAction(any());
    }

    @Test
    void anAlreadyExecutedTaskIsNotClaimedAgain() {
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(task("EXECUTED"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        verify(trxTaskMapper, never()).claimExecution(anyString(), any(), anyString());
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
    }

    // ---- The disabled hop ----

    @Test
    void aDisabledCoreHopLeavesTheTaskReadyToExecute() {
        when(coreTransferClient.isEnabled()).thenReturn(false);

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("READY_TO_EXECUTE");
        verify(trxTaskMapper, never()).claimExecution(anyString(), any(), anyString());
    }

    // ---- Single-user attribution ----

    @Test
    void withoutAReleaseActionTheMakerIsTheExecutingActor() {
        stubClaimable();
        when(trxTaskMapper.findReleaseActorId(TASK)).thenReturn(null);
        when(coreTransferClient.transfer(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907409", null));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        verify(trxTaskMapper).markExecuted(eq(TASK), eq("907409"), anyString(), eq("CU1"));
        ArgumentCaptor<BaseFtInsert> baseFt = ArgumentCaptor.forClass(BaseFtInsert.class);
        verify(transferMapper).insertBaseFt(baseFt.capture());
        assertThat(baseFt.getValue().updatedBy()).isEqualTo("CU1");
    }

    // ---- Group row absent ----

    @Test
    void aMissingGroupLimitRowBindsNothingAndStillExecutes() {
        stubClaimable();
        when(transferMapper.lockGroupLimit("GRP1", "GCM_FTR_IH_3RD", "IDR")).thenReturn(null);
        when(coreTransferClient.transfer(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907409", null));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        verify(transferMapper).incrementCorpLimitUsage("CL1", AMOUNT);
        verify(transferMapper, never()).incrementGroupLimitUsage(anyString(), any());
    }

    // ---- P2: ONLINE (RTOL / ATM Bersama) routing and the code-68 semantics ----

    @Test
    void anOnlineTaskRoutesToTheInterbankSwitchAndStoresTheTrace() {
        stubClaimableOnline();
        when(coreTransferClient.transferInterbank(any())).thenReturn(
                new CoreTransferClient.InterbankOutcome(Status.SUCCESS, "RRN000123", "00", null));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        // Neither the in-house nor the kliring/RTGS wire fires.
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
        verify(coreTransferClient, never()).transferKliring(any());
        verify(coreTransferClient, never()).transferRtgs(any());
        ArgumentCaptor<CoreTransferClient.InterbankInstruction> instruction =
                ArgumentCaptor.forClass(CoreTransferClient.InterbankInstruction.class);
        verify(coreTransferClient).transferInterbank(instruction.capture());
        assertThat(instruction.getValue().fromAccount()).isEqualTo("113179933");
        assertThat(instruction.getValue().beneficiaryAccount()).isEqualTo("3049530495");
        assertThat(instruction.getValue().beneficiaryBankCode()).isEqualTo("014");
        assertThat(instruction.getValue().amount()).isEqualTo("10000000.00");
        assertThat(instruction.getValue().refNo()).isEqualTo("20260831100000228541");
        assertThat(instruction.getValue().customerRefNo()).isEqualTo("20260831100000228541");

        // Usage moves by amount + the 6,500 fee, like every domestic method.
        verify(transferMapper).incrementCorpLimitUsage("CL1", new BigDecimal("10006500"));
        verify(transferMapper).incrementGroupLimitUsage("GL1", new BigDecimal("10006500"));

        ArgumentCaptor<BaseFtDomInsert> baseFt = ArgumentCaptor.forClass(BaseFtDomInsert.class);
        verify(transferMapper).insertBaseFtDom(baseFt.capture());
        verify(transferMapper, never()).insertBaseFt(any());
        assertThat(baseFt.getValue().ftClass()).contains("OnlineFT");
        assertThat(baseFt.getValue().srvcCd()).isEqualTo("GCM_FTR_DOM_ONLINE");
        assertThat(baseFt.getValue().refNo()).isEqualTo("20260831100000228541");
        assertThat(baseFt.getValue().trxRefNo()).hasSize(20).endsWith("000042");
        assertThat(baseFt.getValue().benDomBnkId()).isEqualTo("DB1");
        // Not on the interbank wire: BIC and residency stay NULL on the booking row.
        assertThat(baseFt.getValue().bicSwiftCd()).isNull();
        assertThat(baseFt.getValue().lldIsRemRes()).isNull();
        assertThat(baseFt.getValue().lldIsBenRes()).isNull();
        assertThat(baseFt.getValue().benType()).isNull();
        assertThat(baseFt.getValue().trxAmt()).isEqualByComparingTo(AMOUNT);

        // No core journal exists on the switch protocol; the trace carries the RRN.
        verify(trxTaskMapper).markExecuted(eq(TASK),
                org.mockito.ArgumentMatchers.isNull(), anyString(), eq("CU9"));
        verify(trxTaskMapper).updateInterbankResult(TASK, "RRN000123", "00");
        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().note()).contains("RRN000123");
        verify(txManager, times(2)).commit(any());
        verify(txManager, never()).rollback(any());
    }

    @Test
    void responseCode68LandsUnknownWithUsageKeptExactlyLikeATimeout() {
        stubClaimableOnline();
        // The client maps IN_PROCESS (68) to UNKNOWN; nothing may roll back or retry.
        when(coreTransferClient.transferInterbank(any())).thenReturn(
                new CoreTransferClient.InterbankOutcome(Status.UNKNOWN, "RRN000124", "68",
                        "Transfer antar bank sedang diproses oleh switching (kode 68). Perlu rekonsiliasi."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.message()).contains("68");
        // Exactly the timeout path's semantics: increments COMMIT with the verdict.
        verify(transferMapper).incrementCorpLimitUsage("CL1", new BigDecimal("10006500"));
        verify(transferMapper).incrementGroupLimitUsage("GL1", new BigDecimal("10006500"));
        verify(txManager, never()).rollback(any());
        verify(transferMapper, never()).insertBaseFtDom(any());
        verify(trxTaskMapper, never()).markExecuted(anyString(), any(), anyString(), anyString());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "UNKNOWN", "CU9");
        verify(trxTaskMapper).updateInterbankResult(TASK, "RRN000124", "68");
    }

    @Test
    void aRefusedInterbankTransferRollsBackAndStillStoresTheTrace() {
        stubClaimableOnline();
        when(coreTransferClient.transferInterbank(any())).thenReturn(
                new CoreTransferClient.InterbankOutcome(Status.REFUSED, "RRN000125", "51",
                        "Saldo tidak cukup."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("Saldo tidak cukup.");
        // TX-B (with its increments) rolled back; TX-C wrote the verdict AND the trace.
        verify(txManager).rollback(any());
        verify(transferMapper, never()).insertBaseFtDom(any());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "FAILED", "CU9");
        verify(trxTaskMapper).updateInterbankResult(TASK, "RRN000125", "51");
    }

    // ---- P5: multi-currency in-house routing (cross / loan) ----

    /** A P5 cross task: USD debit frozen on the V7 columns, IDR credit on the wire. */
    private static ExecutionTaskRow crossTask(String productType) {
        return new ExecutionTaskRow(TASK, "CORP1", "MNU_GCME_050200", "GCM_FTR_IH_3RD",
                "20260831100000228541", "READY_TO_EXECUTE", "113179933", "1000533372",
                "PT MAJU JAYA", AMOUNT, "IDR", "pembayaran vendor", "CU1", 3L,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null,
                "USD", new BigDecimal("610.50"), new BigDecimal("10000000"), "02", productType);
    }

    private void stubClaimableCross(ExecutionTaskRow task) {
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(task);
        when(trxTaskMapper.claimExecution(TASK, 3L, "CU1")).thenReturn(1);
        when(trxTaskMapper.findReleaseActorId(TASK)).thenReturn("CU9");
        String ccy = task.debitCcyCd() != null ? task.debitCcyCd() : task.trxCcyCd();
        when(transferMapper.lockCorpLimit("CORP1", "GCM_FTR_IH_3RD", ccy))
                .thenReturn(new UsageLockRow("CL1", BigDecimal.ZERO, new BigDecimal("2000000000")));
        when(transferMapper.findUserGroupId("CU1")).thenReturn("GRP1");
        when(transferMapper.lockGroupLimit("GRP1", "GCM_FTR_IH_3RD", ccy))
                .thenReturn(new UsageLockRow("GL1", BigDecimal.ZERO, new BigDecimal("1500000000")));
        when(transferMapper.lockRefNoValue("GCM_FTR_IH_3RD", "CORP1")).thenReturn(41L);
    }

    @Test
    void aCrossTaskRoutesToCrossCurrencyWithTheFrozenPayload() {
        stubClaimableCross(crossTask("DEP"));
        when(coreTransferClient.transferCrossCurrency(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907411", "OK"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
        verify(coreTransferClient, never()).transferLoan(any());
        ArgumentCaptor<CoreTransferClient.CrossCurrencyInstruction> cross =
                ArgumentCaptor.forClass(CoreTransferClient.CrossCurrencyInstruction.class);
        verify(coreTransferClient).transferCrossCurrency(cross.capture());
        assertThat(cross.getValue().fromAccount()).isEqualTo("113179933");
        assertThat(cross.getValue().debitAmount().amount()).isEqualTo("610.50");
        assertThat(cross.getValue().debitAmount().currency()).isEqualTo("USD");
        assertThat(cross.getValue().toAccount()).isEqualTo("1000533372");
        assertThat(cross.getValue().creditAmount().amount()).isEqualTo("10000000.00");
        assertThat(cross.getValue().creditAmount().currency()).isEqualTo("IDR");
        // The SUBMIT-TIME baseAmount rides out - never re-quoted at release.
        assertThat(cross.getValue().baseAmount()).isEqualTo("10000000");
        assertThat(cross.getValue().rateType()).isEqualTo("02");

        // Usage moves by the DEBIT side, in the debit currency's rows.
        verify(transferMapper).incrementCorpLimitUsage("CL1", new BigDecimal("610.50"));
        verify(transferMapper).incrementGroupLimitUsage("GL1", new BigDecimal("610.50"));

        // BASE_FT keeps the InHouseFT spine; TRX_CCY_CD carries the CREDIT currency.
        ArgumentCaptor<BaseFtInsert> baseFt = ArgumentCaptor.forClass(BaseFtInsert.class);
        verify(transferMapper).insertBaseFt(baseFt.capture());
        assertThat(baseFt.getValue().trxCcyCd()).isEqualTo("IDR");
        assertThat(baseFt.getValue().trxAmt()).isEqualByComparingTo(AMOUNT);
        verify(trxTaskMapper).markExecuted(eq(TASK), eq("907411"), anyString(), eq("CU9"));
    }

    @Test
    void aLoanSourceCrossTaskRoutesToTheLoanEndpoint() {
        stubClaimableCross(crossTask("LON"));
        when(coreTransferClient.transferLoan(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907412", "OK"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        verify(coreTransferClient, never()).transferCrossCurrency(any());
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
        ArgumentCaptor<CoreTransferClient.LoanInstruction> loan =
                ArgumentCaptor.forClass(CoreTransferClient.LoanInstruction.class);
        verify(coreTransferClient).transferLoan(loan.capture());
        assertThat(loan.getValue().fromAccount()).isEqualTo("113179933");
        assertThat(loan.getValue().fromCurrency()).isEqualTo("USD");
        assertThat(loan.getValue().fromAmount()).isEqualTo("610.50");
        assertThat(loan.getValue().toAccount()).isEqualTo("1000533372");
        assertThat(loan.getValue().toCurrency()).isEqualTo("IDR");
        assertThat(loan.getValue().toAmount()).isEqualTo("10000000.00");
        assertThat(loan.getValue().rateType()).isEqualTo("02");
    }

    @Test
    void aSameCurrencyLoanSourceTaskStillRoutesToLoanWithOneLegMirrored() {
        var task = new ExecutionTaskRow(TASK, "CORP1", "MNU_GCME_050200", "GCM_FTR_IH_3RD",
                "20260831100000228541", "READY_TO_EXECUTE", "113179933", "1000533372",
                "PT MAJU JAYA", AMOUNT, "IDR", "pembayaran vendor", "CU1", 3L,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, "LON");
        stubClaimableCross(task);
        when(coreTransferClient.transferLoan(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907413", "OK"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        ArgumentCaptor<CoreTransferClient.LoanInstruction> loan =
                ArgumentCaptor.forClass(CoreTransferClient.LoanInstruction.class);
        verify(coreTransferClient).transferLoan(loan.capture());
        assertThat(loan.getValue().fromCurrency()).isEqualTo("IDR");
        assertThat(loan.getValue().fromAmount()).isEqualTo("10000000.00");
        assertThat(loan.getValue().toAmount()).isEqualTo("10000000.00");
        assertThat(loan.getValue().rateType()).isNull();
        // No frozen debit side: usage moves by the plain amount in the wire currency.
        verify(transferMapper).incrementCorpLimitUsage("CL1", AMOUNT);
    }

    @Test
    void crossCeilingsAreReCheckedOnTheDebitSideAtRelease() {
        stubClaimableCross(crossTask("DEP"));
        // The USD corp ceiling has room for less than the 610.50 debit.
        when(transferMapper.lockCorpLimit("CORP1", "GCM_FTR_IH_3RD", "USD"))
                .thenReturn(new UsageLockRow("CL1", new BigDecimal("499.51"), new BigDecimal("1000")));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        verify(coreTransferClient, never()).transferCrossCurrency(any());
        verify(transferMapper, never()).incrementCorpLimitUsage(anyString(), any());
    }

    // ---- P6: cross-currency Bank Lain, two legs through a simsem account ----

    private static final String SIMSEM = "9990001";
    private static final BigDecimal DEBIT_USD = new BigDecimal("610.50");

    /**
     * A USD-source LLG task: the P1 domestic block (fee 2,900) plus the V7 debit block
     * frozen at submit (610.50 USD, base 10,000,000 IDR, rate type 02).
     */
    private static ExecutionTaskRow twoLegTask(String srvcCd) {
        boolean llg = srvcCd.endsWith("LLG");
        return new ExecutionTaskRow(TASK, "CORP1", "MNU_GCME_050300", srvcCd,
                "20260831100000228541", "READY_TO_EXECUTE", "113179933", "3049530495",
                "YOSUA PRISKWILA", AMOUNT, "IDR", "bayar vendor", "CU1", 3L,
                "DB1", llg ? "0140397" : "CENAIDJA", "CENAIDJA",
                "Jl. Melati 1", null, null, "0811", "12345", "01", "3171", llg ? "1" : null,
                llg ? "1" : "0", llg ? "1" : "0",
                new BigDecimal(llg ? "2900" : "30000"),
                "USD", DEBIT_USD, AMOUNT, "02", "DEP");
    }

    private void stubClaimableTwoLeg(String srvcCd) {
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(twoLegTask(srvcCd));
        when(trxTaskMapper.claimExecution(TASK, 3L, "CU1")).thenReturn(1);
        when(trxTaskMapper.findReleaseActorId(TASK)).thenReturn("CU9");
        when(transferMapper.lockCorpLimit("CORP1", srvcCd, "USD"))
                .thenReturn(new UsageLockRow("CL1", BigDecimal.ZERO, new BigDecimal("2000000")));
        when(transferMapper.findUserGroupId("CU1")).thenReturn("GRP1");
        when(transferMapper.lockGroupLimit("GRP1", srvcCd, "USD"))
                .thenReturn(new UsageLockRow("GL1", BigDecimal.ZERO, new BigDecimal("1500000")));
        when(transferMapper.lockRefNoValue(srvcCd, "CORP1")).thenReturn(41L);
        when(transferMapper.findCorpContact("CORP1"))
                .thenReturn(new CorpContactRow("PT DEMO TRANSAKSI", "Jl. Demo No. 1", "0211234567"));
        when(simsemPool.select(srvcCd, "IDR"))
                .thenReturn(new SimsemAccount("S1", srvcCd, "LLG", SIMSEM, "IDR"));
    }

    /** Leg 1 is the cross call FROM the customer's account; the refund is FROM the simsem. */
    private void stubLeg1(TransferOutcome outcome) {
        when(coreTransferClient.transferCrossCurrency(
                argThat(i -> i != null && "113179933".equals(i.fromAccount()))))
                .thenReturn(outcome);
    }

    private void stubRefund(TransferOutcome outcome) {
        when(coreTransferClient.transferCrossCurrency(
                argThat(i -> i != null && SIMSEM.equals(i.fromAccount()))))
                .thenReturn(outcome);
    }

    @Test
    void anEmptySimsemPoolFailsBeforeAnyIncrementOrCoreCall() {
        stubClaimableTwoLeg("GCM_FTR_DOM_LLG");
        when(simsemPool.select("GCM_FTR_DOM_LLG", "IDR")).thenReturn(null);

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("simsem belum terdaftar");
        verify(transferMapper, never()).incrementCorpLimitUsage(anyString(), any());
        verify(coreTransferClient, never()).transferCrossCurrency(any());
        verify(coreTransferClient, never()).transferKliring(any());
        verify(trxTaskMapper, never()).markSimsemSelected(anyString(), anyString(), anyString());
        verify(txManager, never()).rollback(any());
    }

    @Test
    void aRefusedLeg1RollsBackAndLandsFailedWithTheAccountPinned() {
        stubClaimableTwoLeg("GCM_FTR_DOM_LLG");
        stubLeg1(new TransferOutcome(Status.REFUSED, null, "Saldo tidak cukup."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("Saldo tidak cukup.");
        // The account was pinned (own transaction) before leg 1 left; nothing else moved.
        verify(trxTaskMapper).markSimsemSelected(TASK, SIMSEM, "CU9");
        verify(trxTaskMapper, never()).markLeg1Done(anyString(), anyString(), anyString(), anyString());
        verify(coreTransferClient, never()).transferKliring(any());
        // Usage ran inside TX-B and TX-B rolled back.
        verify(transferMapper).incrementCorpLimitUsage("CL1", DEBIT_USD);
        verify(txManager).rollback(any());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "FAILED", "CU9");
        verify(trxTaskMapper, never()).updateTwoLegState(anyString(), anyString(), anyString());
    }

    @Test
    void anUnknownLeg1LandsUnknownKeepsUsageAndNeverSendsLeg2() {
        stubClaimableTwoLeg("GCM_FTR_DOM_LLG");
        stubLeg1(new TransferOutcome(Status.UNKNOWN, null, "Core banking tidak menjawab tepat waktu."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.message()).contains("Leg 1").contains(SIMSEM);
        verify(trxTaskMapper).markSimsemSelected(TASK, SIMSEM, "CU9");
        verify(trxTaskMapper, never()).markLeg1Done(anyString(), anyString(), anyString(), anyString());
        verify(coreTransferClient, never()).transferKliring(any());
        verify(txManager, never()).rollback(any());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "UNKNOWN", "CU9");
        // No refund is ever attempted off an unconfirmed leg 1.
        verify(coreTransferClient, times(1)).transferCrossCurrency(any());
    }

    @Test
    void bothLegsSucceedingBooksTheLeg2RowWithTheSimsemAndLandsExecuted() {
        stubClaimableTwoLeg("GCM_FTR_DOM_LLG");
        stubLeg1(new TransferOutcome(Status.SUCCESS, "J1", "OK"));
        when(coreTransferClient.transferKliring(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "J2", "OK"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        // Leg 1: customer USD -> simsem IDR, amount + fee, the frozen debit and rate type.
        ArgumentCaptor<CoreTransferClient.CrossCurrencyInstruction> cross =
                ArgumentCaptor.forClass(CoreTransferClient.CrossCurrencyInstruction.class);
        verify(coreTransferClient).transferCrossCurrency(cross.capture());
        assertThat(cross.getValue().fromAccount()).isEqualTo("113179933");
        assertThat(cross.getValue().debitAmount().amount()).isEqualTo("610.50");
        assertThat(cross.getValue().debitAmount().currency()).isEqualTo("USD");
        assertThat(cross.getValue().toAccount()).isEqualTo(SIMSEM);
        assertThat(cross.getValue().creditAmount().amount()).isEqualTo("10002900.00");
        assertThat(cross.getValue().creditAmount().currency()).isEqualTo("IDR");
        assertThat(cross.getValue().baseAmount()).isEqualTo("10002900");
        assertThat(cross.getValue().rateType()).isEqualTo("02");
        // Leg 1 confirmed and committed on its own before leg 2 left.
        verify(trxTaskMapper).markLeg1Done(TASK, SIMSEM, "J1", "CU9");
        // Leg 2: the ordinary kliring payload, FROM the simsem account, sender still the corporate.
        ArgumentCaptor<CoreTransferClient.KliringInstruction> kliring =
                ArgumentCaptor.forClass(CoreTransferClient.KliringInstruction.class);
        verify(coreTransferClient).transferKliring(kliring.capture());
        assertThat(kliring.getValue().fromAccount()).isEqualTo(SIMSEM);
        assertThat(kliring.getValue().amount()).isEqualTo("10000000.00");
        assertThat(kliring.getValue().fee()).isEqualTo("2900.00");
        assertThat(kliring.getValue().senderName()).isEqualTo("PT DEMO TRANSAKSI");
        assertThat(kliring.getValue().beneficiaryAccount()).isEqualTo("3049530495");
        // Usage moved by the DEBIT side in USD.
        verify(transferMapper).incrementCorpLimitUsage("CL1", DEBIT_USD);
        verify(transferMapper).incrementGroupLimitUsage("GL1", DEBIT_USD);
        // BASE_FT: the leg-2 outward row carries the customer's account, the simsem and leg 1's journal.
        ArgumentCaptor<BaseFtDomInsert> baseFt = ArgumentCaptor.forClass(BaseFtDomInsert.class);
        verify(transferMapper).insertBaseFtDom(baseFt.capture());
        assertThat(baseFt.getValue().remAcctNo()).isEqualTo("113179933");
        assertThat(baseFt.getValue().acctNoSimsem()).isEqualTo(SIMSEM);
        assertThat(baseFt.getValue().journalNoSimsem()).isEqualTo("J1");
        assertThat(baseFt.getValue().trxAmt()).isEqualByComparingTo(AMOUNT);
        verify(trxTaskMapper).markExecuted(eq(TASK), eq("J2"), anyString(), eq("CU9"));
        verify(trxTaskMapper).updateTwoLegState(TASK, "LEG2_DONE", "CU9");
        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().note()).contains("J2").contains("J1").contains(SIMSEM);
        verify(txManager, never()).rollback(any());
    }

    @Test
    void anRtgsTwoLegTaskSendsLeg2ThroughTheRtgsEndpointFromTheSimsem() {
        stubClaimableTwoLeg("GCM_FTR_DOM_RTGS");
        stubLeg1(new TransferOutcome(Status.SUCCESS, "J1", "OK"));
        when(coreTransferClient.transferRtgs(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "J2", "OK"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        ArgumentCaptor<CoreTransferClient.RtgsInstruction> rtgs =
                ArgumentCaptor.forClass(CoreTransferClient.RtgsInstruction.class);
        verify(coreTransferClient).transferRtgs(rtgs.capture());
        assertThat(rtgs.getValue().fromAccount()).isEqualTo(SIMSEM);
        assertThat(rtgs.getValue().fee()).isEqualTo("30000.00");
        verify(coreTransferClient, never()).transferKliring(any());
    }

    @Test
    void aRefusedLeg2WithASuccessfulRefundRollsBackAndLandsFailedRefundDone() {
        stubClaimableTwoLeg("GCM_FTR_DOM_LLG");
        stubLeg1(new TransferOutcome(Status.SUCCESS, "J1", "OK"));
        when(coreTransferClient.transferKliring(any()))
                .thenReturn(new TransferOutcome(Status.REFUSED, null, "Sandi kliring tidak valid."));
        stubRefund(new TransferOutcome(Status.SUCCESS, "JR", "OK"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("Sandi kliring tidak valid.")
                .contains("J1").contains("JR").contains(SIMSEM);
        // The refund is the reverse leg 1: simsem IDR (amount + fee) -> customer USD.
        ArgumentCaptor<CoreTransferClient.CrossCurrencyInstruction> calls =
                ArgumentCaptor.forClass(CoreTransferClient.CrossCurrencyInstruction.class);
        verify(coreTransferClient, times(2)).transferCrossCurrency(calls.capture());
        var refund = calls.getAllValues().get(1);
        assertThat(refund.fromAccount()).isEqualTo(SIMSEM);
        assertThat(refund.debitAmount().amount()).isEqualTo("10002900.00");
        assertThat(refund.debitAmount().currency()).isEqualTo("IDR");
        assertThat(refund.toAccount()).isEqualTo("113179933");
        assertThat(refund.creditAmount().amount()).isEqualTo("610.50");
        assertThat(refund.creditAmount().currency()).isEqualTo("USD");
        assertThat(refund.narrativeExt()).contains("J1");
        // TX-B rolled back (usage undone, no booking row); TX-C wrote FAILED + REFUND_DONE.
        verify(txManager).rollback(any());
        verify(transferMapper, never()).insertBaseFtDom(any());
        verify(trxTaskMapper).markLeg1Done(TASK, SIMSEM, "J1", "CU9");
        verify(trxTaskMapper).markExecutionOutcome(TASK, "FAILED", "CU9");
        verify(trxTaskMapper).updateTwoLegState(TASK, "REFUND_DONE", "CU9");
    }

    @Test
    void aRefusedLeg2WithAFailedRefundLandsUnknownRefundFailedAndKeepsUsage() {
        stubClaimableTwoLeg("GCM_FTR_DOM_LLG");
        stubLeg1(new TransferOutcome(Status.SUCCESS, "J1", "OK"));
        when(coreTransferClient.transferKliring(any()))
                .thenReturn(new TransferOutcome(Status.REFUSED, null, "Sandi kliring tidak valid."));
        stubRefund(new TransferOutcome(Status.UNKNOWN, null, "Core banking tidak menjawab tepat waktu."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.message()).contains("tertahan").contains(SIMSEM).contains("J1");
        verify(txManager, never()).rollback(any());
        verify(transferMapper).incrementCorpLimitUsage("CL1", DEBIT_USD);
        verify(trxTaskMapper).markExecutionOutcome(TASK, "UNKNOWN", "CU9");
        verify(trxTaskMapper).updateTwoLegState(TASK, "REFUND_FAILED", "CU9");
        verify(transferMapper, never()).insertBaseFtDom(any());
    }

    @Test
    void anUnknownLeg2LandsUnknownLeg1DoneAndNeverRefunds() {
        stubClaimableTwoLeg("GCM_FTR_DOM_LLG");
        stubLeg1(new TransferOutcome(Status.SUCCESS, "J1", "OK"));
        when(coreTransferClient.transferKliring(any()))
                .thenReturn(new TransferOutcome(Status.UNKNOWN, null, "Core banking tidak menjawab tepat waktu."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.message()).contains("Leg 2").contains("J1").contains(SIMSEM);
        // Exactly one cross call (leg 1) - the money may be on its way to the other bank.
        verify(coreTransferClient, times(1)).transferCrossCurrency(any());
        verify(trxTaskMapper).markLeg1Done(TASK, SIMSEM, "J1", "CU9");
        verify(trxTaskMapper, never()).updateTwoLegState(anyString(), anyString(), anyString());
        verify(txManager, never()).rollback(any());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "UNKNOWN", "CU9");
        verify(transferMapper, never()).insertBaseFtDom(any());
    }

    @Test
    void aBookkeepingFailureAfterLeg2LandsUnknownWithTheLeg2Journal() {
        stubClaimableTwoLeg("GCM_FTR_DOM_LLG");
        stubLeg1(new TransferOutcome(Status.SUCCESS, "J1", "OK"));
        when(coreTransferClient.transferKliring(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "J2", "OK"));
        when(transferMapper.insertBaseFtDom(any())).thenThrow(new RuntimeException("ORA-00001"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.message()).contains("J2");
        verify(coreTransferClient, times(1)).transferCrossCurrency(any());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "UNKNOWN", "CU9");
    }

    @Test
    void aSameCurrencyDomesticTaskAndAnOnlineTaskAreNeverTwoLeg() {
        assertThat(CoreExecutionServiceImpl.isTwoLeg(domesticTask("GCM_FTR_DOM_LLG", "1"))).isFalse();
        assertThat(CoreExecutionServiceImpl.isTwoLeg(onlineTask())).isFalse();
        assertThat(CoreExecutionServiceImpl.isTwoLeg(crossTask("DEP"))).isFalse();
        assertThat(CoreExecutionServiceImpl.isTwoLeg(twoLegTask("GCM_FTR_DOM_LLG"))).isTrue();
        assertThat(CoreExecutionServiceImpl.isTwoLeg(twoLegTask("GCM_FTR_DOM_RTGS"))).isTrue();
    }


    // ---- P3: Transfer ke Virtual Account ----

    private static final String VA_NUMBER = "8241002201234567";

    /** A VA task: the VA number on BEN_ACCT_NO, a 3,000 fee, the inquiry id frozen at submit. */
    private static ExecutionTaskRow vaTask() {
        return new ExecutionTaskRow(TASK, "CORP1", "MNU_GCME_050200", "GCM_VA_BILLING",
                "20260831100000228541", "READY_TO_EXECUTE", "113179933", VA_NUMBER,
                "PT TOKOPEDIA", new BigDecimal("150000"), "IDR", "bayar tagihan", "CU1", 3L,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, new BigDecimal("3000"),
                null, null, null, null, null,
                "INQ-123");
    }

    private void stubClaimableVa() {
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(vaTask());
        when(trxTaskMapper.claimExecution(TASK, 3L, "CU1")).thenReturn(1);
        when(trxTaskMapper.findReleaseActorId(TASK)).thenReturn("CU9");
        when(transferMapper.lockCorpLimit("CORP1", "GCM_VA_BILLING", "IDR"))
                .thenReturn(new UsageLockRow("CL1", BigDecimal.ZERO, new BigDecimal("2000000000")));
        when(transferMapper.findUserGroupId("CU1")).thenReturn("GRP1");
        when(transferMapper.lockGroupLimit("GRP1", "GCM_VA_BILLING", "IDR"))
                .thenReturn(new UsageLockRow("GL1", BigDecimal.ZERO, new BigDecimal("1500000000")));
        when(transferMapper.lockRefNoValue("GCM_VA_BILLING", "CORP1")).thenReturn(41L);
    }

    @Test
    void aVaTaskPaysTheBillAndBooksTheLegacyVirtualAccountRow() {
        stubClaimableVa();
        when(coreTransferClient.transferVa(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907409", "OK"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        // No other wire fires; the payment carries the frozen VA payload.
        verify(coreTransferClient, never()).transfer(anyString(), anyString(), any(), anyString(), any());
        verify(coreTransferClient, never()).transferKliring(any());
        ArgumentCaptor<CoreTransferClient.VaInstruction> va =
                ArgumentCaptor.forClass(CoreTransferClient.VaInstruction.class);
        verify(coreTransferClient).transferVa(va.capture());
        assertThat(va.getValue().reference()).isEqualTo("20260831100000228541");
        assertThat(va.getValue().fromAccount()).isEqualTo("113179933");
        assertThat(va.getValue().billingNumber()).isEqualTo(VA_NUMBER);
        assertThat(va.getValue().amount()).isEqualTo("150000.00");
        assertThat(va.getValue().inquiryRequestId()).isEqualTo("INQ-123");

        // Usage moves by the debited total (amount + fee), like every flat-fee type.
        verify(transferMapper).incrementCorpLimitUsage("CL1", new BigDecimal("153000"));
        verify(transferMapper).incrementGroupLimitUsage("GL1", new BigDecimal("153000"));

        // The legacy VA record, not BASE_FT: principal, total, fee, both references.
        ArgumentCaptor<VaFtInsert> row = ArgumentCaptor.forClass(VaFtInsert.class);
        verify(transferMapper).insertVirtualAccountFt(row.capture());
        verify(transferMapper, never()).insertBaseFt(any());
        verify(transferMapper, never()).insertBaseFtDom(any());
        assertThat(row.getValue().corpId()).isEqualTo("CORP1");
        assertThat(row.getValue().vaNo()).isEqualTo(VA_NUMBER);
        assertThat(row.getValue().vaName()).isEqualTo("PT TOKOPEDIA");
        assertThat(row.getValue().currency()).isEqualTo("IDR");
        assertThat(row.getValue().billedAmt()).isEqualByComparingTo("150000");
        assertThat(row.getValue().feeAmt()).isEqualByComparingTo("3000");
        assertThat(row.getValue().totalAmt()).isEqualByComparingTo("153000");
        assertThat(row.getValue().billedAmtValue()).isEqualTo("Rp150000");
        assertThat(row.getValue().feeAmtValue()).isEqualTo("Rp3000");
        assertThat(row.getValue().debitedAcctNo()).isEqualTo("113179933");
        assertThat(row.getValue().refNo()).isEqualTo("20260831100000228541");
        assertThat(row.getValue().trxRefNo()).hasSize(20).endsWith("000042");
        assertThat(row.getValue().remark()).isEqualTo("bayar tagihan");
        assertThat(row.getValue().createdBy()).isEqualTo("CU1");
        assertThat(row.getValue().updatedBy()).isEqualTo("CU9");
        verify(transferMapper).updateRefNoSeq("GCM_VA_BILLING", "CORP1", 42L);

        // The journalNum is the task's core journal; the note names it.
        verify(trxTaskMapper).markExecuted(eq(TASK), eq("907409"), anyString(), eq("CU9"));
        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().note()).contains("907409");
        verify(txManager, times(2)).commit(any());
        verify(txManager, never()).rollback(any());
    }

    @Test
    void aRefusedVaPaymentRollsTheIncrementsBackAndLandsFailedWithTheServiceMessage() {
        stubClaimableVa();
        when(coreTransferClient.transferVa(any()))
                .thenReturn(new TransferOutcome(Status.REFUSED, null, "Client Tidak Ditemukan."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("Client Tidak Ditemukan.");
        verify(txManager).rollback(any());
        verify(transferMapper, never()).insertVirtualAccountFt(any());
        verify(trxTaskMapper, never()).markExecuted(anyString(), anyString(), anyString(), anyString());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "FAILED", "CU9");
    }

    @Test
    void anUnansweredVaPaymentLandsUnknownAndKeepsTheUsage() {
        stubClaimableVa();
        when(coreTransferClient.transferVa(any()))
                .thenReturn(new TransferOutcome(Status.UNKNOWN, null,
                        "Core banking tidak menjawab tepat waktu."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        verify(transferMapper).incrementCorpLimitUsage("CL1", new BigDecimal("153000"));
        verify(txManager, never()).rollback(any());
        verify(transferMapper, never()).insertVirtualAccountFt(any());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "UNKNOWN", "CU9");
    }

    @Test
    void aBookkeepingFailureAfterTheVaPaymentLandsUnknownWithTheJournal() {
        stubClaimableVa();
        when(coreTransferClient.transferVa(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "907409", "OK"));
        when(transferMapper.insertVirtualAccountFt(any())).thenThrow(new RuntimeException("ORA-00001"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.message()).contains("907409");
        verify(trxTaskMapper, never()).markExecuted(anyString(), anyString(), anyString(), anyString());
    }


    // ---- P7: Transfer ke Bank Lain via BI-Fast ----

    private static final BigDecimal BIFAST_AMOUNT = new BigDecimal("123999");

    /** A BI-Fast task: participant BIC as bank code and BIC, no address block, the V10 block. */
    private static ExecutionTaskRow bifastTask() {
        return new ExecutionTaskRow(TASK, "CORP1", "MNU_GCME_050300", "GCM_FTR_DOM_BIFAST",
                "20260831100000228541", "READY_TO_EXECUTE", "113179933", "9876543210",
                "TUMPAL YAN RAYMOND TEST", BIFAST_AMOUNT, "IDR", "bayar vendor", "CU1", 3L,
                "DB2", "BMRIIDJA", "BMRIIDJA",
                null, null, null, null, null, null, null, null,
                null, null,
                new BigDecimal("2500"),
                null, null, null, null, null,
                null,
                "01", "23231453124123", "01", "SVGS", "01", "0300", "2026-09-04",
                null, null);
    }

    private void stubClaimableBiFast() {
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(bifastTask());
        when(trxTaskMapper.claimExecution(TASK, 3L, "CU1")).thenReturn(1);
        when(trxTaskMapper.findReleaseActorId(TASK)).thenReturn("CU9");
        when(transferMapper.lockCorpLimit("CORP1", "GCM_FTR_DOM_BIFAST", "IDR"))
                .thenReturn(new UsageLockRow("CL1", BigDecimal.ZERO, new BigDecimal("2000000000")));
        when(transferMapper.findUserGroupId("CU1")).thenReturn("GRP1");
        when(transferMapper.lockGroupLimit("GRP1", "GCM_FTR_DOM_BIFAST", "IDR"))
                .thenReturn(new UsageLockRow("GL1", BigDecimal.ZERO, new BigDecimal("1500000000")));
        when(transferMapper.lockRefNoValue("GCM_FTR_DOM_BIFAST", "CORP1")).thenReturn(41L);
    }

    private static BiFastOutcome bifast(Status status, String journal, String message) {
        return new BiFastOutcome(new TransferOutcome(status, journal, message),
                status == Status.REFUSED ? null : "20250925BNINIDJA01075210687",
                status == Status.REFUSED ? null : "20250925BNINIDJA010O0175210687");
    }

    @Test
    void aBiFastTaskCreditsTheSwitchAndBooksBaseFtWithTheSwitchIdentifiers() {
        stubClaimableBiFast();
        when(coreTransferClient.transferBiFast(any())).thenReturn(bifast(Status.SUCCESS, "900067", "OK"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("EXECUTED");
        verify(coreTransferClient, never()).transferKliring(any());
        verify(coreTransferClient, never()).transferInterbank(any());
        ArgumentCaptor<CoreTransferClient.BiFastInstruction> sent =
                ArgumentCaptor.forClass(CoreTransferClient.BiFastInstruction.class);
        verify(coreTransferClient).transferBiFast(sent.capture());
        assertThat(sent.getValue().reference()).isEqualTo("20260831100000228541");
        assertThat(sent.getValue().fromAccount()).isEqualTo("113179933");
        assertThat(sent.getValue().toAccount()).isEqualTo("9876543210");
        assertThat(sent.getValue().amount()).isEqualTo("123999.00");
        assertThat(sent.getValue().fee()).isEqualTo("2500.00");
        assertThat(sent.getValue().receivingBic()).isEqualTo("BMRIIDJA");
        assertThat(sent.getValue().creditorName()).isEqualTo("TUMPAL YAN RAYMOND TEST");
        assertThat(sent.getValue().creditorId()).isEqualTo("23231453124123");
        assertThat(sent.getValue().creditorType()).isEqualTo("01");
        assertThat(sent.getValue().creditorAccountType()).isEqualTo("SVGS");
        assertThat(sent.getValue().creditorResidentStatus()).isEqualTo("01");
        assertThat(sent.getValue().creditorTownName()).isEqualTo("0300");
        assertThat(sent.getValue().settlementDate()).isEqualTo("2026-09-04");
        assertThat(sent.getValue().transactionPurpose()).isEqualTo("01");
        assertThat(sent.getValue().description()).isEqualTo("bayar vendor");
        assertThat(sent.getValue().proxyValue()).isNull();

        // Usage moves by amount + fee like every flat-fee domestic type.
        verify(transferMapper).incrementCorpLimitUsage("CL1", new BigDecimal("126499"));
        verify(transferMapper).incrementGroupLimitUsage("GL1", new BigDecimal("126499"));

        ArgumentCaptor<BaseFtDomInsert> row = ArgumentCaptor.forClass(BaseFtDomInsert.class);
        verify(transferMapper).insertBaseFtDom(row.capture());
        verify(transferMapper, never()).insertBaseFt(any());
        assertThat(row.getValue().ftClass()).isEqualTo("com.aprisma.product.gcm.common.model.BIFastFT");
        assertThat(row.getValue().srvcCd()).isEqualTo("GCM_FTR_DOM_BIFAST");
        assertThat(row.getValue().benDomBnkId()).isEqualTo("DB2");
        assertThat(row.getValue().bicSwiftCd()).isEqualTo("BMRIIDJA");
        assertThat(row.getValue().bifastPurposeCd()).isEqualTo("01");
        assertThat(row.getValue().biFastBenCd()).isEqualTo("01");
        assertThat(row.getValue().trxId()).isEqualTo("20250925BNINIDJA01075210687");
        assertThat(row.getValue().endToEndId()).isEqualTo("20250925BNINIDJA010O0175210687");
        assertThat(row.getValue().trxRefNo()).hasSize(20).endsWith("000042");
        assertThat(row.getValue().acctNoSimsem()).isNull();

        verify(trxTaskMapper).markExecuted(eq(TASK), eq("900067"), anyString(), eq("CU9"));
        verify(trxTaskMapper).updateBiFastResult(TASK, "20250925BNINIDJA01075210687",
                "20250925BNINIDJA010O0175210687", "CU9");
        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().note()).contains("900067").contains("20250925BNINIDJA01075210687");
        verify(txManager, times(2)).commit(any());
        verify(txManager, never()).rollback(any());
    }

    @Test
    void aRefusedBiFastCreditRollsTheIncrementsBackAndLandsFailedWithTheSwitchMessage() {
        stubClaimableBiFast();
        when(coreTransferClient.transferBiFast(any()))
                .thenReturn(bifast(Status.REFUSED, null, "(SOA) ACCOUNT NOT ABLE TO DO TRANSACTION"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("ACCOUNT NOT ABLE TO DO TRANSACTION");
        verify(txManager).rollback(any());
        verify(transferMapper, never()).insertBaseFtDom(any());
        verify(trxTaskMapper, never()).markExecuted(anyString(), anyString(), anyString(), anyString());
        verify(trxTaskMapper, never()).updateBiFastResult(anyString(), any(), any(), anyString());
        verify(trxTaskMapper).markExecutionOutcome(TASK, "FAILED", "CU9");
    }

    @Test
    void anUnansweredBiFastCreditLandsUnknownKeepsTheUsageAndPinsAnyIdentifiersTheSwitchAnswered() {
        stubClaimableBiFast();
        when(coreTransferClient.transferBiFast(any()))
                .thenReturn(bifast(Status.UNKNOWN, null, "Core banking tidak menjawab tepat waktu."));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        verify(transferMapper).incrementCorpLimitUsage("CL1", new BigDecimal("126499"));
        verify(txManager, never()).rollback(any());
        verify(transferMapper, never()).insertBaseFtDom(any());
        verify(trxTaskMapper).updateBiFastResult(TASK, "20250925BNINIDJA01075210687",
                "20250925BNINIDJA010O0175210687", "CU9");
        verify(trxTaskMapper).markExecutionOutcome(TASK, "UNKNOWN", "CU9");
    }

    @Test
    void aBookkeepingFailureAfterTheBiFastCreditLandsUnknownWithTheJournal() {
        stubClaimableBiFast();
        when(coreTransferClient.transferBiFast(any())).thenReturn(bifast(Status.SUCCESS, "900067", "OK"));
        when(transferMapper.insertBaseFtDom(any())).thenThrow(new RuntimeException("ORA-00001"));

        var result = service.execute(TASK);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.message()).contains("900067");
        verify(trxTaskMapper, never()).markExecuted(anyString(), anyString(), anyString(), anyString());
    }
}
