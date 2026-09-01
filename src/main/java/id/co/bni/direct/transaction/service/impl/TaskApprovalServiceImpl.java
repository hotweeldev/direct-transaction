package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.dto.request.TaskRequests.ApproveTaskRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.RejectTaskRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpRequest;
import id.co.bni.direct.transaction.dto.response.TaskResponses.InboxItemResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.InboxResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.MyStageResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.TaskActionResponse;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionInsert;
import id.co.bni.direct.transaction.entity.TrxTaskRows.CandidateRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.StageRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.TaskRow;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.NotFoundException;
import id.co.bni.direct.transaction.integration.UmasAuthenticatorClient;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.ExecutionService;
import id.co.bni.direct.transaction.service.TaskApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final UmasAuthenticatorClient authenticatorClient;
    private final ExecutionService executionService;
    private final ExecutionOutbox executionOutbox;
    private final TransactionTemplate workflowTransaction;

    public TaskApprovalServiceImpl(TrxTaskMapper trxTaskMapper,
                                   UmasAuthenticatorClient authenticatorClient,
                                   ExecutionService executionService,
                                   ExecutionOutbox executionOutbox,
                                   PlatformTransactionManager transactionManager) {
        this.trxTaskMapper = trxTaskMapper;
        this.authenticatorClient = authenticatorClient;
        this.executionService = executionService;
        this.executionOutbox = executionOutbox;
        this.workflowTransaction = new TransactionTemplate(transactionManager);
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
        insertAction(taskId, stage.seqNo(), action, request.userId(), acting.candidate(),
                acting.otpVerificationId(), request.note());
        trxTaskMapper.updateStageProgress(taskId, stage.seqNo(), newCompleted,
                stageDone ? "DONE" : "ACTIVE", actor);
        if (nextStage != null) {
            trxTaskMapper.updateStageStatus(taskId, nextStage.seqNo(), "ACTIVE", actor);
        }

        log.info("Task {} {} by {}: status {} -> {}", taskId, action, request.userId(),
                task.status(), newStatus);
        return newStatus;
    }

    @Override
    @Transactional
    public TaskActionResponse reject(String companyId, String actor, String taskId,
                                     RejectTaskRequest request) {
        Acting acting = checkActionable(companyId, taskId, request.userId(), request.otp());

        claimOrConflict(taskId, acting.task().version(), "REJECTED", null, actor);
        insertAction(taskId, acting.stage().seqNo(), "REJECT", request.userId(),
                acting.candidate(), acting.otpVerificationId(), request.note());
        trxTaskMapper.closeOpenStages(taskId, actor);

        log.info("Task {} REJECTED by {}", taskId, request.userId());
        return new TaskActionResponse(taskId, "REJECTED");
    }

    /** Everything both actions establish before writing anything. */
    private record Acting(TaskRow task, List<StageRow> stages, StageRow stage,
                          CandidateRow candidate, String otpVerificationId) {
    }

    /**
     * The shared eligibility ladder: task exists, is actionable, the user is a frozen
     * candidate on the ACTIVE stage, has not acted on it yet, and their OTP verifies.
     * Order matters - the cheap database answers come before the OTP hop.
     */
    private Acting checkActionable(String companyId, String taskId, String userId,
                                   OtpRequest otp) {
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

        CandidateRow candidate = trxTaskMapper.findCandidate(taskId, stage.seqNo(), userId);
        if (candidate == null) {
            throw new BusinessRuleException("NOT_ELIGIBLE",
                    "Anda tidak berwenang memproses transaksi ini.");
        }
        if (trxTaskMapper.countStageActions(taskId, stage.seqNo(), candidate.corpUsrId()) > 0) {
            throw new BusinessRuleException("ALREADY_ACTED",
                    "Anda sudah memproses transaksi ini.");
        }

        UmasAuthenticatorClient.Verification verification = authenticatorClient
                .verifyTransaction(userId, otp.challenge(), otp.response());
        if (!verification.verified()) {
            throw new BusinessRuleException("OTP_INVALID",
                    "Kode OTP tidak valid atau sudah kedaluwarsa.");
        }
        return new Acting(task, stages, stage, candidate, verification.verificationId());
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
