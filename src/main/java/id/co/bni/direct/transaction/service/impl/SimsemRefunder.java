package id.co.bni.direct.transaction.service.impl;

import java.math.BigDecimal;

import id.co.bni.direct.transaction.entity.TrxTaskRows.ExecutionTaskRow;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.integration.CoreTransferClient.TransferOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The P6 auto-refund (task 6.4) and the two-leg arithmetic both the execution path and
 * the reconciliation path share, so the two never disagree about what leg 1 moved.
 *
 * <p><b>What leg 1 credits.</b> Kliring/RTGS debit the outward amount AND the flat fee
 * from their {@code fromAccount}. In a two-leg flow that account is the simsem account,
 * so leg 1 must make it whole: it credits {@code trxAmt + feeAmt} in IDR (the
 * {@link #leg1Credit(ExecutionTaskRow) leg-1 credit}) against the customer-typed debit
 * amount in the source currency frozen on the task at submit. Both figures are frozen,
 * so a reconciliation hours later refunds exactly what leg 1 took.
 *
 * <p><b>The refund is the reverse leg 1.</b> One {@code DepTransferCross} from the simsem
 * account back to the customer's account: debit the leg-1 IDR credit, credit the frozen
 * source-currency amount, at the task's frozen rate type. Stated assumption (no koreksi
 * operation exists in the spec extract this service was built from): core banking may
 * apply the day's rate to the reverse direction, so the valas amount that lands can differ
 * from what was debited by the rate spread; the narrative and {@code narrativeExt} name the
 * task and leg 1's journal so operations can trace and correct it. ONE attempt, never
 * retried - the refund is money-moving like every other write.
 */
@Service
public class SimsemRefunder {

    private static final Logger log = LoggerFactory.getLogger(SimsemRefunder.class);

    /** The narrative cap the transfer contracts share. */
    private static final int NARRATIVE_MAX = 50;

    private final CoreTransferClient coreTransferClient;

    public SimsemRefunder(CoreTransferClient coreTransferClient) {
        this.coreTransferClient = coreTransferClient;
    }

    /**
     * Leg 1's IDR credit into the simsem account: the outward amount plus the flat fee
     * leg 2 debits from that same account.
     */
    public static BigDecimal leg1Credit(ExecutionTaskRow task) {
        BigDecimal fee = task.feeAmt() != null ? task.feeAmt() : BigDecimal.ZERO;
        return task.trxAmt().add(fee);
    }

    /** The task's narrative for core banking: the remark, else the reference number, capped. */
    public static String narrative(ExecutionTaskRow task) {
        String text = task.remark1() != null && !task.remark1().isBlank()
                ? task.remark1().trim() : task.refNo();
        return cap(text);
    }

    static String cap(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > NARRATIVE_MAX ? text.substring(0, NARRATIVE_MAX) : text;
    }

    /**
     * One attempt to return leg 1's money from the simsem account to the customer. The
     * verdict vocabulary is the client's: SUCCESS (refund landed, journal in hand),
     * REFUSED (core banking said no - the funds are still in the simsem account), UNKNOWN
     * (nobody can say - never resent).
     */
    public TransferOutcome refund(ExecutionTaskRow task, String simsemAcctNo, String leg1Journal) {
        BigDecimal idr = leg1Credit(task);
        log.warn("Task {}: refunding leg 1 ({} IDR, journal {}) from simsem {} to {}",
                task.id(), idr, leg1Journal, simsemAcctNo, task.remAcctNo());
        return coreTransferClient.transferCrossCurrency(
                new CoreTransferClient.CrossCurrencyInstruction(
                        simsemAcctNo,
                        new CoreTransferClient.Money(CoreTransferClient.amountString(idr), "IDR"),
                        task.remAcctNo(),
                        new CoreTransferClient.Money(
                                CoreTransferClient.amountString(task.debitAmt()), task.debitCcyCd()),
                        idr.stripTrailingZeros().toPlainString(),
                        task.rateType(),
                        cap("REFUND " + task.refNo()),
                        leg1Journal != null ? cap("KOREKSI JRN " + leg1Journal) : null));
    }
}
