package id.co.bni.direct.transaction.entity;

import java.math.BigDecimal;

/**
 * Row shapes for the transaction-limit engine: the legacy limit rows it reads and updates
 * (CORP_LMT_PC_DTL, CORP_USR_GRP_LMT and the COM_LMT_PC_DTL package template behind them,
 * all entered through the service-currency matrix) plus the reservation figures this
 * service recomputes from its own TRX_TASK table (V13).
 *
 * <p>A limit in this system is ONE CEILING PER (company, service, CURRENCY COMBINATION) -
 * not per currency. The combination comes from COM_ST_SRVC_CCY_MTRX.CCY_MTRX_CD and its
 * codes are LL (local to local), LF (local to forex), FL (forex to local), FS (forex to
 * forex, same currency) and FC (forex to forex, cross). A row's own CCY_CD is a separate
 * thing: it is the DENOMINATION the ceiling is stated in, and the code that compares an
 * amount against a ceiling has to convert into it first. Reading CCY_MTRX_CD as if it were
 * a currency code, or CCY_CD as if it selected the limit, are the two ways this gets wrong.
 */
public final class LimitRows {

    private LimitRows() {
    }

    /**
     * One live limit row, as the two FOR UPDATE reads return it - the same shape from
     * CORP_LMT_PC_DTL (the company ceiling) and from CORP_USR_GRP_LMT (the user-group
     * ceiling), so the caller can run the same ladder step over both without a second
     * mapping.
     *
     * <p>THERE ARE TWO CEILINGS ON EVERY ROW AND THEY ARE NOT THE SAME KIND OF THING.
     * {@code maxAmtLmt}/{@code amtLmtUsage} are MONEY, expressed in this row's own
     * {@code ccyCd} and in nothing else - an amount in any other currency must be converted
     * into {@code ccyCd} before it is compared against {@code maxAmtLmt} or added to
     * {@code amtLmtUsage}. A transfer of 100 USD checked against a ceiling denominated in
     * IDR passes every time if the conversion is skipped. {@code maxOcLmt}/{@code ocLmtUsage}
     * are a transaction COUNT ("occurrences"): they have no currency at all, they are never
     * converted, and a currency-aware helper applied to them produces nonsense.
     *
     * <p>ZERO IS A BLOCK, NOT "UNLIMITED". A {@code maxAmtLmt} or {@code maxOcLmt} of zero
     * means the company or group may not use that service for that currency combination at
     * all; the permissive reading ("no ceiling configured, let it through") is the one bug
     * in this area that lets money out of the bank. NULL is the value that means "no ceiling
     * on this dimension" - the legacy columns are nullable and plenty of live rows carry a
     * money ceiling with a null occurrence ceiling or the reverse.
     *
     * <p>{@code maxOcLmt}/{@code ocLmtUsage} come back NULL for every CORP_USR_GRP_LMT row
     * regardless of configuration: that table has no occurrence columns at all (see
     * LimitMapper.xml's header). The count ladder therefore only ever has a company step.
     *
     * <p>{@code id} is the row's own primary key and is what the usage updates address. It
     * has to be carried out of the locked read rather than re-resolved, because the
     * (company, service, currency) key is NOT unique in legacy and re-running the
     * resolution can land on a different duplicate than the one that was locked.
     */
    public record LimitRow(String id, String srvcCcyMtrxId, String ccyMtrxCd, String ccyCd,
                           BigDecimal maxAmtLmt, BigDecimal amtLmtUsage,
                           Long maxOcLmt, Long ocLmtUsage) {
    }

    /**
     * One ceiling from the limit PACKAGE template (COM_LMT_PC_DTL), reached through
     * CORP.SRVC_PC_CD -> COM_SRVC_PC.LIMIT_PACKAGE_FK. Same currency rules as
     * {@link LimitRow}: {@code maxAmtLmt} is money in {@code ccyCd}, {@code maxOcLmt} is a
     * count, and zero on either means blocked rather than unlimited.
     *
     * <p>There are no usage fields here and that is not an omission - the package is a
     * TEMPLATE shared by every company on it, so it can state what the ceiling should be
     * but has nowhere to record what any one company has consumed. That is exactly why a
     * company transacting against a package-only limit must first get a row of its own
     * (LimitMapper.insertCorpLimitFromPackage); there is otherwise no place to put the
     * reservation.
     */
    public record PackageLimitRow(String srvcCcyMtrxId, String ccyMtrxCd, String ccyCd,
                                  BigDecimal maxAmtLmt, Long maxOcLmt) {
    }

    /**
     * What the still-open tasks of one company add up to on one limit line - the daily
     * worker's raw material, computed from this service's own TRX_TASK rather than from any
     * legacy counter.
     *
     * <p>The grouping key is (company, service, the matrix line and denomination the task
     * was VALIDATED against), which is why V13 froze those three onto the task: a rebuild
     * that re-derived the currency combination from the source account today would put a
     * task's reservation on a different line than the submit put it on, and the two figures
     * would never reconcile.
     *
     * <p>{@code amount} is the SUM of the reserved amounts, already expressed in
     * {@code ccyCd} because that is the denomination they were reserved in - it needs no
     * further conversion and must not be given one. {@code count} is the NUMBER of open
     * tasks, which is what the occurrence ceiling counts. The pair maps directly onto
     * AMT_LMT_USAGE / OC_LMT_USAGE of the row identified by
     * ({@code corpId}, {@code srvcCd}, {@code srvcCcyMtrxId}, {@code ccyCd}).
     */
    public record OpenTaskUsage(String corpId, String srvcCd, String srvcCcyMtrxId,
                                String ccyCd, BigDecimal amount, Long count) {
    }
}
