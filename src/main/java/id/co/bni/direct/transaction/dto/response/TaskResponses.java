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
}
