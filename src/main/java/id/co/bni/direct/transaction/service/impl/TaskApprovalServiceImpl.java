package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.dto.request.TaskRequests.ApproveTaskRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.BulkActionRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.RejectTaskRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpRequest;
import id.co.bni.direct.transaction.dto.response.TaskResponses.BulkActionResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.BulkResultResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.InboxItemResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.InboxResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.MyStageResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.TaskActionResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.TaskSummaryResponse;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.CandidateRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.StageRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.TaskRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.TaskSummaryRow;
import id.co.bni.direct.transaction.exception.BulkAbortedException;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.NotFoundException;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.ExecutionService;
import id.co.bni.direct.transaction.service.LimitService;
import id.co.bni.direct.transaction.service.TaskApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Approve / release / reject over the frozen candidate sets.
 *
 * <p>Eligibility reads ONLY TRX_TASK_CANDIDATE (materialized at submit) - never the live
 * legacy role tables, so a user onboarded after a task was submitted cannot act on it.
 *
 * <p>CONCURRENCY. Both actions run the same claim: after every check passes, the task row
 * is updated {@code WHERE VERSION = <the version read>}. Every state change of a task
 * goes through that claim and bumps VERSION, so 0 rows back means another approve or
 * reject landed in between - the caller gets TASK_NOT_ACTIONABLE and nothing is written,
 * which is what makes two simultaneous approvals count once. All stage/action writes
 * happen after the claim succeeds, inside the same transaction.
 */
@Service
public class TaskApprovalServiceImpl implements TaskApprovalService {

    private static final Logger log = LoggerFactory.getLogger(TaskApprovalServiceImpl.class);

    private final TrxTaskMapper trxTaskMapper;
    private final TransferMapper transferMapper;
    private final LimitService limitService;
    private final UmasAuthenticatorClient authenticatorClient;
    private final ExecutionService executionService;
    private final ExecutionOutbox executionOutbox;
    private final NotificationOutbox notificationOutbox;
    private final TransactionTemplate workflowTransaction;
    /** Configurable rather than a constant so it can be lowered without a release. */
    private final int bulkMaxSize;

    public TaskApprovalServiceImpl(TrxTaskMapper trxTaskMapper,
                                   TransferMapper transferMapper,
                                   LimitService limitService,
                                   UmasAuthenticatorClient authenticatorClient,
                                   ExecutionService executionService,
                                   ExecutionOutbox executionOutbox,
                                   NotificationOutbox notificationOutbox,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${app.tasks.bulk-max-size:50}") int bulkMaxSize) {
        this.trxTaskMapper = trxTaskMapper;
        this.transferMapper = transferMapper;
        this.limitService = limitService;
        this.authenticatorClient = authenticatorClient;
        this.executionService = executionService;
        this.executionOutbox = executionOutbox;
        this.notificationOutbox = notificationOutbox;
        this.workflowTransaction = new TransactionTemplate(transactionManager);
        this.bulkMaxSize = bulkMaxSize;
    }

    @Override
    public InboxResponse inbox(String companyId, String userId) {
        List<InboxItemResponse> items = trxTaskMapper.findInbox(companyId, userId).stream()
                .map(row -> new InboxItemResponse(
                        row.id(), row.refNo(),
                        TransferServiceImpl.menuName(row.menuCd()),
                        row.status(), row.trxAmt(), row.trxCcyCd(),
                        row.remAcctNo(), row.benAcctNo(), row.benAcctNm(),
                        row.remark1(), row.makerUserName(), row.createdDt(),
                        new MyStageResponse(row.stageSeq(), row.stageType(),
                                row.requiredCount(), row.completedCount())))
                .toList();
        return new InboxResponse(items);
    }

    /**
     * The badge. One aggregate query, no task list built and none cached: the counts must
     * be exact the moment an approver acts in another tab, and a 30-second poll of a
     * three-number COUNT is cheaper than any invalidation scheme that guarantees that.
     */
    @Override
    public TaskSummaryResponse summary(String companyId, String userId) {
        TaskSummaryRow row = trxTaskMapper.findTaskSummary(companyId, userId);
        return new TaskSummaryResponse(row.pendingApproval(), row.pendingRelease(),
                row.actionable());
    }

    /**
     * The workflow write runs in a {@link TransactionTemplate} (not {@code @Transactional})
     * because a release that completes the workflow must COMMIT before the execution seam
     * takes over - the seam opens transactions of its own against the same task row and
     * would self-block on an uncommitted claim. In sync mode execution then runs
     * synchronously in the request, so the answer carries the execution verdict, not
     * READY_TO_EXECUTE. In kafka mode the transaction instead lands the task QUEUED with
     * its outbox row (one atomic commit, written inside approveInTransaction), the seam
     * is NOT called, and the answer is QUEUED - the verdict arrives via the consumer.
     */
    @Override
    public TaskActionResponse approve(String companyId, String actor, String taskId,
                                      ApproveTaskRequest request) {
        String newStatus = workflowTransaction.execute(
                tx -> approveInTransaction(companyId, actor, taskId, request));
        if (!"READY_TO_EXECUTE".equals(newStatus)) {
            return new TaskActionResponse(taskId, newStatus);
        }
        ExecutionService.ExecutionResult executed = executionService.execute(taskId);
        return new TaskActionResponse(taskId, executed.status(), executed.message());
    }

    /** The approve/release write; answers the task's new WORKFLOW status. */
    private String approveInTransaction(String companyId, String actor, String taskId,
                                        ApproveTaskRequest request) {
        Acting acting = checkActionable(companyId, taskId, request.userId(), request.otp());
        return applyApprove(companyId, actor, acting, request.userId(), request.note());
    }

    /**
     * The approve/release write itself, over an {@link Acting} whose checks have already
     * passed. Both the single-task path and the batch path go through this one method, so
     * the stage arithmetic - and the decision of what the task's next status is - exists
     * exactly once.
     */
    private String applyApprove(String companyId, String actor, Acting acting,
                                String userId, String note) {
        String taskId = acting.task().id();
        TaskRow task = acting.task();
        StageRow stage = acting.stage();

        // The stage's arithmetic, computed from the read that VERSION certifies.
        int newCompleted = stage.completedCount() + 1;
        boolean stageDone = newCompleted >= stage.requiredCount();
        String newStatus = task.status();
        Integer newStageSeq = task.currentStageSeq();
        StageRow nextStage = null;
        if (stageDone) {
            final int doneSeq = stage.seqNo();
            nextStage = acting.stages().stream()
                    .filter(s -> s.seqNo() == doneSeq + 1)
                    .findFirst().orElse(null);
            if (nextStage != null) {
                newStageSeq = nextStage.seqNo();
                newStatus = "RELEASE".equals(nextStage.stageType())
                        ? "PENDING_RELEASE" : "PENDING_APPROVAL";
            } else {
                // RELEASE is always last, so no next stage means the release completed.
                // Sync mode hands the task to the seam after this commits; kafka mode
                // queues it instead - task status and outbox row in ONE commit.
                newStatus = executionOutbox.isKafkaMode() ? "QUEUED" : "READY_TO_EXECUTE";
                newStageSeq = null;
            }
        }

        claimOrConflict(taskId, task.version(), newStatus, newStageSeq, actor);
        if ("QUEUED".equals(newStatus)) {
            executionOutbox.enqueueExecutionRequested(taskId, task.refNo(), companyId);
        }

        String action = "RELEASE".equals(stage.stageType()) ? "RELEASE" : "APPROVE";
        insertAction(taskId, stage.seqNo(), action, userId, acting.candidate(),
                acting.otpVerificationId(), note);
        trxTaskMapper.updateStageProgress(taskId, stage.seqNo(), newCompleted,
                stageDone ? "DONE" : "ACTIVE", actor);
        if (nextStage != null) {
            trxTaskMapper.updateStageStatus(taskId, nextStage.seqNo(), "ACTIVE", actor);
        }

        // Same transaction as everything above (see NotificationOutbox): the event about
        // this approval commits with the approval or not at all. A release that completes
        // the workflow is TASK_RELEASED - the workflow is over and only the maker is
        // waiting on it; anything else is TASK_APPROVED, addressed to whoever the task is
        // now waiting on plus the maker. The stage seq handed over is the one the task
        // sits on AFTER this write: the next stage when this action completed the current
        // one, the same stage when a multi-signature level is still short.
        NotificationOutbox.NotifiableTask notifiable =
                notifiable(companyId, task, newStatus);
        if ("RELEASE".equals(action) && stageDone) {
            notificationOutbox.taskReleased(notifiable);
        } else {
            notificationOutbox.taskApproved(notifiable,
                    stageDone ? newStageSeq : stage.seqNo());
        }

        log.info("Task {} {} by {}: status {} -> {}", taskId, action, userId,
                task.status(), newStatus);
        return newStatus;
    }

    /** The task as a notification event describes it; {@code status} is post-change. */
    private static NotificationOutbox.NotifiableTask notifiable(String companyId, TaskRow task,
                                                                String status) {
        return new NotificationOutbox.NotifiableTask(
                task.id(), companyId, task.refNo(),
                TransferServiceImpl.menuName(task.menuCd(), task.srvcCd()), task.srvcCd(),
                task.trxAmt(), task.trxCcyCd(), status);
    }

    @Override
    @Transactional
    public TaskActionResponse reject(String companyId, String actor, String taskId,
                                     RejectTaskRequest request) {
        Acting acting = checkActionable(companyId, taskId, request.userId(), request.otp());
        applyReject(companyId, actor, acting, request.userId(), request.note());
        return new TaskActionResponse(taskId, "REJECTED");
    }

    /**
     * The reject write, shared by the single-task and batch paths.
     *
     * <p>A rejected task will never execute, so the daily ceiling it reserved at submit is
     * given back here. Doing it anywhere later would leave a company unable to spend a
     * limit that nothing is going to consume.
     */
    private void applyReject(String companyId, String actor, Acting acting, String userId, String note) {
        String taskId = acting.task().id();
        claimOrConflict(taskId, acting.task().version(), "REJECTED", null, actor);
        releaseReservation(companyId, acting.task());
        insertAction(taskId, acting.stage().seqNo(), "REJECT", userId,
                acting.candidate(), acting.otpVerificationId(), note);
        trxTaskMapper.closeOpenStages(taskId, actor);
        // After insertAction on purpose: the contract's recipients are the maker "plus
        // everyone who already acted", and by the time the task is rejected the rejecter
        // is one of them.
        notificationOutbox.taskRejected(notifiable(companyId, acting.task(), "REJECTED"));
        log.info("Task {} REJECTED by {}", taskId, userId);
    }

    // ------------------------------------------------------------------ batch

    /**
     * Approve many tasks at once. All-or-nothing: every selected task is checked first,
     * and one refusal aborts the whole batch with nothing written.
     */
    @Override
    public BulkActionResponse bulkApprove(String companyId, String actor, BulkActionRequest request) {
        return bulk(companyId, actor, request, "APPROVAL", "APPROVE");
    }

    /** Release many tasks at once; same contract as {@link #bulkApprove}. */
    @Override
    public BulkActionResponse bulkRelease(String companyId, String actor, BulkActionRequest request) {
        return bulk(companyId, actor, request, "RELEASE", "RELEASE");
    }

    /** Reject many tasks at once. The note saying why is required and shared by all of them. */
    @Override
    public BulkActionResponse bulkReject(String companyId, String actor, BulkActionRequest request) {
        if (request.note() == null || request.note().isBlank()) {
            throw new BusinessRuleException("NOTE_REQUIRED", "Alasan penolakan wajib diisi.");
        }
        return bulk(companyId, actor, request, null, "REJECT");
    }

    /**
     * The batch pipeline, in the order that decides whether a failed batch costs the user
     * a token:
     *
     * <ol>
     *   <li>shape checks (size, duplicates) - no database at all;</li>
     *   <li>eligibility of EVERY task, collecting all failures instead of stopping;</li>
     *   <li>abort here if anything failed - the OTP has not been touched;</li>
     *   <li>verify the OTP ONCE;</li>
     *   <li>apply the action to every task, all inside one transaction.</li>
     * </ol>
     *
     * <p>Step 3 is the whole point of the ordering. Verification happens at UMAS, outside
     * this transaction, and a rollback cannot give a spent token back - so a batch that was
     * never going to succeed must be refused before that hop, not after it.
     *
     * <p>Ids are sorted before processing so two overlapping batches always touch tasks in
     * the same order. Concurrency itself is still settled by the optimistic claim every
     * write makes (WHERE VERSION = the version read): a task another actor moved in
     * between fails the claim, the exception rolls the whole batch back, and the user is
     * told to reload. That is deliberately preferred over row locks, which on this access
     * pattern would trade a clear "someone else acted" message for a deadlock.
     */
    private BulkActionResponse bulk(String companyId, String actor, BulkActionRequest request,
                                    String requiredStageType, String action) {
        List<String> taskIds = validatedIds(request.taskIds());
        if (!request.isDryRun() && request.otp() == null) {
            throw new BusinessRuleException("OTP_REQUIRED", "Verifikasi token wajib diisi.");
        }

        List<BulkResultResponse> results = workflowTransaction.execute(tx -> {
            List<Acting> actings = new ArrayList<>();
            List<BulkAbortedException.Failure> failures = new ArrayList<>();
            for (String taskId : taskIds) {
                try {
                    actings.add(checkEligible(companyId, taskId, request.userId(), requiredStageType));
                } catch (NotFoundException ex) {
                    failures.add(new BulkAbortedException.Failure(taskId, null,
                            "TASK_NOT_FOUND", ex.getMessage()));
                } catch (BusinessRuleException ex) {
                    failures.add(new BulkAbortedException.Failure(taskId, null,
                            ex.code(), ex.getMessage()));
                }
            }
            if (!failures.isEmpty()) {
                log.info("Bulk {} aborted for {}: {} of {} tasks refused", action,
                        request.userId(), failures.size(), taskIds.size());
                throw new BulkAbortedException(failures);
            }
            if (request.isDryRun()) {
                // Nothing is written and no token is spent; the caller only wanted to know
                // whether the selection would go through. Reported statuses are current.
                return actings.stream()
                        .map(a -> new BulkResultResponse(a.task().id(), a.task().refNo(), a.task().status()))
                        .toList();
            }

            String verificationId = verifyOtp(request.userId(), request.otp());
            List<BulkResultResponse> applied = new ArrayList<>();
            for (Acting eligible : actings) {
                Acting acting = eligible.withOtp(verificationId);
                String status;
                if ("REJECT".equals(action)) {
                    applyReject(companyId, actor, acting, request.userId(), request.note());
                    status = "REJECTED";
                } else {
                    status = applyApprove(companyId, actor, acting, request.userId(), request.note());
                }
                applied.add(new BulkResultResponse(acting.task().id(), acting.task().refNo(), status));
            }
            return applied;
        });

        List<BulkResultResponse> finalResults = request.isDryRun()
                ? results
                : results.stream().map(this::executeIfReady).toList();
        log.info("Bulk {} by {}: {} tasks{}", action, request.userId(), finalResults.size(),
                request.isDryRun() ? " (dry run)" : "");
        return new BulkActionResponse(taskIds.size(),
                request.isDryRun() ? 0 : finalResults.size(),
                request.isDryRun(), finalResults);
    }

    /**
     * A released task whose workflow is complete goes to the execution seam - AFTER the
     * batch has committed, exactly as the single-task path does it, because the seam opens
     * its own transaction against the same row.
     *
     * <p>An execution that fails here does NOT undo the batch, and must not: the approvals
     * happened and are recorded; what failed is the instruction, which is a per-transaction
     * outcome the row itself carries. In kafka mode the task is already QUEUED and this is
     * a no-op.
     */
    private BulkResultResponse executeIfReady(BulkResultResponse result) {
        if (!"READY_TO_EXECUTE".equals(result.status())) {
            return result;
        }
        ExecutionService.ExecutionResult executed = executionService.execute(result.taskId());
        return new BulkResultResponse(result.taskId(), result.refNo(), executed.status());
    }

    /** Shape checks that need no database: batch size and duplicate ids. */
    private List<String> validatedIds(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new BusinessRuleException("TASK_IDS_REQUIRED", "Pilih minimal satu transaksi.");
        }
        if (taskIds.size() > bulkMaxSize) {
            throw new BusinessRuleException("BATCH_TOO_LARGE",
                    "Maksimal " + bulkMaxSize + " transaksi dalam satu proses.");
        }
        // A repeated id is a caller bug, not something to quietly de-duplicate: the second
        // occurrence would fail ALREADY_ACTED anyway and abort the batch with a confusing
        // reason. Say what is actually wrong instead.
        if (new HashSet<>(taskIds).size() != taskIds.size()) {
            throw new BusinessRuleException("DUPLICATE_TASK_ID",
                    "Ada transaksi yang terpilih lebih dari satu kali.");
        }
        return taskIds.stream().sorted().toList();
    }

    /** Everything both actions establish before writing anything. */
    private record Acting(TaskRow task, List<StageRow> stages, StageRow stage,
                          CandidateRow candidate, String otpVerificationId) {

        Acting withOtp(String verificationId) {
            return new Acting(task, stages, stage, candidate, verificationId);
        }
    }

    /**
     * The shared eligibility ladder: task exists, is actionable, the user is a frozen
     * candidate on the ACTIVE stage, has not acted on it yet, and their OTP verifies.
     * Order matters - the cheap database answers come before the OTP hop.
     */
    private Acting checkActionable(String companyId, String taskId, String userId,
                                   OtpRequest otp) {
        Acting eligible = checkEligible(companyId, taskId, userId, null);
        return eligible.withOtp(verifyOtp(userId, otp));
    }

    /**
     * Everything checkActionable establishes EXCEPT the OTP: the task exists, is on an
     * actionable stage of the expected type, and this user is a frozen candidate who has
     * not acted yet.
     *
     * <p>Split out for the batch path, where the token must be spent once and only after
     * every selected task has passed - verifying first and failing later would burn a
     * token the user then has to re-issue just to be told a row was stale. The single-task
     * path calls it through checkActionable and behaves exactly as before.
     *
     * <p>{@code requiredStageType} pins which kind of stage the caller is acting on
     * (APPROVAL for approve, RELEASE for release); null accepts whichever stage is active,
     * which is what the single-task endpoint and reject do.
     */
    private Acting checkEligible(String companyId, String taskId, String userId,
                                 String requiredStageType) {
        TaskRow task = trxTaskMapper.findTask(companyId, taskId);
        if (task == null) {
            throw new NotFoundException("Transaksi tidak ditemukan.");
        }

        // Actionable = the status says a stage is in progress AND the ACTIVE stage row of
        // that CURRENT_STAGE_SEQ is of the matching type. Anything else - already
        // rejected, ready to execute, or an inconsistent stage - answers the same code.
        String expectedStageType = switch (task.status()) {
            case "PENDING_APPROVAL" -> "APPROVAL";
            case "PENDING_RELEASE" -> "RELEASE";
            default -> null;
        };
        List<StageRow> stages = trxTaskMapper.findStages(taskId);
        StageRow stage = task.currentStageSeq() == null ? null
                : stages.stream()
                        .filter(s -> s.seqNo().equals(task.currentStageSeq()))
                        .findFirst().orElse(null);
        if (expectedStageType == null || stage == null
                || !"ACTIVE".equals(stage.status())
                || !expectedStageType.equals(stage.stageType())) {
            throw new BusinessRuleException("TASK_NOT_ACTIONABLE",
                    "Transaksi ini tidak dapat diproses pada status saat ini.");
        }
        // The batch endpoints are one action each, so a task sitting on the OTHER kind of
        // stage is refused by name rather than silently doing the other thing to it: a
        // user who selected twenty rows must not have some approved and some released.
        if (requiredStageType != null && !requiredStageType.equals(stage.stageType())) {
            throw new BusinessRuleException("STAGE_MISMATCH",
                    "RELEASE".equals(stage.stageType())
                            ? "Transaksi ini sedang menunggu rilis, bukan persetujuan."
                            : "Transaksi ini sedang menunggu persetujuan, bukan rilis.");
        }

        CandidateRow candidate = trxTaskMapper.findCandidate(taskId, stage.seqNo(), userId);
        if (candidate == null) {
            throw new BusinessRuleException("NOT_ELIGIBLE",
                    "Anda tidak berwenang memproses transaksi ini.");
        }
        if (trxTaskMapper.countStageActions(taskId, stage.seqNo(), candidate.corpUsrId()) > 0) {
            throw new BusinessRuleException("ALREADY_ACTED",
                    "Anda sudah memproses transaksi ini.");
        }
        return new Acting(task, stages, stage, candidate, null);
    }

    /** The token hop, kept separate so a batch can spend one verification for many tasks. */
    private String verifyOtp(String userId, OtpRequest otp) {
        UmasAuthenticatorClient.Verification verification = authenticatorClient
                .verifyTransaction(userId, otp.challenge(), otp.response());
        if (!verification.verified()) {
            throw new BusinessRuleException("OTP_INVALID",
                    "Kode OTP tidak valid atau sudah kedaluwarsa.");
        }
        return verification.verificationId();
    }

    /**
     * Give the task's limit reservation back. Silent when the task reserved nothing - an
     * older task, or a transfer no ceiling row applied to.
     */
    private void releaseReservation(String companyId, TaskRow task) {
        if (task.lmtSrvcCcyMtrxId() == null || task.lmtReservedAmt() == null) {
            return;
        }
        limitService.release(companyId, transferMapper.findUserGroupId(task.makerUserId()),
                task.srvcCd(), new LimitService.Reservation(task.lmtSrvcCcyMtrxId(),
                        task.lmtCcyMtrxCd(), task.lmtCcyCd(), task.lmtReservedAmt()));
    }

    /** The optimistic claim; 0 rows = a concurrent actor won, nothing is written. */
    private void claimOrConflict(String taskId, Long expectedVersion, String status,
                                 Integer stageSeq, String actor) {
        int claimed = trxTaskMapper.updateTaskProgress(taskId, expectedVersion, status,
                stageSeq, actor);
        if (claimed == 0) {
            throw new BusinessRuleException("TASK_NOT_ACTIONABLE",
                    "Transaksi ini baru saja diproses pengguna lain. Muat ulang dan coba lagi.");
        }
    }

    private void insertAction(String taskId, Integer stageSeq, String action, String userId,
                              CandidateRow candidate, String verificationId, String note) {
        trxTaskMapper.insertAction(new ActionInsert(
                newId(), taskId, stageSeq, action,
                candidate.corpUsrId(),
                candidate.userName() != null ? candidate.userName() : userId,
                candidate.corpUsrGrpId(),
                verificationId,
                note));
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
