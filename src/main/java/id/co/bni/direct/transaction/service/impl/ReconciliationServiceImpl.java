package id.co.bni.direct.transaction.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import id.co.bni.direct.transaction.config.TransferTypeProperties;
import id.co.bni.direct.transaction.dto.response.TaskResponses.ReconcileResponse;
import id.co.bni.direct.transaction.entity.TransferRows.BaseFtDomInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ExecutionTaskRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.TaskRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.TwoLegState;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.NotFoundException;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.integration.CoreTransferClient.Posting;
import id.co.bni.direct.transaction.integration.CoreTransferClient.TransferOutcome;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.ReconciliationService;
import id.co.bni.direct.transaction.service.TransferType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P6 task 6.5 - the reconciliation of a two-leg task stranded at UNKNOWN + LEG1_DONE:
 * leg 1 provably credited the simsem account, leg 2 (kliring/RTGS out of it) got no
 * answer. Nothing here ever re-sends leg 2.
 *
 * <p><b>The evidence.</b> direct-integration's {@code /transfers/status}
 * ({@code DepTrxInquiry}) lists the LAST postings on an account - a short, undated window
 * (the spec names twenty rows). Two facts are read off the simsem account's window:
 * <ol>
 * <li><b>Leg 2 landed</b> when a DEBIT posting matches the task: absolute amount equal to
 *     the outward amount and a narrative carrying the task's reference number or its
 *     remark (the narrative kliring/RTGS were sent). Its journal becomes the task's
 *     CORE_JOURNAL and the leg-2 BASE_FT row is written exactly as execution would have
 *     written it.</li>
 * <li><b>Leg 2 provably did NOT land</b> only when leg 1's own journal
 *     ({@code JOURNAL_NO_SIMSEM}) is still visible in the same window and no matching
 *     debit is. Leg 2 posts after leg 1, so if leg 1 has not scrolled off, leg 2 could not
 *     have either. Only then does the refund run.</li>
 * </ol>
 * When leg 1's journal is no longer in the window and no leg-2 debit is seen, the answer
 * is INCONCLUSIVE: the task stays UNKNOWN + LEG1_DONE and nothing moves. This is the
 * "never blind refund" rule made concrete - a refund on top of a leg 2 that did go out
 * would pay the customer twice. Resolving such a task needs the dated statement
 * ({@code /statements/search}, {@code HistoricalTransactions}) - not built here, flagged
 * in the roadmap.
 *
 * <p><b>Known gap.</b> The amount + narrative match is not a journal match (an UNKNOWN
 * leg 2 has no journal). On a busy pool account a second, identical outward posting
 * (same amount, same remark) inside the window would be taken for this task's leg 2. The
 * reference-number narrative (used whenever the maker typed no remark) is unique; a
 * remark narrative is not. Operations should prefer the reference number in remarks for
 * cross-currency Bank Lain transfers until the statement-based reconciliation exists.
 *
 * <p><b>Usage counters.</b> A refund landed here leaves the release-time usage increments
 * as they are: decision D3 (2026-09-01) gives this service no decrement path, and the
 * conservative direction for a limit is to stay counted. The action note records it.
 *
 * <p>Idempotent by construction: every terminal write is one of the
 * {@code reconcileTo*} statements guarded on the exact UNKNOWN + LEG1_DONE pre-state, so
 * a concurrent or repeated reconcile updates 0 rows and answers the live state instead.
 */
@Service
public class ReconciliationServiceImpl implements ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationServiceImpl.class);

    /** TRX_TASK_ACTION.NOTE is NVARCHAR2(400). */
    private static final int NOTE_MAX = 400;

    public static final String OUTCOME_EXECUTED = "EXECUTED";
    public static final String OUTCOME_REFUNDED = "REFUNDED";
    public static final String OUTCOME_REFUND_FAILED = "REFUND_FAILED";
    public static final String OUTCOME_INCONCLUSIVE = "INCONCLUSIVE";
    public static final String OUTCOME_NOOP = "NOOP";

    private final TrxTaskMapper trxTaskMapper;
    private final TransferMapper transferMapper;
    private final CoreTransferClient coreTransferClient;
    private final SimsemRefunder simsemRefunder;
    private final TransferTypeProperties transferTypeProperties;
    private final TransactionTemplate ownTransaction;

    public ReconciliationServiceImpl(TrxTaskMapper trxTaskMapper,
                                     TransferMapper transferMapper,
                                     CoreTransferClient coreTransferClient,
                                     SimsemRefunder simsemRefunder,
                                     TransferTypeProperties transferTypeProperties,
                                     PlatformTransactionManager transactionManager) {
        this.trxTaskMapper = trxTaskMapper;
        this.transferMapper = transferMapper;
        this.coreTransferClient = coreTransferClient;
        this.simsemRefunder = simsemRefunder;
        this.transferTypeProperties = transferTypeProperties;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ReconcileResponse reconcile(String companyId, String taskId, String actor) {
        TaskRow scoped = trxTaskMapper.findTask(companyId, taskId);
        if (scoped == null) {
            throw new NotFoundException("Transaksi tidak ditemukan.");
        }
        TwoLegState state = trxTaskMapper.findTwoLegState(taskId);
        if (state == null || !"UNKNOWN".equals(state.status())
                || !"LEG1_DONE".equals(state.twoLegState())) {
            throw new BusinessRuleException("TASK_NOT_RECONCILABLE",
                    "Hanya transaksi berstatus UNKNOWN dengan leg 1 (pemindahan ke rekening "
                            + "simsem) yang sudah terkonfirmasi yang dapat direkonsiliasi lewat "
                            + "endpoint ini.");
        }
        ExecutionTaskRow task = trxTaskMapper.findTaskForExecution(taskId);
        if (task == null) {
            throw new NotFoundException("Transaksi tidak ditemukan.");
        }

        // The evidence: the simsem account's recent postings. A hop that does not answer
        // throws the client's 503 - never an empty window.
        List<Posting> window = coreTransferClient.inquireTransactions(
                state.simsemAcctNo(), transferTypeProperties.getReconcileTransactionType());

        Optional<Posting> leg2 = window.stream()
                .filter(p -> isLeg2Of(p, task, state.journalNoSimsem()))
                .findFirst();
        if (leg2.isPresent()) {
            return finalizeExecuted(task, state, leg2.get(), actor);
        }

        boolean leg1Visible = state.journalNoSimsem() != null && window.stream()
                .anyMatch(p -> state.journalNoSimsem().equals(p.journalNo()));
        if (!leg1Visible) {
            log.warn("Task {}: leg 1 journal {} no longer in the simsem {} window ({} rows); "
                            + "inconclusive, nothing moved.",
                    task.id(), state.journalNoSimsem(), state.simsemAcctNo(), window.size());
            return new ReconcileResponse(task.id(), "UNKNOWN", "LEG1_DONE", OUTCOME_INCONCLUSIVE,
                    "Jurnal leg 1 tidak lagi terlihat pada riwayat singkat rekening simsem "
                            + state.simsemAcctNo() + " dan leg 2 tidak ditemukan. Tidak ada dana "
                            + "yang dipindahkan; rekonsiliasi memerlukan rekening koran bertanggal.");
        }

        // Leg 1 is in the window, leg 2 is not: leg 2 never left. Refund - one attempt.
        TransferOutcome refund = simsemRefunder.refund(task, state.simsemAcctNo(), state.journalNoSimsem());
        if (refund.status() == CoreTransferClient.Status.SUCCESS) {
            return land(task, actor, "FAILED", "REFUND_DONE", OUTCOME_REFUNDED,
                    "Rekonsiliasi: leg 2 tidak pernah terkirim; dana dikembalikan dari rekening "
                            + "simsem " + state.simsemAcctNo() + " ke rekening sumber. journalLeg1="
                            + state.journalNoSimsem() + " journalRefund=" + refund.coreJournal()
                            + ". Pemakaian limit tetap tercatat.");
        }
        return land(task, actor, "UNKNOWN", "REFUND_FAILED", OUTCOME_REFUND_FAILED,
                "Rekonsiliasi: leg 2 tidak pernah terkirim dan pengembalian dana dari rekening "
                        + "simsem " + state.simsemAcctNo() + " GAGAL/tidak pasti: "
                        + (refund.message() != null ? refund.message() : "tanpa pesan")
                        + ". Dana mungkin masih tertahan di rekening simsem; perlu penanganan "
                        + "manual. journalLeg1=" + state.journalNoSimsem());
    }

    /**
     * A posting is THIS task's leg 2 when it is a debit of exactly the outward amount,
     * is not leg 1's own posting, and its narrative carries the task's reference number
     * or the remark the leg-2 instruction was sent with.
     */
    public static boolean isLeg2Of(Posting posting, ExecutionTaskRow task, String leg1Journal) {
        if (posting.amount() == null || posting.amount().signum() >= 0) {
            return false;
        }
        if (posting.amount().abs().compareTo(task.trxAmt()) != 0) {
            return false;
        }
        if (leg1Journal != null && leg1Journal.equals(posting.journalNo())) {
            return false;
        }
        String narrative = posting.narrative() == null ? "" : posting.narrative().trim();
        if (narrative.isEmpty()) {
            return false;
        }
        if (task.refNo() != null && narrative.contains(task.refNo())) {
            return true;
        }
        String sent = SimsemRefunder.narrative(task);
        return sent != null && !sent.isBlank() && narrative.contains(sent.trim());
    }

    /** Leg 2 did land: the leg-2 BASE_FT row and the EXECUTED verdict, one transaction. */
    private ReconcileResponse finalizeExecuted(ExecutionTaskRow task, TwoLegState state,
                                               Posting leg2, String actor) {
        try {
            return ownTransaction.execute(tx -> finalizeExecutedInTransaction(task, state, leg2, actor));
        } catch (LostRaceException e) {
            // Somebody else landed it first (or the pre-state changed): the transaction -
            // and the reference-number increment with it - rolled back; answer the live state.
            return live(task.id(), OUTCOME_NOOP, "Transaksi sudah direkonsiliasi oleh proses lain.");
        }
    }

    private ReconcileResponse finalizeExecutedInTransaction(ExecutionTaskRow task, TwoLegState state,
                                                            Posting leg2, String actor) {
        String trxRefNo = TransferServiceImpl.nextRefNo(
                transferMapper, task.srvcCd(), task.corpId());
        int updated = trxTaskMapper.reconcileToExecuted(
                task.id(), leg2.journalNo(), trxRefNo, actor);
        if (updated == 0) {
            throw new LostRaceException();
        }
        TransferType type = TransferType.fromServiceCode(task.srvcCd());
        transferMapper.insertBaseFtDom(new BaseFtDomInsert(
                newId(), ftClass(type), task.menuCd(), task.srvcCd(), task.refNo(),
                trxRefNo, task.remAcctNo(), task.benAcctNo(), task.benAcctNm(),
                task.trxAmt(), task.benDomBnkId(), task.benAddr1(), task.benAddr2(),
                task.benAddr3(), task.lldIsRemRes(), task.lldIsBenRes(),
                task.benType(), task.benBnkBic(), task.makerUserId(), actor,
                state.simsemAcctNo(), state.journalNoSimsem()));
        String note = "Rekonsiliasi: leg 2 ditemukan pada rekening simsem "
                + state.simsemAcctNo() + ". coreJournal=" + leg2.journalNo()
                + " journalLeg1=" + state.journalNoSimsem();
        insertAction(task, actor, note);
        log.info("Task {} reconciled to EXECUTED: leg2 journal={} trxRefNo={}",
                task.id(), leg2.journalNo(), trxRefNo);
        return new ReconcileResponse(task.id(), "EXECUTED", "LEG2_DONE", OUTCOME_EXECUTED, note);
    }

    /** The guarded verdict updated 0 rows - thrown to roll the finalizing transaction back. */
    private static final class LostRaceException extends RuntimeException {
    }

    /** The refund verdict, one transaction, guarded on the pre-state. */
    private ReconcileResponse land(ExecutionTaskRow task, String actor, String status,
                                   String twoLegState, String outcome, String note) {
        return ownTransaction.execute(tx -> {
            int updated = trxTaskMapper.reconcileToRefunded(task.id(), status, twoLegState, actor);
            if (updated == 0) {
                return live(task.id(), OUTCOME_NOOP,
                        "Transaksi sudah direkonsiliasi oleh proses lain.");
            }
            insertAction(task, actor, note);
            log.warn("Task {} reconciled to {} ({}): {}", task.id(), status, twoLegState, note);
            return new ReconcileResponse(task.id(), status, twoLegState, outcome, note);
        });
    }

    private ReconcileResponse live(String taskId, String outcome, String message) {
        TwoLegState now = trxTaskMapper.findTwoLegState(taskId);
        return new ReconcileResponse(taskId,
                now != null ? now.status() : null,
                now != null ? now.twoLegState() : null,
                outcome, message);
    }

    private void insertAction(ExecutionTaskRow task, String actor, String note) {
        String trimmed = note != null && note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note;
        trxTaskMapper.insertAction(new ActionInsert(
                newId(), task.id(), null, "RECONCILE", actor, null, null, null, trimmed));
    }

    /** Same legacy CLASS discriminator the execution path writes. */
    private static String ftClass(TransferType type) {
        return switch (type) {
            case LLG -> "com.aprisma.product.gcm.common.model.LLGFT";
            case RTGS -> "com.aprisma.product.gcm.common.model.RTGSFT";
            case ONLINE -> "com.aprisma.product.gcm.common.model.OnlineFT";
            default -> "com.aprisma.product.gcm.common.model.InHouseFT";
        };
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
