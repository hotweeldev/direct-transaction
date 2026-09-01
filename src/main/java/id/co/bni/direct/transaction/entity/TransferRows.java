package id.co.bni.direct.transaction.entity;

import java.math.BigDecimal;

/**
 * Row shapes for the legacy-table reads behind the submit pipeline. Components bind by
 * constructor-parameter name (arg-name-based auto-mapping; requires -parameters).
 */
public final class TransferRows {

    private TransferRows() {
    }

    /**
     * The maker as the legacy schema knows them. {@code corpUserId} is CORP_USR.ID (the
     * surrogate the task rows store), {@code userId} the login id, {@code wfRoleCd} /
     * {@code aprvLvlCd} the GCM_ST_APRV_MAP pair, {@code acctGroupId} the account-group
     * chain the source/beneficiary checks walk. {@code userName} is SPI_GE_USER.NAME when
     * that join still lands, null otherwise.
     */
    public record MakerRow(
            String corpUserId,
            String userId,
            String userName,
            String groupId,
            String acctGroupId,
            String wfRoleCd,
            String aprvLvlCd,
            String authTypCd) {
    }

    /** One BANK_TRX_LMT row: the bank-wide band for a service+currency. */
    public record BankLimitRow(BigDecimal minAmtLmt, BigDecimal maxAmtLmt) {
    }

    /** A usage-tracked ceiling (CORP_LMT_PC_DTL / CORP_USR_GRP_LMT). */
    public record UsageLimitRow(BigDecimal amtLmtUsage, BigDecimal maxAmtLmt) {
    }

    /** CORP's cash-lite flags, both legacy Y/N text. */
    public record CorpFlagsRow(String isCashLite, String isSingleUser) {
    }

    /** One CORP_APRV_MTRX_SUB band, ordered ascending by limit. */
    public record MatrixBandRow(String id, BigDecimal longAmtLmt, Integer noAprv) {
    }

    /**
     * One company user with their workflow-role pair, as candidate materialization reads
     * them. The GROUP-OPTION / level / role filtering happens in Java (unit-testable),
     * not in SQL - this row carries everything those filters need.
     */
    public record WorkflowUserRow(
            String corpUserId,
            String userId,
            String userName,
            String groupId,
            String wfRoleCd,
            String aprvLvlCd) {
    }

    /**
     * A usage-tracked ceiling read FOR UPDATE at execution time. Same resolution joins as
     * {@link UsageLimitRow}, plus the row's own ID so the increment that follows targets
     * exactly the locked row (never re-runs the resolution, which FETCH FIRST makes
     * non-deterministic over legacy duplicates).
     */
    public record UsageLockRow(String id, BigDecimal amtLmtUsage, BigDecimal maxAmtLmt) {
    }

    /**
     * The BASE_FT row execution writes - the minimal correct column set for an immediate
     * in-house IDR transfer, per the studied legacy fill-sheet. Constants (CLASS, IDX,
     * INSTRUCTION_MODE, flags) live in the INSERT statement itself; this record carries
     * only what varies per task. Charge columns (CH_TYP_1_*) are deliberately absent -
     * charge computation is a separate feature.
     */
    public record BaseFtInsert(
            String id,
            String mnuCd,
            String srvcCd,
            String refNo,
            String trxRefNo,
            String remAcctNo,
            String benAcctNo,
            String benAcctNm,
            BigDecimal trxAmt,
            String createdBy,
            String updatedBy) {
    }

    /**
     * One CORP_APRV_MTRX_DTL row of the chosen band, its level already resolved through
     * AUTH_LMT_SCHEME (null = any level).
     */
    public record MatrixSignatureRow(
            Integer seqNo,
            Integer noUsr,
            String aprvLvlCd,
            String usrGrpOpt,
            String corpUsrGrpId) {
    }
}
