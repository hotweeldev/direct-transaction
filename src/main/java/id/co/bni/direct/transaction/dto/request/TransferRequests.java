package id.co.bni.direct.transaction.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Wire shapes for {@code /api/v1/companies/{companyId}/transfers}.
 *
 * <p>The FE is being built against these exact field names in parallel - do not rename
 * them.
 */
public final class TransferRequests {

    private TransferRequests() {
    }

    /** POST /transfers/inquiry. */
    public record InquiryRequest(@NotBlank String accountNumber) {
    }

    /** POST /transfers/otp/challenge. {@code userId} is CORP_USR.USER_ID, the login id. */
    public record OtpChallengeRequest(@NotBlank String userId) {
    }

    /** POST /transfers - the maker submit. */
    public record SubmitTransferRequest(
            @NotBlank String userId,
            @NotBlank String sourceAccountNo,
            @NotBlank String beneficiaryAccountNo,
            String beneficiaryName,
            @NotNull @Valid MoneyRequest amount,
            String remark,
            @NotNull @Valid OtpRequest otp) {
    }

    /** Money envelope: amount plus ISO currency code. */
    public record MoneyRequest(@NotNull @Positive BigDecimal amount, @NotBlank String currency) {
    }

    /** The OTP the maker typed, against the challenge the FE was handed. */
    public record OtpRequest(@NotBlank String challenge, @NotBlank String response) {
    }
}
