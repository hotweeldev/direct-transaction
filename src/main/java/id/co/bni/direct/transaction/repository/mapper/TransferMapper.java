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
