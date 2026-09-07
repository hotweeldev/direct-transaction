package id.co.bni.direct.transaction.dto.request;

import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

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

    /**
     * POST /tasks/bulk-approve, /bulk-release and /bulk-reject - one action over many
     * tasks, authorized by ONE token.
     *
     * <p>One challenge covers the whole batch because the UMAS authenticator issues it
     * against a USER, not against an amount or a reference number: the same verification
     * that proves the person holds the token proves it once for everything they are
     * signing off in that moment. Every action row of the batch then stores the same
     * OTP_VERIFICATION_ID, which is what lets an auditor ask "what did this one
     * verification authorize" and get an exact answer.
     *
     * <p>{@code note} is optional on approve and release and REQUIRED on reject, checked
     * in the service rather than here - the annotation cannot know which endpoint it is
     * on. {@code otp} may be null only when {@code dryRun} is true: a dry run validates
     * the selection and writes nothing, so the user can be shown a bad row BEFORE being
     * asked to open their token.
     */
    public record BulkActionRequest(
            @NotBlank String userId,
            @NotEmpty List<String> taskIds,
            String note,
            @Valid OtpRequest otp,
            Boolean dryRun) {

        public boolean isDryRun() {
            return Boolean.TRUE.equals(dryRun);
        }
    }
}
