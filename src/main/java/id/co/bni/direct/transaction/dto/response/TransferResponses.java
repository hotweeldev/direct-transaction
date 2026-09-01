package id.co.bni.direct.transaction.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Wire shapes for {@code /api/v1/companies/{companyId}/transfers}.
 *
 * <p>The FE is being built against these exact field names in parallel - do not rename
 * them.
 */
public final class TransferResponses {

    private TransferResponses() {
    }

    /** POST /transfers/inquiry - the beneficiary as core banking knows it. */
    public record InquiryResponse(
            String accountNumber,
            String accountName,
            String currency,
            String status) {
    }

    /**
     * POST /transfers/otp/challenge. {@code authType} is the user's CORP_USR.AUTH_TYP_CD;
     * challenge and verificationId come from the UMAS authenticator.
     */
    public record OtpChallengeResponse(String challenge, String verificationId, String authType) {
    }

    /** POST /transfers, 201. */
    public record SubmitResponse(String taskId, String refNo, String status) {
    }

    /**
     * GET /transfers/{taskId} and GET /tasks/{taskId} - the success/status screen and the
     * approval detail share one shape ({@code menuName}, {@code makerName} and the stage
     * {@code actions} were ADDED for the approval phase; {@code coreJournal},
     * {@code trxRefNo} and {@code executedAt} for the execution phase - all additive,
     * null until the task executes; existing consumers ignore them).
     */
    public record TaskDetailResponse(
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
            List<StageResponse> stages,
            String coreJournal,
            String trxRefNo,
            LocalDateTime executedAt) {
    }

    /** One workflow stage. {@code approvalLevel} null means any level qualifies. */
    public record StageResponse(
            Integer seqNo,
            String stageType,
            String approvalLevel,
            String groupOption,
            Integer requiredCount,
            Integer completedCount,
            String status,
            List<StageActionResponse> actions) {
    }

    /** One action taken inside a stage (APPROVE / REJECT / RELEASE). */
    public record StageActionResponse(
            String actorName,
            String action,
            String note,
            LocalDateTime at) {
    }
}
