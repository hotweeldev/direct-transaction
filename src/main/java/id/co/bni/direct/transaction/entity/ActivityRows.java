package id.co.bni.direct.transaction.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Rows behind Aktivitas Transaksi (ActivityMapper.xml).
 *
 * <p>One row shape for both sources: a rewrite task ({@code TRX_TASK}, every status) or a
 * legacy final transaction ({@code BASE_FT}, executed only). {@code source} tells which,
 * {@code statusGroup} is the three-way status the screen shows (BERHASIL / GAGAL /
 * DIPROSES) computed in SQL so it can also be filtered on.
 */
public final class ActivityRows {

    private ActivityRows() {
    }

    public record ActivityRow(
            String source,
            String taskId,
            String refNo,
            String trxRefNo,
            String menuCd,
            String srvcCd,
            String srvcNm,
            String rawStatus,
            String statusGroup,
            BigDecimal trxAmt,
            String trxCcyCd,
            String remAcctNo,
            String remAcctNm,
            String remAcctCcy,
            String benAcctNo,
            String benAcctNm,
            String remark1,
            String instructionMode,
            LocalDateTime instructionDt,
            LocalDateTime createdDt,
            LocalDateTime executedDt,
            String journalNo,
            String makerUserName,
            String orderPartyRefNo,
            String counterPartyRefNo,
            /* Workflow position, TASK rows only - null on every legacy/VA row. */
            Integer currentStageSeq,
            Integer approvalStageCount,
            String currentStageType) {
    }

    /**
     * Search filter. A class with getters rather than a record: MyBatis' OGNL reads
     * parameter properties through getters, and a record's accessors are not bean getters.
     * Null means "no filter" for every field.
     */
    public static final class ActivityFilter {
        private String companyId;
        private String userId;
        private LocalDate dateFrom;
        private LocalDate dateTo;
        private String menuCd;
        private java.util.List<String> statusGroups;
        private String sourceAccountNo;
        private String beneficiaryName;
        private BigDecimal amountFrom;
        private BigDecimal amountTo;
        private String refNo;
        private int offset;
        private int size;

        public String getCompanyId() { return companyId; }
        public void setCompanyId(String companyId) { this.companyId = companyId; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public LocalDate getDateFrom() { return dateFrom; }
        public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }
        public LocalDate getDateTo() { return dateTo; }
        public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }
        public String getMenuCd() { return menuCd; }
        public void setMenuCd(String menuCd) { this.menuCd = menuCd; }
        public java.util.List<String> getStatusGroups() { return statusGroups; }
        public void setStatusGroups(java.util.List<String> statusGroups) { this.statusGroups = statusGroups; }
        public String getSourceAccountNo() { return sourceAccountNo; }
        public void setSourceAccountNo(String sourceAccountNo) { this.sourceAccountNo = sourceAccountNo; }
        public String getBeneficiaryName() { return beneficiaryName; }
        public void setBeneficiaryName(String beneficiaryName) { this.beneficiaryName = beneficiaryName; }
        public BigDecimal getAmountFrom() { return amountFrom; }
        public void setAmountFrom(BigDecimal amountFrom) { this.amountFrom = amountFrom; }
        public BigDecimal getAmountTo() { return amountTo; }
        public void setAmountTo(BigDecimal amountTo) { this.amountTo = amountTo; }
        public String getRefNo() { return refNo; }
        public void setRefNo(String refNo) { this.refNo = refNo; }
        public int getOffset() { return offset; }
        public void setOffset(int offset) { this.offset = offset; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
    }
}
