package id.co.bni.direct.transaction.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Wire shapes for {@code /api/v1/companies/{companyId}/tasks} - the approval inbox and
 * the approve/reject answers.
 *
 * <p>The FE is being built against these exact field names in parallel - do not rename
 * them.
 */
public final class TaskResponses {

    private TaskResponses() {
    }

    /** GET /tasks - the inbox envelope. */
    public record InboxResponse(List<InboxItemResponse> items) {
    }

    /** One inbox line; {@code myStage} is the ACTIVE stage this user may act on. */
    public record InboxItemResponse(
            String taskId,
            String refNo,
            String menuName,
            String status,
            BigDecimal amount,
            String currency,
            String sourceAccountNo,
            String beneficiaryAccountNo,
            String beneficiaryName,
            String remark,
            String makerName,
            LocalDateTime createdAt,
            MyStageResponse myStage) {
    }

    /**
     * GET /tasks/summary - the bell/badge counts, polled every 30 seconds by every open
     * browser tab. {@code actionable} is the number of tasks where the caller is a
     * candidate of the currently ACTIVE stage and has not acted on it yet;
     * {@code pendingApproval} and {@code pendingRelease} are that same set split by the
     * kind of stage, so the two always add up to {@code actionable}. Counts only - the
     * list behind them is GET /tasks.
     */
    public record TaskSummaryResponse(int pendingApproval, int pendingRelease, int actionable) {
    }

    /** The stage the inbox user is a candidate on. */
    public record MyStageResponse(
            Integer seqNo,
            String stageType,
            Integer requiredCount,
            Integer completedCount) {
    }

    /**
     * POST /tasks/{taskId}/approve and /reject - the task's status after the action.
     * Since the execution phase, a release that completes the workflow answers the
     * EXECUTION verdict (EXECUTED / FAILED / UNKNOWN); {@code message} carries the
     * readable reason on FAILED / UNKNOWN and is null otherwise (additive field).
     */
    public record TaskActionResponse(String taskId, String status, String message) {
        public TaskActionResponse(String taskId, String status) {
            this(taskId, status, null);
        }
    }

    /**
     * The answer of a P6 reconciliation. {@code outcome} is what THIS call established:
     * EXECUTED (leg 2 found, task finalized), REFUNDED (leg 2 provably absent, refund
     * landed, task FAILED), REFUND_FAILED (refund refused/unknown, task stays UNKNOWN
     * with REFUND_FAILED), INCONCLUSIVE (nothing proven, nothing moved), NOOP (another
     * process landed it first). {@code status} / {@code twoLegState} are the task's
     * values after the call.
     */
    public record ReconcileResponse(String taskId, String status, String twoLegState,
                                    String outcome, String message) {
    }

    /**
     * The answer of a batch action that went through in full - there is no partial
     * success on this path, so {@code succeeded} equals {@code requested} except on a dry
     * run, which writes nothing and reports 0.
     *
     * <p>{@code results} carries one line per task with the status it holds AFTER the
     * action. Different values inside one batch are normal, not a bug: a task with two
     * approval levels moves on to the next level while a single-level one goes straight
     * to PENDING_RELEASE.
     */
    public record BulkActionResponse(int requested, int succeeded, boolean dryRun,
                                     List<BulkResultResponse> results) {
    }

    /** One task of a batch and where it landed. */
    public record BulkResultResponse(String taskId, String refNo, String status) {
    }
}
