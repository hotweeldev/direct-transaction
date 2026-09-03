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
            String trxCcyCd,
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

    /**
     * One COM_MT_DOM_BANK row (direct-bankmodule's DomesticBank master), read never
     * copied. {@code cd} is EITHER a 7-digit sandi kliring OR a BIC; {@code memberCd}
     * carries the BIC where present. The same bank appears as multiple rows in legacy
     * (e.g. Mandiri 0080017 + BMRIIDJA), so codes are taken from the ONE row the user
     * picked, and shape-validated per method.
     */
    public record DomBankRow(
            String id,
            String cd,
            String nm,
            String memberCd,
            String onlineCd) {
    }

    /** CORP's own name/address/phone - the kliring/RTGS sender block. */
    public record CorpContactRow(String nm, String addr1, String phoneNo) {
    }

    /**
     * The BASE_FT row of an executed DOMESTIC (LLG/RTGS) transfer - the in-house column
     * set plus the beneficiary-bank block the legacy LLG/RTGS rows carry
     * (BEN_DOM_BNK_ID, BEN_ADDR_1..3, LLD_IS_REM_RES / LLD_IS_BEN_RES, BEN_TYPE,
     * BIC_SWIFT_CD). {@code ftClass} is the legacy CLASS discriminator, passed in
     * because it differs per product. Charge columns (CH_TYP_*) stay NULL until P4.
     */
    public record BaseFtDomInsert(
            String id,
            String ftClass,
            String mnuCd,
            String srvcCd,
            String refNo,
            String trxRefNo,
            String remAcctNo,
            String benAcctNo,
            String benAcctNm,
            BigDecimal trxAmt,
            String benDomBnkId,
            String benAddr1,
            String benAddr2,
            String benAddr3,
            String lldIsRemRes,
            String lldIsBenRes,
            String benType,
            String bicSwiftCd,
            String createdBy,
            String updatedBy,
            String acctNoSimsem,
            String journalNoSimsem) {

        /**
         * The single-leg shape (P1/P2): no simsem account or leg-1 journal - those two
         * BASE_FT columns stay NULL. The P6 two-leg path uses the full constructor.
         */
        public BaseFtDomInsert(String id, String ftClass, String mnuCd, String srvcCd,
                               String refNo, String trxRefNo, String remAcctNo,
                               String benAcctNo, String benAcctNm, BigDecimal trxAmt,
                               String benDomBnkId, String benAddr1, String benAddr2,
                               String benAddr3, String lldIsRemRes, String lldIsBenRes,
                               String benType, String bicSwiftCd, String createdBy,
                               String updatedBy) {
            this(id, ftClass, mnuCd, srvcCd, refNo, trxRefNo, remAcctNo, benAcctNo,
                    benAcctNm, trxAmt, benDomBnkId, benAddr1, benAddr2, benAddr3,
                    lldIsRemRes, lldIsBenRes, benType, bicSwiftCd, createdBy, updatedBy,
                    null, null);
        }
    }

    /**
     * The legacy {@code VIRTUAL_ACCOUNT_FT} booking row a Transfer ke Virtual Account
     * writes on success (P3) - the VA-specific final record legacy kept instead of a
     * BASE_FT row. Column values follow the executed rows in the legacy sample data
     * (VA_TRX_TYPE 'o', PROCESS_FLAG 'Y', STANDING_INSTRUCTION '1', NOTIFICATION_FLAG
     * '1', the "No.VA" / "Nama" / "Biaya admin" labels); the table carries no journal
     * column, so the VA service's journalNum lives on TRX_TASK.CORE_JOURNAL only.
     *
     * @param totalAmt   the debited total, principal + fee
     * @param billedAmt  the principal the VA was credited with (the task's TRX_AMT)
     * @param createdBy  the maker's CORP_USR id (legacy CREATED_BY is the user row id)
     * @param updatedBy  the releasing actor
     */
    public record VaFtInsert(
            String id,
            String corpId,
            String vaNo,
            String currency,
            BigDecimal totalAmt,
            String vaName,
            BigDecimal billedAmt,
            String billedAmtValue,
            BigDecimal feeAmt,
            String feeAmtValue,
            String debitedAcctNo,
            String refNo,
            String trxRefNo,
            String remark,
            String createdBy,
            String updatedBy) {
    }
}
