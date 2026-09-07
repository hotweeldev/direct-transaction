package id.co.bni.direct.transaction.repository.mapper;

import java.math.BigDecimal;
import java.util.List;

import id.co.bni.direct.transaction.entity.LimitRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * The transaction-limit engine's reads and writes: the legacy limit chain
 * (CORP_LMT_PC_DTL, CORP_USR_GRP_LMT and the COM_LMT_PC_DTL package template, all entered
 * through COM_ST_SRVC_CCY_MTRX / COM_ST_SRVC_CCY) plus the reservation figures recomputed
 * from this service's own TRX_TASK (V13). Statements live in
 * {@code resources/mapper/LimitMapper.xml}, whose header records what the live legacy data
 * actually looks like; every method here must have a matching statement id there
 * ({@code MapperStatementsTest}).
 *
 * <p>Everything on this interface is keyed by CCY_MTRX_CD - the currency COMBINATION
 * (LL/LF/FL/FS/FC) - and never by a currency code. The currency code lives on the returned
 * row and says what its ceiling is denominated in. TransferMapper's older
 * findCorpLimit/lockCorpLimit pair keys by CCY_CD instead; that is the narrower, older
 * read and the two are deliberately not merged while both are in use.
 */
@Mapper
public interface LimitMapper {

    /**
     * The company's own ceiling for one service and currency combination, LOCKED for
     * update, so the ceiling check and the usage increment that follows it are one
     * indivisible step against a concurrent submit or release on the same line.
     *
     * <p>Null means this company has no row of its own for that line - the common case,
     * not an error: 1,712 of 2,303 active companies have such gaps. The caller then reads
     * {@link #findPackageLimit} and materializes a row with
     * {@link #insertCorpLimitFromPackage} before it can reserve anything.
     */
    LimitRows.LimitRow lockCorpLimit(@Param("companyId") String companyId,
                                     @Param("srvcCd") String srvcCd,
                                     @Param("ccyMtrxCd") String ccyMtrxCd);

    /**
     * The user group's ceiling for the same line, locked the same way. Same null-means-no-row
     * rule, but with no package fallback: CORP_USR_GRP_LMT has no template behind it, so a
     * missing row here simply means the group step of the ladder does not apply.
     *
     * <p>The returned row always carries NULL occurrence figures. CORP_USR_GRP_LMT has no
     * MAX_OC_LMT / OC_LMT_USAGE columns at all - the group ceiling is money-only in legacy.
     */
    LimitRows.LimitRow lockGroupLimit(@Param("groupId") String groupId,
                                      @Param("srvcCd") String srvcCd,
                                      @Param("ccyMtrxCd") String ccyMtrxCd);

    /**
     * The limit-package template behind the company, reached CORP.SRVC_PC_CD ->
     * COM_SRVC_PC.LIMIT_PACKAGE_FK -> COM_LMT_PC_DTL. Ceilings only, no usage - see
     * {@link LimitRows.PackageLimitRow} for why that matters.
     */
    LimitRows.PackageLimitRow findPackageLimit(@Param("companyId") String companyId,
                                               @Param("srvcCd") String srvcCd,
                                               @Param("ccyMtrxCd") String ccyMtrxCd);

    /**
     * Copies a package ceiling down into a per-company CORP_LMT_PC_DTL row with both usage
     * counters at zero - what the legacy procedure INJECT_LIMIT_COMPANY does when a company
     * is set up, done here because for most companies it was never run.
     *
     * <p>This is a write to a legacy table, which is allowed: legacy DIRECT tables are
     * read-only for DDL, not for DML, and this one is the live limit table of the system
     * this application replaces. What it must not do is invent a ceiling - the figures come
     * from the package the company is actually on.
     */
    int insertCorpLimitFromPackage(@Param("id") String id, @Param("companyId") String companyId,
                                   @Param("srvcCd") String srvcCd,
                                   @Param("srvcCcyMtrxId") String srvcCcyMtrxId,
                                   @Param("ccyCd") String ccyCd,
                                   @Param("maxAmtLmt") BigDecimal maxAmtLmt,
                                   @Param("maxOcLmt") Long maxOcLmt,
                                   @Param("createdBy") String createdBy);

    /**
     * Moves the company row's two usage counters by the given deltas, addressed by the id
     * that came out of {@link #lockCorpLimit}.
     *
     * <p>NEGATIVE VALUES ARE HOW A RESERVATION IS RELEASED. Rejecting a task, or an
     * execution that fails, calls this with the negated amount and a count of -1; there is
     * no separate release statement and none is wanted, because a release that used
     * different arithmetic than the reservation would drift from it. Nothing is clamped at
     * zero on purpose: a release always matches an earlier reservation on the same row, so a
     * floor could only ever hide a bug by silently swallowing the difference. A usage that
     * goes negative is a real signal that a release ran twice or against the wrong row.
     *
     * <p>{@code count} is a transaction count and is not converted to anything;
     * {@code amount} must already be expressed in the locked row's own CCY_CD.
     */
    int addCorpUsage(@Param("id") String id, @Param("amount") BigDecimal amount,
                     @Param("count") Long count);

    /**
     * The same for the user-group row. {@code count} is accepted so callers can drive both
     * ladders with one shape, but it is NOT written: CORP_USR_GRP_LMT has no occurrence
     * columns (see {@link #lockGroupLimit}).
     */
    int addGroupUsage(@Param("id") String id, @Param("amount") BigDecimal amount,
                      @Param("count") Long count);



}
