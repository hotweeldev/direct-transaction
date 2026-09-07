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
     * POST /transfers/interbank-inquiry (P2, ONLINE) - the beneficiary as the interbank
     * switch knows it. {@code retrievalRefNo} is the switch's reference for the inquiry
     * leg (informational; the payment mints its own).
     */
    public record InterbankInquiryResponse(
            String beneficiaryName,
            String beneficiaryBankName,
            String retrievalRefNo) {
    }

    /**
     * POST /transfers/otp/challenge. {@code authType} is the user's CORP_USR.AUTH_TYP_CD;
     * challenge and verificationId come from the UMAS authenticator.
     */
    public record OtpChallengeResponse(String challenge, String verificationId, String authType) {
    }

    /**
     * The VA billing inquiry answer (P3). {@code name} and {@code amount} are what the VA
     * service reported and may both be null (the success shape is not fully confirmed on
     * DEV); the FE pre-fills the amount only when it is positive and keeps it editable.
     * {@code inquiryRequestId} must be echoed back on the submit. {@code fee} is the
     * configured flat VA fee so the FE never hardcodes it.
     */
    public record VaInquiryResponse(
            String vaNumber,
            String name,
            BigDecimal amount,
            String currency,
            String inquiryRequestId,
            BigDecimal fee,
            String responseCode,
            String responseMessage,
            String trxType,
            VaBillResponse bill) {

        /** The pre-V11 shape (no open/fixed flag, no bill block). */
        public VaInquiryResponse(String vaNumber, String name, BigDecimal amount,
                                 String currency, String inquiryRequestId, BigDecimal fee,
                                 String responseCode, String responseMessage) {
            this(vaNumber, name, amount, currency, inquiryRequestId, fee, responseCode,
                    responseMessage, null, null);
        }
    }

    /**
     * The bill block the VA service answered - the FE shows its labels and echoes the
     * whole block back as {@code vaBill} on the submit. {@code trxType} is the raw flag
     * ({@code o} = open payment); the outer {@code trxType} is the OPEN / FIXED reading.
     */
    public record VaBillResponse(
            String trxType,
            String billingLabel,
            String vaNameLabel,
            String billedAmountLabel,
            String billedAmountValue,
            BigDecimal billedAmount,
            String feeAmountLabel,
            String feeAmountValue,
            BigDecimal feeAmount,
            String accountNumberTo,
            String trxId,
            String clientId,
            String additionalLabel1,
            String additionalLabel2,
            String additionalLabel3,
            String additionalValue1,
            String additionalValue2,
            String additionalValue3) {
    }

    /**
     * POST /transfers, 201. {@code advisoryMessage} (P5, additive) carries the trxPBI
     * statement advisory of an IDR-to-valas transfer - informational, never blocking;
     * null everywhere else.
     */
    public record SubmitResponse(String taskId, String refNo, String status,
                                 String advisoryMessage) {

        public SubmitResponse(String taskId, String refNo, String status) {
            this(taskId, refNo, status, null);
        }
    }

    /**
     * GET /transfers/{taskId} and GET /tasks/{taskId} - the success/status screen and the
     * approval detail share one shape ({@code menuName}, {@code makerName} and the stage
     * {@code actions} were ADDED for the approval phase; {@code coreJournal},
     * {@code trxRefNo} and {@code executedAt} for the execution phase; the
     * {@code transferType} / bank / fee block for P1's LLG-RTGS - all additive, null for
     * older tasks and for types they do not apply to; existing consumers ignore them).
     * {@code totalAmount} is {@code amount + feeAmount}, the figure that is actually
     * debited and that every limit was validated against.
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
            LocalDateTime executedAt,
            String transferType,
            String beneficiaryBankId,
            String beneficiaryBankName,
            String beneficiaryBankCode,
            String beneficiaryBankBic,
            BigDecimal feeAmount,
            BigDecimal totalAmount,
            String retrievalRefNo,
            String interbankResponseCode,
            String debitCurrency,
            BigDecimal debitAmount,
            BigDecimal exchangeRate,
            String advisoryMessage,
            String sourceProductType,
            String twoLegState,
            String simsemAccountNo,
            String simsemJournalNo,
            String trxId,
            String endToEndId,
            String transactionPurpose,
            String vaTrxType,
            /**
             * The charge, itemised. {@code fees} carries one entry per component of the
             * legacy charge matrix (an LLG transfer is priced as Transfer Fee plus LLG
             * Fee, not as one number), {@code totalFee} their IDR total, {@code chargeTo}
             * who pays, and {@code totalDebited} what actually leaves the source account -
             * which on {@code BENEFICIARY} is the principal alone, because the fee comes
             * off the credit side instead. All null on tasks submitted before the charge
             * engine; {@code feeAmount} and {@code totalAmount} above stay as they were.
             */
            String chargeTo,
            List<ChargeComponentResponse> fees,
            MoneyResponse totalFee,
            MoneyResponse totalDebited) {
    }

    /**
     * One charge component. {@code amount} is the tariff in its own currency and
     * {@code amountIdr} the baseline figure the totals are built from; {@code fxRate} and
     * {@code fxRateType} are null whenever the tariff is already IDR, which in the live
     * data is every domestic transfer charge. Both sides travel so a screen can show
     * "USD 1,00 (Rp15.500)" instead of a converted number with no provenance.
     */
    public record ChargeComponentResponse(
            String code,
            String label,
            MoneyResponse amount,
            BigDecimal amountIdr,
            BigDecimal fxRate,
            String fxRateType) {
    }

    /** Money envelope for the charge block: an amount and its currency. */
    public record MoneyResponse(BigDecimal amount, String currency) {
    }

    /**
     * One row of GET /transfers/banks?method=LLG|RTGS|ONLINE - the destination-bank
     * picker. {@code code} is what the transfer will be routed with (7-digit sandi
     * kliring for LLG, the RTGS/BIC member code for RTGS, the 3-digit interbank code
     * for ONLINE); {@code bic} the final-bank BIC (LLG's bankPenerimaAkhir; for RTGS it
     * equals {@code code}; null for ONLINE - that wire carries no BIC).
     */
    public record BankResponse(String id, String code, String name, String bic) {
    }

    /** One BI-Fast transaction purpose (COM_MT_BIFAST_TRX_PURPOSE). */
    public record BiFastPurposeResponse(String code, String name) {
    }

    /**
     * The BI-Fast inquiry answer (P7): the creditor as the switch knows them, the routing
     * BIC the submit will repeat, the flat fee and the amount the inquiry was priced on.
     * The creditor block must be echoed on the submit as {@code bifastCreditor}.
     */
    public record BiFastInquiryResponse(
            String beneficiaryName,
            String receivingBic,
            String creditorId,
            String creditorType,
            String creditorAccountType,
            String creditorResidentStatus,
            String creditorTownName,
            String settlementDate,
            BigDecimal fee,
            BigDecimal amount) {
    }

    /**
     * GET /transfers/method-info?method=LLG|RTGS - the method's display parameters from
     * the legacy SYS_PARAM_TRF_SME_* rows, so the FE validates the minimum nominal and
     * shows the fee without hardcoding either. Amount fields are null when the parameter
     * row is missing or a token unparsable; {@code fee} falls back to the configured
     * per-method placeholder, so it is always present. {@code currency} is IDR - P1 is
     * single-leg IDR only.
     */
    public record MethodInfoResponse(
            String method,
            String currency,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            BigDecimal fee,
            String estimatedDuration) {
    }

    /**
     * One workflow stage. {@code approvalLevel} null means any level qualifies.
     *
     * <p>{@code label} and {@code pendingCandidates} are additive: the display name is
     * built server-side ({@code "Approval 1"} / {@code "Rilis"}) so no consumer has to
     * learn the naming rule, and {@code pendingCandidates} - who the stage is still
     * waiting on - is filled only for the ACTIVE stage of the view-only ladder in
     * Aktivitas Transaksi, null everywhere else including the approval screen.
     */
    public record StageResponse(
            Integer seqNo,
            String stageType,
            String approvalLevel,
            String groupOption,
            Integer requiredCount,
            Integer completedCount,
            String status,
            List<StageActionResponse> actions,
            String label,
            PendingCandidatesResponse pendingCandidates) {

        /** The pre-label shape the approval screen builds; label and candidates absent. */
        public StageResponse(Integer seqNo, String stageType, String approvalLevel,
                             String groupOption, Integer requiredCount, Integer completedCount,
                             String status, List<StageActionResponse> actions) {
            this(seqNo, stageType, approvalLevel, groupOption, requiredCount, completedCount,
                    status, actions, null, null);
        }
    }

    /**
     * Who a stage is still waiting on. {@code names} is capped (the service truncates);
     * {@code totalCount} is how many there really are, so a screen can render
     * "dan 3 lainnya" without a second call.
     */
    public record PendingCandidatesResponse(List<String> names, int totalCount) {
    }

    /** One action taken inside a stage (APPROVE / REJECT / RELEASE). */
    public record StageActionResponse(
            String actorName,
            String action,
            String note,
            LocalDateTime at) {
    }
}
