package id.co.bni.direct.transaction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Row shapes for the charge engine: the legacy tariff rows it reads (CORP_CH_PC_DTL and
 * COM_CH_PC_DTL through the charge matrix) and this service's own TRX_TASK_CHARGE table
 * (V12), which holds the resolved components of one transaction.
 */
public final class ChargeRows {

    private ChargeRows() {
    }

    /**
     * One tariff row as the two legacy reads return it - the same shape from the
     * per-company table and from the package template, so the caller can fall back from
     * one to the other without a second mapping.
     *
     * <p>{@code valTyp} is the legacy value type and decides how {@code value} must be
     * read. {@code 'F'} is a FLAT amount: {@code value} is the charge itself, in
     * {@code ccyCd}, and it is the only type every domestic transfer tariff uses.
     * {@code 'B'} is not an amount at all - it carries an id INTO ANOTHER TABLE (a banded
     * / tiered tariff resolved there) and appears only on international services. Parsing
     * a {@code 'B'} row's {@code value} as a number therefore yields a meaningless figure
     * that will nonetheless look like a plausible fee, so the caller must REFUSE a
     * {@code 'B'} row outright rather than misread it.
     *
     * <p>{@code value} stays a String for exactly that reason: the type is only safe to
     * convert after {@code valTyp} has been checked.
     *
     * <p>{@code updatedDt} exists because both legacy tables contain duplicate
     * (charge type, currency) keys and carry no soft-delete flag; it is what the service
     * uses to pick the newest of a duplicate pair. See ChargeMapper.xml's header.
     */
    public record TariffRow(String chTypCd, String chTypNm, String ccyCd, String valTyp,
                            String value, LocalDateTime updatedDt) {
    }

    /**
     * One TRX_TASK_CHARGE row at insert time - a single component of a transaction's
     * charge, written at submit and frozen there. {@code amt}/{@code ccyCd} are the tariff
     * as stated; {@code idrAmt} with {@code fxRate}/{@code fxRateType}/{@code fxRateSide}
     * is the IDR baseline and the quote that produced it ({@code fxRate} null when
     * {@code ccyCd} is IDR). {@code tariffSource} records whether the figure came from the
     * company row or the package template. The re-quote columns are not settable here:
     * they only ever arrive later, through updateRequote.
     */
    public record ChargeInsert(String id, String trxTaskId, Integer seqNo, String chTypCd,
                               String chTypNm, String ccyCd, BigDecimal amt,
                               BigDecimal idrAmt, BigDecimal fxRate,
                               String fxRateType, String fxRateSide, String tariffSource,
                               String createdBy) {
    }

    /**
     * One charge component as the detail, approval and release flows read it back, in
     * SEQ_NO order. Both sides of the conversion are present on purpose - the original
     * amount with its currency and the IDR figure with its rate - so "why this number" is
     * answerable from the row alone. {@code requotedIdrAmt}/{@code requotedFxRate} are
     * null unless release actually re-quoted; when they are set, the submit-time
     * {@code idrAmt}/{@code fxRate} beside them are still what the approvers saw.
     */
    public record ChargeRow(Integer seqNo, String chTypCd, String chTypNm, String ccyCd,
                            BigDecimal amt, BigDecimal idrAmt,
                            BigDecimal fxRate, String fxRateType, String fxRateSide,
                            String tariffSource, BigDecimal requotedIdrAmt,
                            BigDecimal requotedFxRate) {
    }
}
