package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.config.TransferTypeProperties;
import id.co.bni.direct.transaction.entity.TransferRows.BaseFtDomInsert;
import id.co.bni.direct.transaction.entity.TransferRows.BaseFtInsert;
import id.co.bni.direct.transaction.entity.TransferRows.BankLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.CorpContactRow;
import id.co.bni.direct.transaction.entity.TransferRows.UsageLockRow;
import id.co.bni.direct.transaction.entity.TransferRows.VaFtInsert;
import id.co.bni.direct.transaction.entity.SimsemRows.SimsemAccount;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ExecutionTaskRow;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.integration.CoreTransferClient.BiFastOutcome;
import id.co.bni.direct.transaction.integration.CoreTransferClient.InterbankOutcome;
import id.co.bni.direct.transaction.integration.CoreTransferClient.TransferOutcome;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.entity.ChargeRows;
import id.co.bni.direct.transaction.repository.mapper.ChargeMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.ChargeService;
import id.co.bni.direct.transaction.service.LimitService;
import id.co.bni.direct.transaction.service.ExecutionService;
import id.co.bni.direct.transaction.service.SimsemPool;
import id.co.bni.direct.transaction.service.TransferType;
import id.co.bni.direct.transaction.service.VaBill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * The real half of the execution seam: claim, limit usage, core transfer, BASE_FT,
 * verdict.
 *
 * <p><b>TRANSACTION BOUNDARIES</b> - three deliberate transactions (REQUIRES_NEW via
 * {@link TransactionTemplate}), because "roll everything back" is only the right answer
 * until the money may have moved. The caller must NOT hold an open transaction with the
 * task row locked (both call sites commit their workflow transaction first).
 *
 * <ol>
 * <li><b>TX-A, the claim.</b> Reads the task and moves READY_TO_EXECUTE to EXECUTING under
 *     the same optimistic-VERSION discipline as approve/reject; 0 rows means another
 *     executor won - the double-execute guard. Committed on its own BEFORE anything else,
 *     so a crash mid-execution leaves a visibly stuck EXECUTING task rather than one a
 *     retry would silently execute twice.</li>
 * <li><b>TX-B, the work.</b> Locks the two usage-tracked ceilings FOR UPDATE, re-validates
 *     them (funds may have moved between submit and release), increments them, calls core,
 *     and on success mints TRX_REF_NO and writes BASE_FT + the EXECUTED verdict - one
 *     commit covers usage, booking row and verdict. A ceiling breach never increments:
 *     the FAILED verdict is written inside TX-B and committed. A definite core refusal
 *     throws, rolling TX-B back - increments, ref-no counter and locks all undone in one
 *     move. A TIMEOUT does the opposite on purpose: the transfer may have happened, so
 *     the usage increments and the UNKNOWN verdict COMMIT together - usage stays counted
 *     until reconciliation says otherwise (the conservative direction for a limit).</li>
 * <li><b>TX-C, the failure verdict.</b> Only after TX-B rolled back: writes FAILED (or
 *     UNKNOWN when the transfer succeeded but the local bookkeeping then failed - the
 *     journal is preserved in the action NOTE) with the readable reason. Separate by
 *     necessity: the verdict must survive while the work must not.</li>
 * </ol>
 *
 * <p><b>P6, the two-leg flow.</b> A cross-currency Transfer ke Bank Lain (LLG/RTGS with
 * a valas source frozen on the V7 columns) cannot go straight out - kliring and RTGS are
 * IDR-only. It runs inside TX-B as two core calls through a simsem (holding) account
 * chosen from {@link SimsemPool}: leg 1 {@code DepTransferCross} customer valas account
 * into the simsem IDR account (amount + fee), leg 2 kliring/RTGS out of the simsem
 * account with the ordinary single-leg payload. Between them {@code markLeg1Done} is
 * COMMITTED in its own transaction, so the in-flight count and reconciliation see leg 1
 * even if this thread dies before leg 2 answers. Verdicts: leg 1 refused = FAILED (TX-B
 * rolled back, nothing moved); leg 1 UNKNOWN = UNKNOWN with the chosen account pinned;
 * leg 2 UNKNOWN = UNKNOWN + LEG1_DONE and NEVER a refund (the money may be on its way
 * to the other bank - {@code ReconciliationServiceImpl} owns it); leg 2 refused =
 * auto-refund from the simsem account (task 6.4): refund confirmed = FAILED +
 * REFUND_DONE with TX-B rolled back (usage undone, both journals in the note), refund
 * refused/UNKNOWN = UNKNOWN + REFUND_FAILED with usage kept (funds may be stranded in
 * the simsem account - the note says which).
 *
 * <p>Reconciliation of single-leg UNKNOWN tasks via direct-integration's
 * /transfers/status remains a documented TODO; the contract's rule that a timed-out
 * transfer is never resent is what this class enforces in the meantime.
 */
@Service
@Primary
public class CoreExecutionServiceImpl implements ExecutionService {

    /** Percentage arithmetic for the release-time re-quote tolerance. */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final Logger log = LoggerFactory.getLogger(CoreExecutionServiceImpl.class);

    /** TRX_TASK_ACTION.NOTE is NVARCHAR2(400). */
    private static final int NOTE_MAX = 400;

    private final TrxTaskMapper trxTaskMapper;
    private final ChargeMapper chargeMapper;
    private final ChargeService chargeService;
    private final LimitService limitService;
    private final TransferMapper transferMapper;
    private final CoreTransferClient coreTransferClient;
    private final TransferTypeProperties transferTypeProperties;
    private final SimsemPool simsemPool;
    private final SimsemRefunder simsemRefunder;
    private final TransactionTemplate ownTransaction;

    public CoreExecutionServiceImpl(TrxTaskMapper trxTaskMapper,
                                    ChargeMapper chargeMapper,
                                    ChargeService chargeService,
                                    LimitService limitService,
                                    TransferMapper transferMapper,
                                    CoreTransferClient coreTransferClient,
                                    TransferTypeProperties transferTypeProperties,
                                    SimsemPool simsemPool,
                                    SimsemRefunder simsemRefunder,
                                    PlatformTransactionManager transactionManager) {
        this.trxTaskMapper = trxTaskMapper;
        this.chargeMapper = chargeMapper;
        this.chargeService = chargeService;
        this.limitService = limitService;
        this.transferMapper = transferMapper;
        this.coreTransferClient = coreTransferClient;
        this.transferTypeProperties = transferTypeProperties;
        this.simsemPool = simsemPool;
        this.simsemRefunder = simsemRefunder;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ExecutionResult execute(String taskId) {
        if (!coreTransferClient.isEnabled()) {
            // No core stack (local dev). The task stays READY_TO_EXECUTE instead of
            // failing every release on a laptop; production always runs enabled.
            log.warn("Task {} left READY_TO_EXECUTE: the core-transfer hop is disabled.", taskId);
            return new ExecutionResult("READY_TO_EXECUTE",
                    "Eksekusi transfer belum aktif di lingkungan ini.");
        }

        // TX-A: the claim.
        ExecutionTaskRow task = ownTransaction.execute(tx -> claim(taskId));
        if (task == null) {
            // Another executor holds (or already finished) it - answer the live status.
            ExecutionTaskRow current = trxTaskMapper.findTaskForExecution(taskId);
            if (current == null) {
                throw new IllegalStateException("Task " + taskId + " does not exist.");
            }
            log.info("Task {} not claimable (status {}): double-execute guard.",
                    taskId, current.status());
            return new ExecutionResult(current.status(), null);
        }

        // The releaser executes; on the single-user path the maker stands in.
        String releaserId = trxTaskMapper.findReleaseActorId(taskId);
        String executedBy = releaserId != null ? releaserId : task.makerUserId();

        // TX-B: the work.
        try {
            return ownTransaction.execute(tx -> work(task, executedBy));
        } catch (CoreRefusedException e) {
            // TX-B rolled back: increments and ref-no counter undone. TX-C: the verdict,
            // plus (ONLINE) whatever trace the switch answered the refusal with.
            return ownTransaction.execute(tx -> {
                traceInterbank(task.id(), e.retrievalRefNo, e.responseCode);
                return fail(task, executedBy, "FAILED",
                        "Core banking menolak transaksi: " + e.getMessage());
            });
        } catch (TwoLegRefundedException e) {
            // P6: leg 2 was refused and the refund out of the simsem account landed. TX-B
            // rolled back (usage undone, nothing booked); TX-C writes FAILED and pins the
            // two-leg state - LEG1_DONE was committed separately and is now superseded.
            return ownTransaction.execute(tx -> {
                ExecutionResult verdict = fail(task, executedBy, "FAILED", e.getMessage());
                trxTaskMapper.updateTwoLegState(task.id(), "REFUND_DONE", executedBy);
                return verdict;
            });
        } catch (PostTransferException e) {
            // The money moved (we hold a journal) but committing the bookkeeping failed.
            // Never FAILED - the journal is preserved in the action note for the
            // reconciliation TODO.
            log.error("Task {}: transfer succeeded (journal {}) but local bookkeeping failed",
                    task.id(), e.coreJournal, e);
            return ownTransaction.execute(tx -> fail(task, executedBy, "UNKNOWN",
                    "Transfer terkirim (jurnal " + e.coreJournal
                            + ") tetapi pencatatan lokal gagal. Perlu rekonsiliasi."));
        } catch (RuntimeException e) {
            // Unexpected failure BEFORE the core call (the call itself never throws) -
            // TX-B rolled back, nothing moved.
            log.error("Task {}: execution failed before the core call", task.id(), e);
            return ownTransaction.execute(tx -> fail(task, executedBy, "FAILED",
                    "Kesalahan internal saat mengeksekusi transaksi."));
        }
    }

    /** TX-A body: read, guard, claim. Null when the claim is lost. */
    private ExecutionTaskRow claim(String taskId) {
        ExecutionTaskRow task = trxTaskMapper.findTaskForExecution(taskId);
        if (task == null) {
            throw new IllegalStateException("Task " + taskId + " does not exist.");
        }
        if (!"READY_TO_EXECUTE".equals(task.status()) && !"QUEUED".equals(task.status())) {
            // Terminal, still in workflow, or EXECUTING under another claim - either way
            // not ours to take. A redelivered Kafka event lands here and no-ops.
            return null;
        }
        return trxTaskMapper.claimExecution(taskId, task.version(), task.makerUserId()) == 1
                ? task : null;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** TX-B body. Throws {@link CoreRefusedException} to roll this transaction back. */
    private ExecutionResult work(ExecutionTaskRow task, String executedBy) {
        BigDecimal amount = task.trxAmt();
        // The DEBITED total: amount + the fee frozen at submit (null for in-house
        // tasks). P5: a cross task freezes the customer-typed debit amount and the
        // source currency on V7 columns - every ceiling re-check and usage increment
        // then sees THAT side, in THAT currency, mirroring the submit-time ladder.
        // The core call itself sends the frozen amounts, with the fee in its own field.
        BigDecimal debitSide = task.debitAmt() != null ? task.debitAmt() : amount;
        String limitCcy = task.debitCcyCd() != null ? task.debitCcyCd() : task.trxCcyCd();
        // P6: on a two-leg task the flat IDR fee is INSIDE the leg-1 credit the frozen
        // valas debit amount pays for (SimsemRefunder.leg1Credit), so the ceilings see
        // the debit amount alone - the same figure the submit ladder validated.
        // THE CHARGE at release. Three things decide whether it is added to the debited
        // total, and they are independent:
        //   - a BENEFICIARY charge never is; it comes off the credit side, so the source
        //     account is debited the principal alone;
        //   - a two-leg task never is; the charge already rides inside the leg-1 credit
        //     the frozen valas debit pays for, and adding it again would double-count it;
        //   - everything else adds the IDR charge total to an IDR debit.
        BigDecimal charge = chargeAtRelease(task, executedBy);
        boolean chargeOnBeneficiary = ChargeService.ChargeBearer.BENEFICIARY.name()
                .equalsIgnoreCase(task.chargeTo());
        BigDecimal totalDebit = isTwoLeg(task) || chargeOnBeneficiary
                ? debitSide : debitSide.add(charge);

        // Re-validate the two ceilings under FOR UPDATE before incrementing: usage moved
        // between submit and release. Only rows that exist bind - same resolution the
        // submit validation used.
        UsageLockRow corpLimit = transferMapper.lockCorpLimit(
                task.corpId(), task.srvcCd(), limitCcy);
        if (breaches(corpLimit, totalDebit)) {
            return fail(task, executedBy, "FAILED",
                    "Limit harian perusahaan tidak lagi mencukupi saat rilis.");
        }
        String makerGroupId = transferMapper.findUserGroupId(task.makerUserId());
        UsageLockRow groupLimit = makerGroupId == null ? null
                : transferMapper.lockGroupLimit(makerGroupId, task.srvcCd(), limitCcy);
        if (breaches(groupLimit, totalDebit)) {
            return fail(task, executedBy, "FAILED",
                    "Limit grup pengguna tidak lagi mencukupi saat rilis.");
        }

        // Decision A (2026-09-01): the BANK's per-transaction ceilings are re-read at release
        // so a limit the bank lowered while the task waited for approval wins over the value
        // the maker was validated against. The authorization STRUCTURE (matrix band, levels,
        // frozen candidates, maker scheme) deliberately stays as it was at submit - decision B.
        BankLimitRow bankLimit = transferMapper.findBankLimit(task.srvcCd(), limitCcy);
        if (bankLimit != null && (totalDebit.compareTo(nvl(bankLimit.minAmtLmt())) < 0
                || (bankLimit.maxAmtLmt() != null && totalDebit.compareTo(bankLimit.maxAmtLmt()) > 0))) {
            return fail(task, executedBy, "FAILED",
                    "Limit transaksi bank berubah sejak transaksi dibuat; nominal tidak lagi diizinkan.");
        }
        BigDecimal debitLimit = transferMapper.findAccountDebitLimit(task.corpId(), task.remAcctNo());
        if (debitLimit != null && totalDebit.compareTo(debitLimit) > 0) {
            return fail(task, executedBy, "FAILED",
                    "Limit debit rekening sumber berubah sejak transaksi dibuat; nominal tidak lagi diizinkan.");
        }

        // P6: a two-leg task needs a simsem account BEFORE anything is incremented - an
        // empty pool is a plain refusal with nothing to undo, never a core call.
        SimsemAccount simsem = null;
        if (isTwoLeg(task)) {
            simsem = simsemPool.select(task.srvcCd(), "IDR");
            if (simsem == null) {
                return fail(task, executedBy, "FAILED",
                        "Rekening simsem belum terdaftar untuk layanan ini.");
            }
        }

        // NO usage increment here any more. The daily ceilings were CONSUMED at submit, as
        // a reservation, so incrementing again at release would charge the company twice
        // for one transfer. What release still does is give the reservation BACK when the
        // instruction is refused - see fail(...) - while an UNKNOWN outcome keeps it,
        // because the money may already have moved and reconciliation has not said yet.

        // P2: the ONLINE (RTOL / ATM Bersama) type rides the interbank switch protocol,
        // whose verdict carries a trace (RRN, response code) instead of a core journal.
        if (TransferType.fromServiceCode(task.srvcCd()) == TransferType.ONLINE) {
            return interbankWork(task, executedBy, amount);
        }
        // P3: Transfer ke Virtual Account rides the VA billing service - its own wire and
        // its own legacy booking table; success is the service's journalNum, nothing else.
        if (TransferType.fromServiceCode(task.srvcCd()) == TransferType.VA) {
            return virtualAccountWork(task, executedBy, amount);
        }
        if (TransferType.fromServiceCode(task.srvcCd()) == TransferType.BIFAST) {
            return biFastWork(task, executedBy, amount);
        }
        if (simsem != null) {
            return twoLegWork(task, executedBy, amount, simsem);
        }

        TransferOutcome outcome = callCore(task, amount, task.remAcctNo());

        if (outcome.status() == CoreTransferClient.Status.REFUSED) {
            throw new CoreRefusedException(outcome.message());
        }
        if (outcome.status() == CoreTransferClient.Status.UNKNOWN) {
            // The transfer MAY have happened: commit the increments and the UNKNOWN
            // verdict together, store nothing core-side. Reconciliation is the TODO.
            return fail(task, executedBy, "UNKNOWN", outcome.message());
        }

        // SUCCESS. Everything after this point rides on money that has already moved -
        // a failure here must surface as UNKNOWN-with-journal, never as a plain rollback.
        try {
            String trxRefNo = TransferServiceImpl.nextRefNo(
                    transferMapper, task.srvcCd(), task.corpId());
            TransferType type = TransferType.fromServiceCode(task.srvcCd());
            if (type.isDomestic()) {
                transferMapper.insertBaseFtDom(new BaseFtDomInsert(
                        newId(), ftClass(type), task.menuCd(), task.srvcCd(), task.refNo(),
                        trxRefNo, task.remAcctNo(), task.benAcctNo(), task.benAcctNm(),
                        amount, task.benDomBnkId(), task.benAddr1(), task.benAddr2(),
                        task.benAddr3(), task.lldIsRemRes(), task.lldIsBenRes(),
                        task.benType(), task.benBnkBic(), task.makerUserId(), executedBy));
            } else {
                transferMapper.insertBaseFt(new BaseFtInsert(
                        newId(), task.menuCd(), task.srvcCd(), task.refNo(), trxRefNo,
                        task.remAcctNo(), task.benAcctNo(), task.benAcctNm(), amount,
                        task.trxCcyCd() != null ? task.trxCcyCd() : "IDR",
                        task.makerUserId(), executedBy));
            }
            trxTaskMapper.markExecuted(task.id(), outcome.coreJournal(), trxRefNo, executedBy);
            insertExecuteAction(task, executedBy,
                    "Transfer berhasil. coreJournal=" + outcome.coreJournal());
            log.info("Task {} EXECUTED: journal={} trxRefNo={}",
                    task.id(), outcome.coreJournal(), trxRefNo);
            return new ExecutionResult("EXECUTED", null);
        } catch (RuntimeException e) {
            throw new PostTransferException(outcome.coreJournal(), e);
        }
    }

    /**
     * The ONLINE tail of TX-B, entered after the shared ceiling re-checks and usage
     * increments. One - and only one - switch attempt off the frozen payload (bank code
     * = the 3-digit ONLINE_CD in BEN_BNK_CD; both reference fields carry the task's
     * REF_NO). The verdict handling mirrors the core path with one deliberate twist:
     * IN_PROCESS (responseCode 68) arrives from the client already as UNKNOWN and lands
     * exactly like a timeout - increments and the UNKNOWN verdict COMMIT together,
     * nothing retries, reconciliation owns the rest. The switch's trace is stored on
     * every outcome where it answered at all.
     */
    private ExecutionResult interbankWork(ExecutionTaskRow task, String executedBy,
                                          BigDecimal amount) {
        InterbankOutcome outcome = coreTransferClient.transferInterbank(
                new CoreTransferClient.InterbankInstruction(
                        task.remAcctNo(), task.benAcctNo(), task.benBnkCd(),
                        CoreTransferClient.amountString(amount),
                        task.refNo(), task.refNo()));
        if (outcome.status() == CoreTransferClient.Status.REFUSED) {
            throw new CoreRefusedException(outcome.message(),
                    outcome.retrievalRefNo(), outcome.responseCode());
        }
        if (outcome.status() == CoreTransferClient.Status.UNKNOWN) {
            traceInterbank(task.id(), outcome.retrievalRefNo(), outcome.responseCode());
            return fail(task, executedBy, "UNKNOWN", outcome.message());
        }
        // SUCCESS - same discipline as the core path: everything below rides on money
        // that has already moved, so a local failure surfaces as UNKNOWN-with-reference.
        try {
            String trxRefNo = TransferServiceImpl.nextRefNo(
                    transferMapper, task.srvcCd(), task.corpId());
            transferMapper.insertBaseFtDom(new BaseFtDomInsert(
                    newId(), ftClass(TransferType.ONLINE), task.menuCd(), task.srvcCd(),
                    task.refNo(), trxRefNo, task.remAcctNo(), task.benAcctNo(),
                    task.benAcctNm(), amount, task.benDomBnkId(), task.benAddr1(),
                    task.benAddr2(), task.benAddr3(), task.lldIsRemRes(),
                    task.lldIsBenRes(), task.benType(), null,
                    task.makerUserId(), executedBy));
            // No core journal exists on the switch protocol; the trace columns carry
            // the cross-network reference instead.
            trxTaskMapper.markExecuted(task.id(), null, trxRefNo, executedBy);
            traceInterbank(task.id(), outcome.retrievalRefNo(), outcome.responseCode());
            insertExecuteAction(task, executedBy,
                    "Transfer berhasil. retrievalRefNo=" + outcome.retrievalRefNo());
            log.info("Task {} EXECUTED (interbank): retrievalRefNo={} trxRefNo={}",
                    task.id(), outcome.retrievalRefNo(), trxRefNo);
            return new ExecutionResult("EXECUTED", null);
        } catch (RuntimeException e) {
            throw new PostTransferException(outcome.retrievalRefNo(), e);
        }
    }

    /**
     * The charge this release should book, in IDR.
     *
     * <p>Frozen at submit by default, and that default is the safe one: a task can wait
     * days for its approvals, and the figure the approvers signed off is the figure that
     * should be charged. {@code SYS_PARAM_CHARGE_FX_REQUOTE_ON_RELEASE} switches on a
     * re-quote for the foreign-currency components, for a bank that would rather the
     * customer paid today's rate.
     *
     * <p>Two guards make that switch safe to leave on. A rate that cannot be fetched
     * leaves the frozen figure in place - a released transfer must not fail because this
     * morning's rate has not landed. And a re-quote that moves the total further than
     * {@code SYS_PARAM_CHARGE_FX_REQUOTE_TOLERANCE_PCT} is refused rather than booked:
     * past that point the transfer being executed is not the one anybody approved.
     */
    private BigDecimal chargeAtRelease(ExecutionTaskRow task, String executedBy) {
        BigDecimal frozen = nvl(task.feeAmt());
        if (!chargeService.requoteOnRelease()) {
            return frozen;
        }
        List<ChargeRows.ChargeRow> rows = chargeMapper.findCharges(task.id());
        if (rows.isEmpty() || rows.stream().allMatch(
                r -> ChargeServiceImpl.BASELINE_CCY.equalsIgnoreCase(r.ccyCd()))) {
            return frozen;
        }
        List<ChargeService.ChargeComponent> components = rows.stream()
                .map(r -> new ChargeService.ChargeComponent(r.seqNo(), r.chTypCd(), r.chTypNm(),
                        r.ccyCd(), r.amt(), r.idrAmt(), r.fxRate(), r.fxRateType(),
                        r.fxRateSide(), r.tariffSource()))
                .toList();
        ChargeService.ChargeQuote requoted;
        try {
            requoted = chargeService.requote(components);
        } catch (RuntimeException e) {
            log.warn("Task {}: charge re-quote failed ({}); keeping the approved charge",
                    task.id(), e.getMessage());
            return frozen;
        }
        BigDecimal drift = requoted.totalIdr().subtract(frozen).abs();
        if (frozen.signum() > 0) {
            BigDecimal driftPct = drift.multiply(HUNDRED).divide(frozen, 4, RoundingMode.HALF_UP);
            if (driftPct.compareTo(chargeService.requoteTolerancePercent()) > 0) {
                throw new CoreRefusedException(
                        "Biaya berubah dari " + frozen.toPlainString() + " menjadi "
                                + requoted.totalIdr().toPlainString()
                                + " karena perubahan kurs; transaksi perlu diajukan ulang.");
            }
        }
        for (ChargeService.ChargeComponent c : requoted.components()) {
            chargeMapper.updateRequote(task.id(), c.seqNo(), c.idrAmt(), c.fxRate());
        }
        log.info("Task {} charge re-quoted {} -> {} by {}", task.id(), frozen,
                requoted.totalIdr(), executedBy);
        return requoted.totalIdr();
    }

    /**
     * A task is two-leg when it is an LLG/RTGS transfer whose frozen debit currency is
     * not IDR. ONLINE never is (no simsem route), in-house never is (P5 handles cross
     * in one DepTransferCross), and a same-currency domestic task has no debit block.
     */
    public static boolean isTwoLeg(ExecutionTaskRow task) {
        TransferType type = TransferType.fromServiceCode(task.srvcCd());
        return (type == TransferType.LLG || type == TransferType.RTGS)
                && task.debitCcyCd() != null
                && !"IDR".equalsIgnoreCase(task.debitCcyCd());
    }

    /**
     * The P6 tail of TX-B, entered after the ceiling re-checks and usage increments with
     * the pool account already chosen. See the class comment for the verdict table.
     */
    private ExecutionResult twoLegWork(ExecutionTaskRow task, String executedBy,
                                       BigDecimal amount, SimsemAccount simsem) {
        String simsemAcct = simsem.accountNo();
        // Pin the chosen account before leg 1 leaves - committed on its own, so a leg-1
        // timeout still records WHICH holding account may hold the money.
        ownTransaction.execute(tx -> trxTaskMapper.markSimsemSelected(task.id(), simsemAcct, executedBy));

        // LEG 1: customer valas account -> simsem IDR account (amount + fee), off the
        // frozen debit amount and rate type. One attempt.
        BigDecimal leg1Credit = SimsemRefunder.leg1Credit(task);
        TransferOutcome leg1 = coreTransferClient.transferCrossCurrency(
                new CoreTransferClient.CrossCurrencyInstruction(
                        task.remAcctNo(),
                        new CoreTransferClient.Money(
                                CoreTransferClient.amountString(task.debitAmt()), task.debitCcyCd()),
                        simsemAcct,
                        new CoreTransferClient.Money(
                                CoreTransferClient.amountString(leg1Credit), "IDR"),
                        leg1Credit.stripTrailingZeros().toPlainString(),
                        task.rateType(),
                        SimsemRefunder.narrative(task),
                        null));
        if (leg1.status() == CoreTransferClient.Status.REFUSED) {
            throw new CoreRefusedException(leg1.message());
        }
        if (leg1.status() == CoreTransferClient.Status.UNKNOWN) {
            // Money MAY be in the simsem account. Usage stays counted, state stays NONE
            // (leg 1 unconfirmed), the pinned account tells reconciliation where to look.
            return fail(task, executedBy, "UNKNOWN",
                    "Leg 1 (pemindahan ke rekening simsem " + simsemAcct
                            + ") tidak dapat dipastikan: " + leg1.message()
                            + " Perlu rekonsiliasi.");
        }

        // Leg 1 confirmed: LEG1_DONE + both identifiers, COMMITTED before leg 2 is sent.
        ownTransaction.execute(tx -> trxTaskMapper.markLeg1Done(
                task.id(), simsemAcct, leg1.coreJournal(), executedBy));
        log.info("Task {} leg 1 done: simsem={} journal={} credit={} IDR",
                task.id(), simsemAcct, leg1.coreJournal(), leg1Credit);

        // LEG 2: the ordinary kliring/RTGS instruction, sent FROM the simsem account.
        TransferOutcome leg2 = callCore(task, amount, simsemAcct);
        if (leg2.status() == CoreTransferClient.Status.UNKNOWN) {
            // NEVER a refund here: leg 2 may be on its way to the other bank.
            return fail(task, executedBy, "UNKNOWN",
                    "Leg 2 (transfer keluar dari rekening simsem " + simsemAcct
                            + ") tidak dapat dipastikan: " + leg2.message()
                            + " Dana berada di rekening simsem (jurnal leg 1 "
                            + leg1.coreJournal() + "). Perlu rekonsiliasi.");
        }
        if (leg2.status() == CoreTransferClient.Status.REFUSED) {
            // Definite refusal: the money is provably still in the simsem account.
            // Auto-refund (task 6.4), one attempt.
            TransferOutcome refund = simsemRefunder.refund(task, simsemAcct, leg1.coreJournal());
            if (refund.status() == CoreTransferClient.Status.SUCCESS) {
                throw new TwoLegRefundedException(
                        "Core banking menolak leg 2: " + nonNull(leg2.message())
                                + " Dana dikembalikan dari rekening simsem " + simsemAcct
                                + " ke rekening sumber. journalLeg1=" + leg1.coreJournal()
                                + " journalRefund=" + refund.coreJournal());
            }
            // Refund refused or unknown: funds may be stranded. Commit TX-B (usage kept -
            // the conservative direction) with UNKNOWN + REFUND_FAILED.
            ExecutionResult verdict = fail(task, executedBy, "UNKNOWN",
                    "Core banking menolak leg 2: " + nonNull(leg2.message())
                            + " Pengembalian dana dari rekening simsem " + simsemAcct
                            + " GAGAL/tidak pasti: " + nonNull(refund.message())
                            + " Dana mungkin tertahan di rekening simsem; perlu penanganan manual."
                            + " journalLeg1=" + leg1.coreJournal());
            trxTaskMapper.updateTwoLegState(task.id(), "REFUND_FAILED", executedBy);
            return verdict;
        }

        // Leg 2 SUCCESS - same discipline as the single-leg path: everything below rides
        // on money that has already moved, so a local failure surfaces as
        // UNKNOWN-with-journal (state stays LEG1_DONE; reconciliation finds leg 2 landed).
        try {
            String trxRefNo = TransferServiceImpl.nextRefNo(
                    transferMapper, task.srvcCd(), task.corpId());
            TransferType type = TransferType.fromServiceCode(task.srvcCd());
            transferMapper.insertBaseFtDom(new BaseFtDomInsert(
                    newId(), ftClass(type), task.menuCd(), task.srvcCd(), task.refNo(),
                    trxRefNo, task.remAcctNo(), task.benAcctNo(), task.benAcctNm(),
                    amount, task.benDomBnkId(), task.benAddr1(), task.benAddr2(),
                    task.benAddr3(), task.lldIsRemRes(), task.lldIsBenRes(),
                    task.benType(), task.benBnkBic(), task.makerUserId(), executedBy,
                    simsemAcct, leg1.coreJournal()));
            trxTaskMapper.markExecuted(task.id(), leg2.coreJournal(), trxRefNo, executedBy);
            trxTaskMapper.updateTwoLegState(task.id(), "LEG2_DONE", executedBy);
            insertExecuteAction(task, executedBy,
                    "Transfer berhasil (2 leg via simsem " + simsemAcct + "). coreJournal="
                            + leg2.coreJournal() + " journalLeg1=" + leg1.coreJournal());
            log.info("Task {} EXECUTED (two-leg): simsem={} leg1={} leg2={} trxRefNo={}",
                    task.id(), simsemAcct, leg1.coreJournal(), leg2.coreJournal(), trxRefNo);
            return new ExecutionResult("EXECUTED", null);
        } catch (RuntimeException e) {
            throw new PostTransferException(leg2.coreJournal(), e);
        }
    }

    /**
     * The VA tail of TX-B (P3), entered after the shared ceiling re-checks and usage
     * increments. One - and only one - billing-payment attempt off the frozen task: the
     * VA number, the amount the maker typed, and the inquiryRequestId the maker's
     * inquiry answered. The verdict handling is the single-leg core path's: a refusal
     * rolls TX-B back (usage undone), an unanswered call commits UNKNOWN with usage
     * kept. On success the legacy record goes to VIRTUAL_ACCOUNT_FT - legacy never wrote
     * a VA payment to BASE_FT - and the journalNum to TRX_TASK.CORE_JOURNAL (the legacy
     * VA table has no journal column).
     */
    private ExecutionResult virtualAccountWork(ExecutionTaskRow task, String executedBy,
                                               BigDecimal amount) {
        TransferOutcome outcome = coreTransferClient.transferVa(
                new CoreTransferClient.VaInstruction(
                        task.refNo(), task.remAcctNo(), task.benAcctNo(),
                        CoreTransferClient.amountString(amount), task.vaInquiryReqId()));
        if (outcome.status() == CoreTransferClient.Status.REFUSED) {
            throw new CoreRefusedException(outcome.message());
        }
        if (outcome.status() == CoreTransferClient.Status.UNKNOWN) {
            return fail(task, executedBy, "UNKNOWN", outcome.message());
        }
        // SUCCESS - everything below rides on money that has already moved, so a local
        // failure surfaces as UNKNOWN-with-journal, never as a plain rollback.
        try {
            String trxRefNo = TransferServiceImpl.nextRefNo(
                    transferMapper, task.srvcCd(), task.corpId());
            BigDecimal fee = nvl(task.feeAmt());
            // The legacy row carries the presentation block the inquiry answered (V11),
            // verbatim - labels, display values, the open/fixed flag, the VA service's
            // ids. A task without the block (pre-V11, or an FE that did not echo it)
            // books the constants the legacy sample rows carry.
            VaBill bill = VaBill.fromJson(task.vaBillJson());
            transferMapper.insertVirtualAccountFt(new VaFtInsert(
                    newId(), task.corpId(), task.benAcctNo(),
                    task.trxCcyCd() != null ? task.trxCcyCd() : "IDR",
                    amount.add(fee), task.benAcctNm(),
                    amount,
                    bill != null && notBlank(bill.billedAmountValue()) ? bill.billedAmountValue() : rupiah(amount),
                    fee,
                    bill != null && notBlank(bill.feeAmountValue()) ? bill.feeAmountValue() : rupiah(fee),
                    task.remAcctNo(), task.refNo(), trxRefNo,
                    trimTo(task.remark1(), 40),
                    task.makerUserId(), executedBy,
                    bill != null && notBlank(bill.billingLabel()) ? trimTo(bill.billingLabel(), 100) : "No.VA",
                    bill != null && notBlank(bill.vaNameLabel()) ? trimTo(bill.vaNameLabel(), 100) : "Nama",
                    bill != null && notBlank(bill.trxType()) ? trimTo(bill.trxType(), 10) : VaBill.TRX_TYPE_OPEN,
                    bill != null && notBlank(bill.billedAmountLabel()) ? trimTo(bill.billedAmountLabel(), 100) : "Nominal",
                    bill != null && notBlank(bill.feeAmountLabel()) ? trimTo(bill.feeAmountLabel(), 100) : "Biaya admin",
                    bill != null ? trimTo(bill.accountNumberTo(), 40) : null,
                    bill != null ? trimTo(bill.additionalLabel1(), 200) : null,
                    bill != null ? trimTo(bill.additionalLabel2(), 200) : null,
                    bill != null ? trimTo(bill.additionalLabel3(), 200) : null,
                    bill != null ? trimTo(bill.additionalValue1(), 200) : null,
                    bill != null ? trimTo(bill.additionalValue2(), 200) : null,
                    bill != null ? trimTo(bill.additionalValue3(), 200) : null,
                    bill != null ? trimTo(bill.trxId(), 100) : null));
            trxTaskMapper.markExecuted(task.id(), outcome.coreJournal(), trxRefNo, executedBy);
            insertExecuteAction(task, executedBy,
                    "Pembayaran Virtual Account berhasil. journalNum=" + outcome.coreJournal());
            log.info("Task {} EXECUTED (virtual account): journalNum={} trxRefNo={}",
                    task.id(), outcome.coreJournal(), trxRefNo);
            return new ExecutionResult("EXECUTED", null);
        } catch (RuntimeException e) {
            throw new PostTransferException(outcome.coreJournal(), e);
        }
    }

    /** The legacy VA table's display figure ("Rp0", "Rp10000") - a label, not a number. */
    /**
     * The BI-Fast tail of TX-B (P7), entered after the shared ceiling re-checks and usage
     * increments. One - and only one - credit-transfer off the frozen payload: the
     * participant BIC in BEN_BNK_CD, the flat fee, the purpose and the creditor block
     * the inquiry answered (V10), the proxy route when one was used. SUCCESS books the
     * legacy BASE_FT row with the BI-Fast columns (purpose, beneficiary type, proxy,
     * trxId, endToEndId) and pins the switch identifiers on the task next to the
     * journal; REFUSED rolls TX-B back; UNKNOWN keeps the usage counted like every
     * other money-moving hop.
     */
    private ExecutionResult biFastWork(ExecutionTaskRow task, String executedBy,
                                       BigDecimal amount) {
        String narrative = notBlank(task.remark1()) ? task.remark1().trim() : task.refNo();
        if (narrative.length() > 50) {
            narrative = narrative.substring(0, 50);
        }
        BiFastOutcome outcome = coreTransferClient.transferBiFast(
                new CoreTransferClient.BiFastInstruction(
                        task.refNo(), task.remAcctNo(), task.benAcctNo(),
                        CoreTransferClient.amountString(amount),
                        CoreTransferClient.amountString(nvl(task.feeAmt())),
                        task.benBnkCd(), task.benAcctNm(),
                        task.bifastCredId(), task.bifastCredType(), task.bifastCredAcctType(),
                        task.bifastCredRsdntSts(), task.bifastCredTown(),
                        task.proxyType(), task.proxyId(),
                        narrative, task.bifastSettlementDt(), task.bifastPurposeCd()));
        if (outcome.status() == CoreTransferClient.Status.REFUSED) {
            throw new CoreRefusedException(outcome.message());
        }
        if (outcome.status() == CoreTransferClient.Status.UNKNOWN) {
            traceBiFast(task.id(), outcome, executedBy);
            return fail(task, executedBy, "UNKNOWN", outcome.message());
        }
        try {
            String trxRefNo = TransferServiceImpl.nextRefNo(
                    transferMapper, task.srvcCd(), task.corpId());
            transferMapper.insertBaseFtDom(new BaseFtDomInsert(
                    newId(), ftClass(TransferType.BIFAST), task.menuCd(), task.srvcCd(),
                    task.refNo(), trxRefNo, task.remAcctNo(), task.benAcctNo(),
                    task.benAcctNm(), amount, task.benDomBnkId(), null, null, null,
                    null, null, null, task.benBnkBic(), task.makerUserId(), executedBy,
                    null, null,
                    task.bifastPurposeCd(), task.bifastCredType(), task.proxyId(),
                    task.proxyType(), outcome.trxId(), outcome.endToEndId()));
            trxTaskMapper.markExecuted(task.id(), outcome.coreJournal(), trxRefNo, executedBy);
            traceBiFast(task.id(), outcome, executedBy);
            insertExecuteAction(task, executedBy,
                    "Transfer BI-Fast berhasil. depositJournal=" + outcome.coreJournal()
                            + " trxId=" + outcome.trxId() + " endToEndId=" + outcome.endToEndId());
            log.info("Task {} EXECUTED (BI-Fast): journal={} trxId={} endToEndId={} trxRefNo={}",
                    task.id(), outcome.coreJournal(), outcome.trxId(), outcome.endToEndId(), trxRefNo);
            return new ExecutionResult("EXECUTED", null);
        } catch (RuntimeException e) {
            throw new PostTransferException(outcome.coreJournal(), e);
        }
    }

    /** Stores the switch identifiers when it answered them at all; both-null is a no-op. */
    private void traceBiFast(String taskId, BiFastOutcome outcome, String executedBy) {
        if (outcome.trxId() == null && outcome.endToEndId() == null) {
            return;
        }
        trxTaskMapper.updateBiFastResult(taskId, outcome.trxId(), outcome.endToEndId(), executedBy);
    }

    private static String rupiah(BigDecimal amount) {
        return "Rp" + nvl(amount).stripTrailingZeros().toPlainString();
    }

    private static String trimTo(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static String nonNull(String message) {
        return message != null ? message : "tanpa pesan.";
    }

    /** Stores the switch's trace when it answered at all; both-null is a silent no-op. */
    private void traceInterbank(String taskId, String retrievalRefNo, String responseCode) {
        if (retrievalRefNo == null && responseCode == null) {
            return;
        }
        trxTaskMapper.updateInterbankResult(taskId, retrievalRefNo, responseCode);
    }

    /**
     * The one core attempt, routed by the task's resolved service code (task 1.6): LLG
     * and RTGS go to direct-integration's kliring/rtgs endpoints, everything else stays
     * on the P0 in-house call. The instruction is built ENTIRELY from the frozen task
     * payload plus the corporate's own master data (the sender block - kliring mandates
     * sender address/phone, so missing CORP data falls back to "-" rather than failing a
     * released transfer) and config constants (TSA codes, intermediary branch). Nothing
     * is re-derived from live reference data: what the approver saw is what executes.
     * All three paths share the same UNKNOWN-on-timeout, never-retried semantics.
     * {@code fromAccount} is the customer's account on a single-leg transfer and the
     * simsem account on leg 2 of a two-leg one (P6); the sender block stays the
     * corporate's either way - the customer is the sender, the simsem account only
     * holds the money.
     */
    private TransferOutcome callCore(ExecutionTaskRow task, BigDecimal amount, String fromAccount) {
        TransferType type = TransferType.fromServiceCode(task.srvcCd());
        if (!type.isDomestic()) {
            return callInHouse(task, amount);
        }
        CorpContactRow corp = transferMapper.findCorpContact(task.corpId());
        String senderName = corp != null && notBlank(corp.nm()) ? corp.nm() : task.corpId();
        // narrative1 is mandatory upstream; the reference number stands in for a
        // remark-less instruction.
        String narrative = notBlank(task.remark1()) ? task.remark1().trim() : task.refNo();
        if (narrative.length() > 50) {
            narrative = narrative.substring(0, 50);
        }
        String amountStr = CoreTransferClient.amountString(amount);
        String feeStr = CoreTransferClient.amountString(nvl(task.feeAmt()));
        if (type == TransferType.LLG) {
            return coreTransferClient.transferKliring(new CoreTransferClient.KliringInstruction(
                    fromAccount, amountStr, feeStr,
                    narrative, null,
                    task.benAcctNm(),
                    task.benAddr1(), task.benAddr2(),
                    task.benPhone(), task.benPostalCd(),
                    task.benIdNo(), task.benIdType(),
                    senderName,
                    corp != null && notBlank(corp.addr1()) ? corp.addr1() : "-",
                    corp != null && notBlank(corp.phoneNo()) ? corp.phoneNo() : "-",
                    task.lldIsRemRes(), task.lldIsBenRes(),
                    task.benBnkCd(),
                    task.benAcctNo(),
                    task.benType(),
                    transferTypeProperties.getIntermediaryBranch(),
                    task.benBnkBic(),
                    transferTypeProperties.getLlg().getTsaCode()));
        }
        return coreTransferClient.transferRtgs(new CoreTransferClient.RtgsInstruction(
                fromAccount, amountStr, feeStr,
                narrative, null, null,
                task.benAcctNm(),
                task.benAddr1(), task.benAddr2(),
                task.benPhone(), task.benPostalCd(),
                task.benIdNo(), task.benIdType(),
                senderName, null,
                task.lldIsRemRes(), task.lldIsBenRes(),
                task.benBnkCd(),
                task.benAcctNo(),
                transferTypeProperties.getIntermediaryBranch(),
                transferTypeProperties.getRtgs().getTsaCode()));
    }

    /**
     * The in-house (Transfer ke BNI) routing per the frozen V7 block (task 5.4): LON
     * source -> LoanTransfer (each leg carries its own currency/amount, core banking
     * owns the rate arithmetic); cross deposit source -> DepTransferCross with the
     * SUBMIT-TIME baseAmount (never re-quoted here - what the approver saw is what
     * executes); everything else stays on the P0 same-currency call. One attempt each,
     * UNKNOWN-on-timeout identical.
     */
    private TransferOutcome callInHouse(ExecutionTaskRow task, BigDecimal amount) {
        String creditCcy = task.trxCcyCd();
        String debitCcy = task.debitCcyCd() != null ? task.debitCcyCd() : creditCcy;
        BigDecimal debitAmt = task.debitAmt() != null ? task.debitAmt() : amount;
        if ("LON".equalsIgnoreCase(task.sourceProductType())) {
            return coreTransferClient.transferLoan(new CoreTransferClient.LoanInstruction(
                    task.remAcctNo(), debitCcy,
                    CoreTransferClient.amountString(debitAmt),
                    task.benAcctNo(), creditCcy,
                    CoreTransferClient.amountString(amount),
                    trimNarrative(task.remark1()),
                    task.rateType()));
        }
        if (task.debitCcyCd() != null && !task.debitCcyCd().equals(creditCcy)) {
            return coreTransferClient.transferCrossCurrency(
                    new CoreTransferClient.CrossCurrencyInstruction(
                            task.remAcctNo(),
                            new CoreTransferClient.Money(
                                    CoreTransferClient.amountString(debitAmt), debitCcy),
                            task.benAcctNo(),
                            new CoreTransferClient.Money(
                                    CoreTransferClient.amountString(amount), creditCcy),
                            task.baseAmt() != null
                                    ? task.baseAmt().stripTrailingZeros().toPlainString()
                                    : null,
                            task.rateType(),
                            trimNarrative(task.remark1()),
                            null));
        }
        return coreTransferClient.transfer(
                task.remAcctNo(), task.benAcctNo(), amount, creditCcy, task.remark1());
    }

    /** The 50-char narrative cap the transfer contracts share; null when blank. */
    private static String trimNarrative(String narrative) {
        if (narrative == null || narrative.isBlank()) {
            return null;
        }
        String trimmed = narrative.trim();
        return trimmed.length() > 50 ? trimmed.substring(0, 50) : trimmed;
    }

    /**
     * The legacy CLASS discriminator per product. InHouseFT is verified against live
     * rows; the DEV copy holds NO legacy LLG/RTGS BASE_FT row to verify these two
     * against (flagged in the P1 roadmap notes), so they follow the in-house naming
     * pattern - correct here first if a real legacy row ever shows another spelling.
     */
    private static String ftClass(TransferType type) {
        return switch (type) {
            case LLG -> "com.aprisma.product.gcm.common.model.LLGFT";
            case RTGS -> "com.aprisma.product.gcm.common.model.RTGSFT";
            case ONLINE -> "com.aprisma.product.gcm.common.model.OnlineFT";
            // By naming convention with its siblings: no BI-Fast BASE_FT row exists in the
            // seed extracts to copy the class from (flagged in the P7 notes).
            case BIFAST -> "com.aprisma.product.gcm.common.model.BIFastFT";
            default -> "com.aprisma.product.gcm.common.model.InHouseFT";
        };
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Writes a non-success verdict and its EXECUTE action row in the CURRENT transaction.
     * Used inside TX-B (breach, timeout - commits with whatever TX-B holds) and as the
     * whole body of TX-C (after a rollback).
     */
    private ExecutionResult fail(ExecutionTaskRow task, String executedBy,
                                 String status, String reason) {
        trxTaskMapper.markExecutionOutcome(task.id(), status, executedBy);
        insertExecuteAction(task, executedBy, reason);
        releaseReservation(task, status);
        log.warn("Task {} landed {}: {}", task.id(), status, reason);
        return new ExecutionResult(status, reason);
    }

    /**
     * Give the daily ceiling back, but ONLY on a verdict that says the money did not move.
     *
     * <p>{@code FAILED} means core banking refused, so nothing was spent and the company
     * gets its headroom back. {@code UNKNOWN} means nobody knows yet: the instruction may
     * well have gone through, and handing the ceiling back would let the customer send the
     * same amount a second time. So an UNKNOWN task KEEPS its reservation until
     * reconciliation settles it - which is the decision recorded for this path.
     */
    private void releaseReservation(ExecutionTaskRow task, String status) {
        if (!"FAILED".equals(status)
                || task.lmtSrvcCcyMtrxId() == null || task.lmtReservedAmt() == null) {
            return;
        }
        limitService.release(task.corpId(), transferMapper.findUserGroupId(task.makerUserId()),
                task.srvcCd(), new LimitService.Reservation(task.lmtSrvcCcyMtrxId(),
                        task.lmtCcyMtrxCd(), task.lmtCcyCd(), task.lmtReservedAmt()));
    }

    private void insertExecuteAction(ExecutionTaskRow task, String executedBy, String note) {
        String trimmed = note != null && note.length() > NOTE_MAX
                ? note.substring(0, NOTE_MAX) : note;
        trxTaskMapper.insertAction(new ActionInsert(
                newId(), task.id(), null, "EXECUTE",
                executedBy, null, null, null, trimmed));
    }

    private static boolean breaches(UsageLockRow limit, BigDecimal amount) {
        if (limit == null || limit.maxAmtLmt() == null) {
            return false;
        }
        BigDecimal usage = limit.amtLmtUsage() != null ? limit.amtLmtUsage() : BigDecimal.ZERO;
        return usage.add(amount).compareTo(limit.maxAmtLmt()) > 0;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Core banking (or the interbank switch) definitely said no - thrown to roll TX-B
     * back. The optional trace fields carry the switch's answer on the ONLINE path so
     * TX-C can persist them after the rollback.
     */
    private static final class CoreRefusedException extends RuntimeException {
        final String retrievalRefNo;
        final String responseCode;

        CoreRefusedException(String message) {
            this(message, null, null);
        }

        CoreRefusedException(String message, String retrievalRefNo, String responseCode) {
            super(message != null ? message : "tanpa pesan");
            this.retrievalRefNo = retrievalRefNo;
            this.responseCode = responseCode;
        }
    }

    /**
     * P6: leg 2 was refused and the refund out of the simsem account landed - thrown to
     * roll TX-B back (usage undone) so TX-C can write FAILED + REFUND_DONE with the
     * readable reason carrying both journals.
     */
    private static final class TwoLegRefundedException extends RuntimeException {
        TwoLegRefundedException(String message) {
            super(message);
        }
    }

    /** The transfer succeeded but the local bookkeeping did not commit. */
    private static final class PostTransferException extends RuntimeException {
        final String coreJournal;

        PostTransferException(String coreJournal, Throwable cause) {
            super(cause);
            this.coreJournal = coreJournal;
        }
    }
}
