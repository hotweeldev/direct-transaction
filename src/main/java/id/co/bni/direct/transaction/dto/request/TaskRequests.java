package id.co.bni.direct.transaction.dto.request;

import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Wire shapes for {@code /api/v1/companies/{companyId}/tasks}.
 *
 * <p>The FE is being built against these exact field names in parallel - do not rename
 * them. {@code userId} is CORP_USR.USER_ID, the login id the candidate rows carry.
 */
public final class TaskRequests {

    private TaskRequests() {
    }

    /** POST /tasks/{taskId}/approve. Note is optional on an approval. */
    public record ApproveTaskRequest(
            @NotBlank String userId,
            String note,
            @NotNull @Valid OtpRequest otp) {
    }

    /** POST /tasks/{taskId}/reject. A rejection must say why - note is REQUIRED. */
    public record RejectTaskRequest(
            @NotBlank String userId,
            @NotBlank String note,
            @NotNull @Valid OtpRequest otp) {
    }

    /**
     * P6 reconciliation of a two-leg task left UNKNOWN. No OTP: nothing new is
     * authorized - the instruction was approved and released already; this only
     * establishes what core banking did with it.
     */
    public record ReconcileTaskRequest(@NotBlank String userId) {
    }
}
