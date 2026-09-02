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
            String fileRefNo) {
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
            List<ActivityTrailResponse> activities) {
    }
}
