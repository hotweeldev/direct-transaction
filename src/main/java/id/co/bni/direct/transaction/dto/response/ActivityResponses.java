package id.co.bni.direct.transaction.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Response shapes of Aktivitas Transaksi (FE contract §4.5, §7.1, §7.2). */
public final class ActivityResponses {

    private ActivityResponses() {
    }

    /** One row of the list / home card. */
    public record ActivityItemResponse(
            String referenceNo,
            String source,
            LocalDateTime date,
            String transactionType,
            String transactionTypeLabel,
            String sourceAccountNo,
            String sourceAccountName,
            String sourceAccountCurrency,
            String beneficiaryAccountNo,
            String beneficiaryName,
            BigDecimal amount,
            String currency,
            String instruction,
            String status,
            String rawStatus,
            String senderRefNo,
            String beneficiaryRefNo,
            String fileRefNo,
            /**
             * Where a still-moving transaction stands, e.g. {@code "Approval 1 dari 2"}
             * or {@code "Rilis"}. Null for anything that is not waiting on a stage -
             * every legacy/VA row, and every task past PENDING_RELEASE.
             */
            String stageProgress) {
    }

    /** GET /transactions/recent. */
    public record ActivityRecentResponse(List<ActivityItemResponse> items) {
    }

    /** POST /transactions/search. */
    public record ActivityPageResponse(List<ActivityItemResponse> items,
                                       int page,
                                       int size,
                                       long totalItems) {
    }

    /** One entry of the detail's activity trail. */
    public record ActivityTrailResponse(
            LocalDateTime timestamp,
            String type,
            String actor,
            BigDecimal amount,
            String currency,
            String activityStatus,
            String remark,
            String transactionStatus) {
    }

    /** GET /transactions/{referenceNo}. */
    public record ActivityDetailResponse(
            String referenceNo,
            String source,
            String status,
            String rawStatus,
            LocalDateTime date,
            String transactionType,
            String transactionTypeLabel,
            String sourceAccountNo,
            String sourceAccountName,
            String sourceAccountCurrency,
            String beneficiaryAccountNo,
            String beneficiaryName,
            BigDecimal amount,
            String currency,
            String remark,
            String instruction,
            String makerUserName,
            String journalNo,
            String trxRefNo,
            LocalDateTime executedAt,
            String senderRefNo,
            String beneficiaryRefNo,
            List<ActivityTrailResponse> activities,
            /**
             * The approval ladder, view-only. Filled ONLY while the transaction is still
             * moving through it (PENDING_APPROVAL / PENDING_RELEASE); null for every
             * other status and for legacy/VA rows, which have no ladder at all.
             */
            WorkflowResponse workflow) {
    }

    /**
     * The view-only workflow ladder of one task: every stage in order, including the ones
     * not reached yet - a stage that has not started is exactly what answers "berapa
     * tahap lagi". Same element shape as the approval screen's {@code stages} so the FE
     * renders it with the same component, actions disabled.
     */
    public record WorkflowResponse(
            Integer currentStageSeq,
            int totalApprovalStages,
            List<TransferResponses.StageResponse> stages) {
    }
}
