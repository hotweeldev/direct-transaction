package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.util.List;

import id.co.bni.direct.transaction.entity.TransferRows.BaseFtInsert;
import id.co.bni.direct.transaction.entity.TransferRows.UsageLockRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ExecutionTaskRow;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.integration.CoreTransferClient.Status;
import id.co.bni.direct.transaction.integration.CoreTransferClient.TransferOutcome;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.impl.CoreExecutionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private CoreExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        trxTaskMapper = mock(TrxTaskMapper.class);
        transferMapper = mock(TransferMapper.class);
        coreTransferClient = mock(CoreTransferClient.class);
        txManager = mock(PlatformTransactionManager.class);
        service = new CoreExecutionServiceImpl(trxTaskMapper, transferMapper,
                coreTransferClient, txManager);
        when(coreTransferClient.isEnabled()).thenReturn(true);
    }

    private static ExecutionTaskRow task(String status) {
        return new ExecutionTaskRow(TASK, "CORP1", "MNU_GCME_050200", "GCM_FTR_IH_3RD",
                "20260831100000228541", status, "113179933", "1000533372", "PT MAJU JAYA",
                AMOUNT, "IDR", "pembayaran vendor", "CU1", 3L);
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
}
