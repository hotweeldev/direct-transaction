package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.util.List;

import id.co.bni.direct.transaction.config.TransferTypeProperties;
import id.co.bni.direct.transaction.entity.TransferRows.BaseFtDomInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ExecutionTaskRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.TwoLegState;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.NotFoundException;
import id.co.bni.direct.transaction.exception.ServiceUnavailableException;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.integration.CoreTransferClient.Posting;
import id.co.bni.direct.transaction.integration.CoreTransferClient.Status;
import id.co.bni.direct.transaction.integration.CoreTransferClient.TransferOutcome;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.bni.direct.transaction.entity.EventOutboxRows;
import com.fasterxml.jackson.databind.JsonNode;
import id.co.bni.direct.transaction.repository.mapper.ExecutionOutboxMapper;
import id.co.bni.direct.transaction.service.impl.NotificationOutbox;
import id.co.bni.direct.transaction.service.impl.ReconciliationServiceImpl;
import id.co.bni.direct.transaction.service.impl.SimsemRefunder;
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
 * P6 task 6.5 over mocked mappers and a mocked core client: what the simsem account's
 * posting window proves, and the one rule that matters - no refund unless leg 2 is
 * PROVABLY absent.
 */
class ReconciliationServiceImplTest {

    private static final String COMPANY = "CORP1";
    private static final String TASK = "T1";
    private static final String SIMSEM = "9990001";
    private static final String REF = "20260903100000228541";
    private static final BigDecimal AMOUNT = new BigDecimal("10000000");

    private TrxTaskMapper trxTaskMapper;
    private TransferMapper transferMapper;
    private CoreTransferClient coreTransferClient;
    private ExecutionOutboxMapper outboxMapper;
    private ReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        trxTaskMapper = mock(TrxTaskMapper.class);
        transferMapper = mock(TransferMapper.class);
        coreTransferClient = mock(CoreTransferClient.class);
        outboxMapper = mock(ExecutionOutboxMapper.class);
        service = new ReconciliationServiceImpl(trxTaskMapper, mock(LimitService.class), transferMapper, coreTransferClient,
                new SimsemRefunder(coreTransferClient), new TransferTypeProperties(),
                new NotificationOutbox(outboxMapper, trxTaskMapper, new ObjectMapper()),
                mock(PlatformTransactionManager.class));
    }

    /** A USD-source LLG task frozen at submit: 610.50 USD debit, 10,000,000 IDR out, fee 2,900. */
    private static ExecutionTaskRow task() {
        return new ExecutionTaskRow(TASK, COMPANY, "MNU_GCME_050300", "GCM_FTR_DOM_LLG",
                REF, "UNKNOWN", "113179933", "3049530495", "YOSUA PRISKWILA",
                AMOUNT, "IDR", "bayar vendor", "CU1", 5L,
                "DB1", "0140397", "CENAIDJA", "Jl. Melati 1", null, null, "0811", "12345",
                "01", "3171", "1", "1", "1", new BigDecimal("2900"),
                "USD", new BigDecimal("610.50"), AMOUNT, "02", "DEP");
    }

    private static TrxTaskRows.TaskRow scoped(String status) {
        return new TrxTaskRows.TaskRow(TASK, REF, "MNU_GCME_050300", "GCM_FTR_DOM_LLG",
                status, null, AMOUNT, "IDR", "113179933", "3049530495", "YOSUA PRISKWILA",
                "bayar vendor", "BUDI SANTOSO", null, 5L, null, null, null,
                null, null, null, null, null, null, null);
    }

    private void stubStranded() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(scoped("UNKNOWN"));
        when(trxTaskMapper.findTwoLegState(TASK))
                .thenReturn(new TwoLegState("UNKNOWN", "LEG1_DONE", SIMSEM, "J1"));
        when(trxTaskMapper.findTaskForExecution(TASK)).thenReturn(task());
        when(transferMapper.lockRefNoValue("GCM_FTR_DOM_LLG", COMPANY)).thenReturn(41L);
    }

    private static Posting posting(String journal, String narrative, String amount) {
        return new Posting("2026-09-03", "2026-09-03T10:00:00Z", journal, narrative,
                new BigDecimal(amount));
    }

    private static Posting leg1Credit() {
        return posting("J1", "bayar vendor", "10002900.00");
    }

    // ---- Guards ----

    @Test
    void anUnknownTaskOutsideTheCompanyIs404() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(null);

        assertThatThrownBy(() -> service.reconcile(COMPANY, TASK, "ops"))
                .isInstanceOf(NotFoundException.class);
        verify(coreTransferClient, never()).inquireTransactions(anyString(), anyString());
    }

    @Test
    void aTaskNotStrandedAtUnknownLeg1DoneIsRefused() {
        when(trxTaskMapper.findTask(COMPANY, TASK)).thenReturn(scoped("EXECUTED"));
        when(trxTaskMapper.findTwoLegState(TASK))
                .thenReturn(new TwoLegState("EXECUTED", "LEG2_DONE", SIMSEM, "J1"));

        assertThatThrownBy(() -> service.reconcile(COMPANY, TASK, "ops"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("TASK_NOT_RECONCILABLE"));
        verify(coreTransferClient, never()).inquireTransactions(anyString(), anyString());
        verify(coreTransferClient, never()).transferCrossCurrency(any());
    }

    @Test
    void aStatusHopThatDoesNotAnswerPropagatesAndMovesNothing() {
        stubStranded();
        when(coreTransferClient.inquireTransactions(SIMSEM, "01"))
                .thenThrow(new ServiceUnavailableException("down"));

        assertThatThrownBy(() -> service.reconcile(COMPANY, TASK, "ops"))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(coreTransferClient, never()).transferCrossCurrency(any());
        verify(trxTaskMapper, never()).reconcileToExecuted(anyString(), anyString(), anyString(), anyString());
        verify(trxTaskMapper, never()).reconcileToRefunded(anyString(), anyString(), anyString(), anyString());
    }

    // ---- Leg 2 landed ----

    @Test
    void aMatchingDebitInTheWindowFinalizesExecutedWithTheLeg2BaseFtRow() {
        stubStranded();
        when(coreTransferClient.inquireTransactions(SIMSEM, "01")).thenReturn(List.of(
                posting("J2", "bayar vendor", "-10000000.00"),
                leg1Credit()));
        when(trxTaskMapper.reconcileToExecuted(eq(TASK), eq("J2"), anyString(), eq("ops"))).thenReturn(1);

        var response = service.reconcile(COMPANY, TASK, "ops");

        assertThat(response.outcome()).isEqualTo("EXECUTED");
        assertThat(response.status()).isEqualTo("EXECUTED");
        assertThat(response.twoLegState()).isEqualTo("LEG2_DONE");
        // Never a refund when leg 2 is found.
        verify(coreTransferClient, never()).transferCrossCurrency(any());
        ArgumentCaptor<BaseFtDomInsert> baseFt = ArgumentCaptor.forClass(BaseFtDomInsert.class);
        verify(transferMapper).insertBaseFtDom(baseFt.capture());
        assertThat(baseFt.getValue().remAcctNo()).isEqualTo("113179933");
        assertThat(baseFt.getValue().acctNoSimsem()).isEqualTo(SIMSEM);
        assertThat(baseFt.getValue().journalNoSimsem()).isEqualTo("J1");
        assertThat(baseFt.getValue().trxRefNo()).hasSize(20).endsWith("000042");
        assertThat(baseFt.getValue().updatedBy()).isEqualTo("ops");
        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().action()).isEqualTo("RECONCILE");
        assertThat(action.getValue().note()).contains("J2").contains("J1");
    }

    @Test
    void aMatchByReferenceNumberNarrativeAlsoCounts() {
        stubStranded();
        when(coreTransferClient.inquireTransactions(SIMSEM, "01")).thenReturn(List.of(
                posting("J2", "LLG " + REF, "-10000000.00"),
                leg1Credit()));
        when(trxTaskMapper.reconcileToExecuted(eq(TASK), eq("J2"), anyString(), eq("ops"))).thenReturn(1);

        assertThat(service.reconcile(COMPANY, TASK, "ops").outcome()).isEqualTo("EXECUTED");
    }

    @Test
    void aConcurrentReconcileThatLostTheRaceAnswersNoopAndWritesNothing() {
        stubStranded();
        when(coreTransferClient.inquireTransactions(SIMSEM, "01")).thenReturn(List.of(
                posting("J2", "bayar vendor", "-10000000.00"), leg1Credit()));
        when(trxTaskMapper.reconcileToExecuted(eq(TASK), eq("J2"), anyString(), eq("ops"))).thenReturn(0);

        var response = service.reconcile(COMPANY, TASK, "ops");

        assertThat(response.outcome()).isEqualTo("NOOP");
        verify(transferMapper, never()).insertBaseFtDom(any());
        verify(trxTaskMapper, never()).insertAction(any());
    }

    // ---- Leg 2 provably absent -> refund ----

    @Test
    void leg1VisibleAndNoLeg2DebitRefundsAndLandsFailedRefundDone() {
        stubStranded();
        when(coreTransferClient.inquireTransactions(SIMSEM, "01")).thenReturn(List.of(
                posting("J9", "other transfer", "-5000000.00"),
                leg1Credit()));
        when(coreTransferClient.transferCrossCurrency(any()))
                .thenReturn(new TransferOutcome(Status.SUCCESS, "JR", "OK"));
        when(trxTaskMapper.reconcileToRefunded(TASK, "FAILED", "REFUND_DONE", "ops")).thenReturn(1);

        var response = service.reconcile(COMPANY, TASK, "ops");

        assertThat(response.outcome()).isEqualTo("REFUNDED");
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.twoLegState()).isEqualTo("REFUND_DONE");
        // The refund is the reverse leg 1: simsem IDR (amount + fee) -> customer USD 610.50.
        ArgumentCaptor<CoreTransferClient.CrossCurrencyInstruction> refund =
                ArgumentCaptor.forClass(CoreTransferClient.CrossCurrencyInstruction.class);
        verify(coreTransferClient).transferCrossCurrency(refund.capture());
        assertThat(refund.getValue().fromAccount()).isEqualTo(SIMSEM);
        assertThat(refund.getValue().debitAmount().amount()).isEqualTo("10002900.00");
        assertThat(refund.getValue().debitAmount().currency()).isEqualTo("IDR");
        assertThat(refund.getValue().toAccount()).isEqualTo("113179933");
        assertThat(refund.getValue().creditAmount().amount()).isEqualTo("610.50");
        assertThat(refund.getValue().creditAmount().currency()).isEqualTo("USD");
        assertThat(refund.getValue().narrative()).contains(REF);
        assertThat(refund.getValue().narrativeExt()).contains("J1");
        verify(transferMapper, never()).insertBaseFtDom(any());
        ArgumentCaptor<ActionInsert> action = ArgumentCaptor.forClass(ActionInsert.class);
        verify(trxTaskMapper).insertAction(action.capture());
        assertThat(action.getValue().note()).contains("JR").contains("J1");
    }

    @Test
    void aRefusedRefundLandsUnknownRefundFailed() {
        stubStranded();
        when(coreTransferClient.inquireTransactions(SIMSEM, "01")).thenReturn(List.of(leg1Credit()));
        when(coreTransferClient.transferCrossCurrency(any()))
                .thenReturn(new TransferOutcome(Status.REFUSED, null, "Rekening diblokir."));
        when(trxTaskMapper.reconcileToRefunded(TASK, "UNKNOWN", "REFUND_FAILED", "ops")).thenReturn(1);

        var response = service.reconcile(COMPANY, TASK, "ops");

        assertThat(response.outcome()).isEqualTo("REFUND_FAILED");
        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.twoLegState()).isEqualTo("REFUND_FAILED");
        assertThat(response.message()).contains("Rekening diblokir.").contains(SIMSEM);
    }

    // ---- Nothing proven -> nothing moves ----

    @Test
    void leg1NoLongerInTheWindowIsInconclusiveAndNeverRefunds() {
        stubStranded();
        when(coreTransferClient.inquireTransactions(SIMSEM, "01")).thenReturn(List.of(
                posting("J9", "other transfer", "-5000000.00")));

        var response = service.reconcile(COMPANY, TASK, "ops");

        assertThat(response.outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.twoLegState()).isEqualTo("LEG1_DONE");
        verify(coreTransferClient, never()).transferCrossCurrency(any());
        verify(trxTaskMapper, never()).reconcileToExecuted(anyString(), anyString(), anyString(), anyString());
        verify(trxTaskMapper, never()).reconcileToRefunded(anyString(), anyString(), anyString(), anyString());
        verify(trxTaskMapper, never()).insertAction(any());
    }

    @Test
    void anEmptyWindowIsInconclusiveToo() {
        stubStranded();
        when(coreTransferClient.inquireTransactions(SIMSEM, "01")).thenReturn(List.of());

        assertThat(service.reconcile(COMPANY, TASK, "ops").outcome()).isEqualTo("INCONCLUSIVE");
        verify(coreTransferClient, never()).transferCrossCurrency(any());
    }

    // ---- The match rule ----

    @Test
    void theLeg2MatchNeedsADebitOfTheOutwardAmountWithTheTasksNarrative() {
        ExecutionTaskRow task = task();
        // A credit of the amount is not leg 2 (wrong direction).
        assertThat(ReconciliationServiceImpl.isLeg2Of(
                posting("X", "bayar vendor", "10000000.00"), task, "J1")).isFalse();
        // Leg 1's own journal is never leg 2, whatever its shape.
        assertThat(ReconciliationServiceImpl.isLeg2Of(
                posting("J1", "bayar vendor", "-10000000.00"), task, "J1")).isFalse();
        // Right amount, unrelated narrative: not ours.
        assertThat(ReconciliationServiceImpl.isLeg2Of(
                posting("X", "gaji karyawan", "-10000000.00"), task, "J1")).isFalse();
        // Amount off by the fee: not the outward posting.
        assertThat(ReconciliationServiceImpl.isLeg2Of(
                posting("X", "bayar vendor", "-10002900.00"), task, "J1")).isFalse();
        // Debit of the amount with the remark, or with the reference number: ours.
        assertThat(ReconciliationServiceImpl.isLeg2Of(
                posting("X", "bayar vendor", "-10000000.00"), task, "J1")).isTrue();
        assertThat(ReconciliationServiceImpl.isLeg2Of(
                posting("X", REF, "-10000000.00"), task, "J1")).isTrue();
        // No amount parsed: never a match.
        assertThat(ReconciliationServiceImpl.isLeg2Of(
                new Posting(null, null, "X", "bayar vendor", null), task, "J1")).isFalse();
    }

    /**
     * A reconciliation is the only path other than the execution seam that moves a task to
     * a terminal status, so it owes the same TRANSACTION_* event - written inside the
     * finalizing transaction, not after it.
     */
    @Test
    void reconcilingToExecutedEnqueuesTheExecutedNotification() throws Exception {
        stubStranded();
        when(trxTaskMapper.findNotificationMaker(TASK)).thenReturn(
                new TrxTaskRows.NotificationRecipientRow("CU1", "budi", "BUDI", "MAKER"));
        when(coreTransferClient.inquireTransactions(SIMSEM, "01")).thenReturn(List.of(
                posting("J2", "bayar vendor", "-10000000.00"),
                leg1Credit()));
        when(trxTaskMapper.reconcileToExecuted(eq(TASK), eq("J2"), anyString(), eq("actor")))
                .thenReturn(1);

        service.reconcile(COMPANY, TASK, "actor");

        ArgumentCaptor<EventOutboxRows.OutboxInsert> row =
                ArgumentCaptor.forClass(EventOutboxRows.OutboxInsert.class);
        verify(outboxMapper).insert(row.capture());
        assertThat(row.getValue().eventType()).isEqualTo("TRANSACTION_EXECUTED");
        assertThat(new ObjectMapper().readTree(row.getValue().payload())
                .get("recipients").findValuesAsText("userId")).containsExactly("CU1");
    }
}
