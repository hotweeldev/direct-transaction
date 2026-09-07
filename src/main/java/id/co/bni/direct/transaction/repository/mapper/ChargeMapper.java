package id.co.bni.direct.transaction.repository.mapper;

import java.math.BigDecimal;
import java.util.List;

import id.co.bni.direct.transaction.entity.ChargeRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * The charge engine's reads and writes: the legacy tariff chain (CORP_CH_PC_DTL and the
 * COM_CH_PC_DTL package template, both entered through the charge matrix) and this
 * service's own TRX_TASK_CHARGE table (V12). Statements live in
 * {@code resources/mapper/ChargeMapper.xml}, whose header records what the live legacy
 * data actually looks like; every method here must have a matching statement id there
 * ({@code MapperStatementsTest}).
 */
@Mapper
public interface ChargeMapper {

    /**
     * The company's own tariff rows for one service - the AUTHORITATIVE source, not an
     * override for special customers (see the XML header: most companies have rows here).
     * Empty when this company was never injected, which is the case for two thirds of
     * them; the caller then falls back to {@link #findPackageTariffs}.
     *
     * <p>Duplicate (charge type, currency) keys DO come back: the legacy table has no
     * unique key beyond ID and no soft-delete flag. Resolving them is the service's job,
     * on UPDATED_DT - the SQL deliberately does not choose.
     */
    List<ChargeRows.TariffRow> findCompanyTariffs(@Param("companyId") String companyId,
                                                  @Param("srvcCd") String srvcCd);

    /**
     * The service-package template behind the company, reached through CORP.SRVC_PC_CD -
     * the fallback when the company has no per-company row. Same shape, same duplicate
     * caveat as {@link #findCompanyTariffs}.
     */
    List<ChargeRows.TariffRow> findPackageTariffs(@Param("companyId") String companyId,
                                                  @Param("srvcCd") String srvcCd);

    /** One resolved charge component, frozen at submit. */
    int insertCharge(ChargeRows.ChargeInsert charge);

    /** A task's components in SEQ_NO order - the breakdown behind TRX_TASK.FEE_AMT. */
    List<ChargeRows.ChargeRow> findCharges(@Param("taskId") String taskId);

    /**
     * Release re-quoted the rate: stores the new IDR figure, the new rate and the moment
     * it was taken, ALONGSIDE the submit-time values, which this statement never touches.
     * Addressed by (task, seq) - unique by UK_TRX_TASK_CHARGE_SEQ.
     */
    int updateRequote(@Param("taskId") String taskId,
                      @Param("seqNo") Integer seqNo,
                      @Param("requotedIdrAmt") BigDecimal requotedIdrAmt,
                      @Param("requotedFxRate") BigDecimal requotedFxRate);
}
