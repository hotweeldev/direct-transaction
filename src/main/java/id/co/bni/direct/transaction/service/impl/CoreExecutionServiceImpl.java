package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.entity.TransferRows.BaseFtInsert;
import id.co.bni.direct.transaction.entity.TransferRows.BankLimitRow;
import id.co.bni.direct.transaction.entity.TransferRows.UsageLockRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ExecutionTaskRow;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.integration.CoreTransferClient.TransferOutcome;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
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
 * <p>Reconciliation of UNKNOWN tasks via direct-integration's /transfers/status is a
 * documented TODO - deliberately not built here; the contract's rule that a timed-out
 * transfer is never resent is what this class enforces in the meantime.
 */
@Service
@Primary
public class CoreExecutionServiceImpl implements ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CoreExecutionServiceImpl.class);

    /** TRX_TASK_ACTION.NOTE is NVARCHAR2(400). */
    private static final int NOTE_MAX = 400;

    private final TrxTaskMapper trxTaskMapper;
    private final TransferMapper transferMapper;
    private final CoreTransferClient coreTransferClient;
    private final TransactionTemplate ownTransaction;

    public CoreExecutionServiceImpl(TrxTaskMapper trxTaskMapper,
                                    TransferMapper transferMapper,
                                    CoreTransferClient coreTransferClient,
                                    PlatformTransactionManager transactionManager) {
        this.trxTaskMapper = trxTaskMapper;
        this.transferMapper = transferMapper;
        this.coreTransferClient = coreTransferClient;
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
            // TX-B rolled back: increments and ref-no counter undone. TX-C: the verdict.
            return ownTransaction.execute(tx -> fail(task, executedBy, "FAILED",
                    "Core banking menolak transaksi: " + e.getMessage()));
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

        // Re-validate the two ceilings under FOR UPDATE before incrementing: usage moved
        // between submit and release. Only rows that exist bind - same resolution the
        // submit validation used.
        UsageLockRow corpLimit = transferMapper.lockCorpLimit(
                task.corpId(), task.srvcCd(), task.trxCcyCd());
        if (breaches(corpLimit, amount)) {
            return fail(task, executedBy, "FAILED",
                    "Limit harian perusahaan tidak lagi mencukupi saat rilis.");
        }
        String makerGroupId = transferMapper.findUserGroupId(task.makerUserId());
        UsageLockRow groupLimit = makerGroupId == null ? null
                : transferMapper.lockGroupLimit(makerGroupId, task.srvcCd(), task.trxCcyCd());
        if (breaches(groupLimit, amount)) {
            return fail(task, executedBy, "FAILED",
                    "Limit grup pengguna tidak lagi mencukupi saat rilis.");
        }

        // Decision A (2026-09-01): the BANK's per-transaction ceilings are re-read at release
        // so a limit the bank lowered while the task waited for approval wins over the value
        // the maker was validated against. The authorization STRUCTURE (matrix band, levels,
        // frozen candidates, maker scheme) deliberately stays as it was at submit - decision B.
        BankLimitRow bankLimit = transferMapper.findBankLimit(task.srvcCd(), task.trxCcyCd());
        if (bankLimit != null && (amount.compareTo(nvl(bankLimit.minAmtLmt())) < 0
                || (bankLimit.maxAmtLmt() != null && amount.compareTo(bankLimit.maxAmtLmt()) > 0))) {
            return fail(task, executedBy, "FAILED",
                    "Limit transaksi bank berubah sejak transaksi dibuat; nominal tidak lagi diizinkan.");
        }
        BigDecimal debitLimit = transferMapper.findAccountDebitLimit(task.corpId(), task.remAcctNo());
        if (debitLimit != null && amount.compareTo(debitLimit) > 0) {
            return fail(task, executedBy, "FAILED",
                    "Limit debit rekening sumber berubah sejak transaksi dibuat; nominal tidak lagi diizinkan.");
        }

        // Usage increments at release - the recorded decision. Same transaction as the
        // core call: a refusal rolls them back, a timeout commits them.
        if (corpLimit != null) {
            transferMapper.incrementCorpLimitUsage(corpLimit.id(), amount);
        }
        if (groupLimit != null) {
            transferMapper.incrementGroupLimitUsage(groupLimit.id(), amount);
        }

        TransferOutcome outcome = coreTransferClient.transfer(
                task.remAcctNo(), task.benAcctNo(), amount, task.trxCcyCd(), task.remark1());

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
            transferMapper.insertBaseFt(new BaseFtInsert(
                    newId(), task.menuCd(), task.srvcCd(), task.refNo(), trxRefNo,
                    task.remAcctNo(), task.benAcctNo(), task.benAcctNm(), amount,
                    task.makerUserId(), executedBy));
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
     * Writes a non-success verdict and its EXECUTE action row in the CURRENT transaction.
     * Used inside TX-B (breach, timeout - commits with whatever TX-B holds) and as the
     * whole body of TX-C (after a rollback).
     */
    private ExecutionResult fail(ExecutionTaskRow task, String executedBy,
                                 String status, String reason) {
        trxTaskMapper.markExecutionOutcome(task.id(), status, executedBy);
        insertExecuteAction(task, executedBy, reason);
        log.warn("Task {} landed {}: {}", task.id(), status, reason);
        return new ExecutionResult(status, reason);
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

    /** Core banking definitely said no - thrown to roll TX-B back. */
    private static final class CoreRefusedException extends RuntimeException {
        CoreRefusedException(String message) {
            super(message != null ? message : "tanpa pesan");
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
