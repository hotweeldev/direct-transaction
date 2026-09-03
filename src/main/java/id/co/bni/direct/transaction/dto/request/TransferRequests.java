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

    /**
     * POST /transfers - the maker submit.
     *
     * <p>P1 adds the domestic (Transfer ke Bank Lain) fields, all additive and all
     * ignored for the BNI type. {@code transferType} is {@code "BNI"} (or absent - the
     * P0 wire shape keeps working), {@code "LLG"} or {@code "RTGS"}.
     * {@code beneficiaryBankId} is the {@code id} of a row from
     * {@code GET /transfers/banks?method=...}; the server re-reads that row and derives
     * the clearing code and BIC itself - the FE never sends raw bank codes.
     * {@code remitterResidencyCode}/{@code beneficiaryResidencyCode} speak each method's
     * OWN vocabulary (kliring: 1 Penduduk / 2 Bukan Penduduk; RTGS: 0 Resident /
     * 1 Non-Resident) and default to resident when absent. The fee is never sent - the
     * server computes it per method and validates limits on {@code amount + fee}.
     *
     * <p>P2 adds {@code "ONLINE"} (RTOL / ATM Bersama): required are
     * {@code beneficiaryBankId} (a row from {@code GET /transfers/banks?method=ONLINE};
     * the server re-derives its 3-digit interbank code), {@code beneficiaryAccountNo},
     * {@code beneficiaryName} (from the interbank-inquiry step) and an IDR
     * {@code amount}. The address/postal/residency/beneficiaryType fields are not part
     * of the interbank wire and are accepted but ignored.
     */
    public record SubmitTransferRequest(
            @NotBlank String userId,
            @NotBlank String sourceAccountNo,
            @NotBlank String beneficiaryAccountNo,
            String beneficiaryName,
            @NotNull @Valid MoneyRequest amount,
            String remark,
            @NotNull @Valid OtpRequest otp,
            String transferType,
            String beneficiaryBankId,
            String beneficiaryAddress1,
            String beneficiaryAddress2,
            String beneficiaryAddress3,
            String beneficiaryPhone,
            String beneficiaryPostalCode,
            String beneficiaryIdType,
            String beneficiaryIdNumber,
            String beneficiaryType,
            String remitterResidencyCode,
            String beneficiaryResidencyCode,
            String beneficiaryCurrency,
            BigDecimal debitAmount,
            String rateType,
            String underlyingDocType,
            String underlyingDocNumber,
            String underlyingDocName,
            BigDecimal underlyingDocAmount,
            String underlyingDocExpiry,
            String inquiryRequestId) {

        /** The pre-P3 wire (through the P5 underlying block); no VA inquiry id. */
        public SubmitTransferRequest(String userId, String sourceAccountNo,
                                     String beneficiaryAccountNo, String beneficiaryName,
                                     MoneyRequest amount, String remark, OtpRequest otp,
                                     String transferType, String beneficiaryBankId,
                                     String beneficiaryAddress1, String beneficiaryAddress2,
                                     String beneficiaryAddress3, String beneficiaryPhone,
                                     String beneficiaryPostalCode, String beneficiaryIdType,
                                     String beneficiaryIdNumber, String beneficiaryType,
                                     String remitterResidencyCode, String beneficiaryResidencyCode,
                                     String beneficiaryCurrency, BigDecimal debitAmount,
                                     String rateType, String underlyingDocType,
                                     String underlyingDocNumber, String underlyingDocName,
                                     BigDecimal underlyingDocAmount, String underlyingDocExpiry) {
            this(userId, sourceAccountNo, beneficiaryAccountNo, beneficiaryName, amount,
                    remark, otp, transferType, beneficiaryBankId, beneficiaryAddress1,
                    beneficiaryAddress2, beneficiaryAddress3, beneficiaryPhone,
                    beneficiaryPostalCode, beneficiaryIdType, beneficiaryIdNumber,
                    beneficiaryType, remitterResidencyCode, beneficiaryResidencyCode,
                    beneficiaryCurrency, debitAmount, rateType, underlyingDocType,
                    underlyingDocNumber, underlyingDocName, underlyingDocAmount,
                    underlyingDocExpiry, null);
        }

        /** The P0 shape - used by tests and anywhere only the BNI fields matter. */
        public SubmitTransferRequest(String userId, String sourceAccountNo,
                                     String beneficiaryAccountNo, String beneficiaryName,
                                     MoneyRequest amount, String remark, OtpRequest otp) {
            this(userId, sourceAccountNo, beneficiaryAccountNo, beneficiaryName, amount,
                    remark, otp, null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }

        /** The pre-P5 (P1/P2 domestic) shape; the multi-currency block stays absent. */
        public SubmitTransferRequest(String userId, String sourceAccountNo,
                                     String beneficiaryAccountNo, String beneficiaryName,
                                     MoneyRequest amount, String remark, OtpRequest otp,
                                     String transferType, String beneficiaryBankId,
                                     String beneficiaryAddress1, String beneficiaryAddress2,
                                     String beneficiaryAddress3, String beneficiaryPhone,
                                     String beneficiaryPostalCode, String beneficiaryIdType,
                                     String beneficiaryIdNumber, String beneficiaryType,
                                     String remitterResidencyCode, String beneficiaryResidencyCode) {
            this(userId, sourceAccountNo, beneficiaryAccountNo, beneficiaryName, amount,
                    remark, otp, transferType, beneficiaryBankId, beneficiaryAddress1,
                    beneficiaryAddress2, beneficiaryAddress3, beneficiaryPhone,
                    beneficiaryPostalCode, beneficiaryIdType, beneficiaryIdNumber,
                    beneficiaryType, remitterResidencyCode, beneficiaryResidencyCode,
                    null, null, null, null, null, null, null, null);
        }
    }

    /**
     * The Transfer ke Virtual Account billing inquiry (P3): the maker's "Periksa" step
     * before a VA submit. {@code vaNumber} is the virtual account (billing number);
     * {@code sourceAccountNo} is forwarded because the VA service keys the inquiry on
     * the paying account too.
     */
    public record VaInquiryRequest(
            @NotBlank String userId,
            @NotBlank String sourceAccountNo,
            @NotBlank String vaNumber) {
    }

    /**
     * POST /transfers/interbank-inquiry (P2, ONLINE) - the FE's "Periksa" step:
     * confirms the beneficiary's name at the destination bank through the interbank
     * switch before the maker submits. {@code beneficiaryBankId} is the id of a row
     * from {@code GET /transfers/banks?method=ONLINE}.
     */
    public record InterbankInquiryRequest(
            @NotBlank String userId,
            @NotBlank String sourceAccountNo,
            @NotBlank String beneficiaryAccountNo,
            @NotBlank String beneficiaryBankId,
            @NotNull @Positive BigDecimal amount) {
    }

    /** Money envelope: amount plus ISO currency code. */
    public record MoneyRequest(@NotNull @Positive BigDecimal amount, @NotBlank String currency) {
    }

    /** The OTP the maker typed, against the challenge the FE was handed. */
    public record OtpRequest(@NotBlank String challenge, @NotBlank String response) {
    }
}
