package id.co.bni.direct.transaction.repository.mapper;

import java.math.BigDecimal;
import java.util.List;

import id.co.bni.direct.transaction.entity.TransferRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Every legacy-table read the maker-submit pipeline makes, plus the shared reference
 * number counter. Statements live in {@code resources/mapper/TransferMapper.xml}, not in
 * annotations: SQL is easier to review as SQL, and the joins there carry comments that
 * matter (the CORP_ACCT_NO trap above all).
 *
 * <p>Every method here must have a matching statement id in that file, which
 * {@code MapperStatementsTest} checks without a database.
 */
@Mapper
public interface TransferMapper {

    /** The maker with their group, account group, workflow role, level and auth type. */
    TransferRows.MakerRow findMaker(@Param("companyId") String companyId,
                                    @Param("userId") String userId);

    /** How many rows of the account group carry this account WITH debit permission. */
    int countDebitAccount(@Param("acctGroupId") String acctGroupId,
                          @Param("accountNo") String accountNo);

    /** How many rows of the account group carry this account with any permission. */
    int countAnyAccount(@Param("acctGroupId") String acctGroupId,
                        @Param("accountNo") String accountNo);

    /** The bank-wide band for a service+currency, or null when none is configured. */
    TransferRows.BankLimitRow findBankLimit(@Param("srvcCd") String srvcCd,
                                            @Param("ccyCd") String ccyCd);

    /** The company's usage-tracked ceiling for the matrix cell, or null. */
    TransferRows.UsageLimitRow findCorpLimit(@Param("companyId") String companyId,
                                             @Param("srvcCd") String srvcCd,
                                             @Param("ccyCd") String ccyCd);

    /** The user group's usage-tracked ceiling for the matrix cell, or null. */
    TransferRows.UsageLimitRow findGroupLimit(@Param("groupId") String groupId,
                                              @Param("srvcCd") String srvcCd,
                                              @Param("ccyCd") String ccyCd);

    /** CORP_ACCT.DEBIT_LMT for the source account, null when unset or unregistered. */
    BigDecimal findAccountDebitLimit(@Param("companyId") String companyId,
                                     @Param("accountNo") String accountNo);

    /** AUTH_LMT_SCHEME.MAKE_LMT for the maker's level+currency, null when no scheme row. */
    BigDecimal findMakerSchemeLimit(@Param("companyId") String companyId,
                                    @Param("aprvLvlCd") String aprvLvlCd,
                                    @Param("ccyCd") String ccyCd);

    /** CORP.IS_CASH_LITE / IS_SINGLE_USER, null when the company does not exist. */
    TransferRows.CorpFlagsRow findCorpFlags(@Param("companyId") String companyId);

    /**
     * Every user of the company that carries a workflow-role map row - the raw material
     * candidate materialization filters (role, level, group option) in Java at submit.
     */
    List<TransferRows.WorkflowUserRow> findWorkflowUsers(@Param("companyId") String companyId);

    /** The matrix header for company+menu+currency, or null - the NO_MATRIX case. */
    String findMatrixMasterId(@Param("companyId") String companyId,
                              @Param("menuCd") String menuCd,
                              @Param("ccyCd") String ccyCd);

    /** The matrix bands, ascending by LONG_AMT_LMT - a band's floor is the band below. */
    List<TransferRows.MatrixBandRow> findMatrixBands(@Param("masterId") String masterId);

    /** The chosen band's signature rows in SEQ_NO order, levels resolved. */
    List<TransferRows.MatrixSignatureRow> findBandSignatures(@Param("bandId") String bandId);

    /**
     * The company ceiling row locked FOR UPDATE at execution time - same resolution joins
     * as {@link #findCorpLimit}, answering the row's ID so the increment targets exactly
     * the locked row. Null when the company has no row for this matrix cell.
     */
    TransferRows.UsageLockRow lockCorpLimit(@Param("companyId") String companyId,
                                            @Param("srvcCd") String srvcCd,
                                            @Param("ccyCd") String ccyCd);

    /** The maker group's ceiling row locked FOR UPDATE; same shape as {@link #lockCorpLimit}. */
    TransferRows.UsageLockRow lockGroupLimit(@Param("groupId") String groupId,
                                             @Param("srvcCd") String srvcCd,
                                             @Param("ccyCd") String ccyCd);

    /** Add the executed amount to one locked CORP_LMT_PC_DTL row's usage. */
    int incrementCorpLimitUsage(@Param("id") String id, @Param("amount") BigDecimal amount);

    /** Add the executed amount to one locked CORP_USR_GRP_LMT row's usage. */
    int incrementGroupLimitUsage(@Param("id") String id, @Param("amount") BigDecimal amount);

    /** CORP_USR.CORP_USR_GRP_ID by CORP_USR.ID - the maker's group at execution time. */
    String findUserGroupId(@Param("corpUsrId") String corpUsrId);

    /** The legacy booking row of a successfully executed transfer. */
    int insertBaseFt(TransferRows.BaseFtInsert row);

    /** The booking row of an executed DOMESTIC (LLG/RTGS) transfer - P1's column set. */
    int insertBaseFtDom(TransferRows.BaseFtDomInsert row);

    /**
     * The executed Transfer ke Virtual Account record, into the legacy
     * {@code VIRTUAL_ACCOUNT_FT} table (P3) - legacy never wrote VA payments to BASE_FT.
     */
    int insertVirtualAccountFt(TransferRows.VaFtInsert row);

    /** One COM_MT_DOM_BANK row by ID (live rows only), or null. */
    TransferRows.DomBankRow findDomBank(@Param("id") String id);

    /**
     * The LLG bank picker: live rows whose CD is a 7-digit sandi kliring AND that carry
     * a member BIC - both are mandatory on the kliring operation (clearing code +
     * finalBankBic), so a row missing either cannot be routed.
     */
    List<TransferRows.DomBankRow> findClearingBanks();

    /** The RTGS bank picker: live rows that yield a BIC (MEMBER_CD, or a BIC-shaped CD). */
    List<TransferRows.DomBankRow> findRtgsBanks();

    /** The ONLINE (RTOL / ATM Bersama) picker: live rows carrying a 3-digit ONLINE_CD. */
    List<TransferRows.DomBankRow> findOnlineBanks();

    /**
     * P7: the BI-Fast participant banks - every active COM_MT_DOM_BANK row carrying a
     * BIFAST_CD (the receivingBIC the switch routes on). 37 rows on DEV.
     */
    List<TransferRows.DomBankRow> findBiFastBanks();

    /** P7: the active BI-Fast transaction purposes (COM_MT_BIFAST_TRX_PURPOSE). */
    List<TransferRows.BiFastPurposeRow> findBiFastPurposes();

    /** P7: 1 when the purpose code is an active, non-deleted COM_MT_BIFAST_TRX_PURPOSE row. */
    int countBiFastPurpose(@Param("cd") String cd);

    /** SYS_PARAM.VALUE by CD (live rows only), or null when missing or soft-deleted. */
    String findSysParamValue(@Param("cd") String cd);

    /** CORP's name/address/phone - the sender block of the kliring/RTGS operations. */
    TransferRows.CorpContactRow findCorpContact(@Param("companyId") String companyId);

    /** CORP.HOST_CIF_ID - the corporate CIF the trxPBI underlying check is keyed by (P5). */
    String findCorpHostCif(@Param("companyId") String companyId);

    /**
     * The current counter for (SERVICE_CD, CORP_ID), locked FOR UPDATE - the lock is what
     * keeps this service and the parallel legacy application from minting the same
     * reference number. Null when the pair has no row yet.
     */
    Long lockRefNoValue(@Param("srvcCd") String srvcCd, @Param("corpId") String corpId);

    /** First counter row for a (SERVICE_CD, CORP_ID) pair this schema has never seen. */
    int insertRefNoSeq(@Param("srvcCd") String srvcCd,
                       @Param("corpId") String corpId,
                       @Param("value") long value);

    /** Store the incremented counter; the caller already holds the row lock. */
    int updateRefNoSeq(@Param("srvcCd") String srvcCd,
                       @Param("corpId") String corpId,
                       @Param("value") long value);
}
