package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a transfer costs, resolved from the legacy charge tables instead of the flat
 * per-method constants the service shipped with.
 *
 * <p>Three facts about the legacy model shape this interface, all verified against the
 * live GCM_AGCM schema on 2026-09-07:
 *
 * <ul>
 *   <li><b>A transfer carries several charges, not one.</b> Every interbank service has
 *       two components - LLG is Transfer Fee plus LLG Fee, RTGS is Transfer Fee plus RTGS
 *       Fee - so the answer is a list and the total is derived, never the other way round.
 *   <li><b>Each component has its own currency.</b> Tariff rows are keyed by currency, and
 *       real transactions exist with an IDR principal and a USD fee. IDR is the baseline
 *       every ceiling and every stored total is expressed in, so a foreign-currency
 *       component is converted - and both sides are kept, because a converted number
 *       nobody can trace back is a number nobody can defend.
 *   <li><b>The tariff is per company.</b> CORP_CH_PC_DTL is the effective table; the
 *       charge package behind it is only the template it was copied from.
 * </ul>
 */
public interface ChargeService {

    /** Who pays. The canonical spelling; legacy has three of its own, translated at each edge. */
    enum ChargeBearer {
        /** The sender is debited principal + charges. The default, and what legacy writes as '0'. */
        REMITTER,
        /**
         * The charge comes off the credit side: the sender is debited the principal alone
         * and the beneficiary receives less. Legacy writes '1' on the single-transfer
         * tables and 'BEN' on the activity log, where the amounts move from the
         * DBT_CH_TYP_* columns to the CRDT_CH_TYP_* ones.
         */
        BENEFICIARY;

        /** Parses the wire word; null, blank and anything unknown mean REMITTER. */
        public static ChargeBearer parse(String value) {
            if (value == null || value.isBlank()) {
                return REMITTER;
            }
            return BENEFICIARY.name().equalsIgnoreCase(value.trim()) ? BENEFICIARY : REMITTER;
        }
    }

    /**
     * One charge component as it will be frozen on the task.
     *
     * <p>{@code amt} in {@code ccyCd} is what the tariff says; {@code idrAmt} is that
     * figure in the baseline currency. They are equal, and {@code fxRate} is null, for the
     * IDR tariffs that make up every domestic transfer charge in the live data.
     */
    record ChargeComponent(
            int seqNo,
            String chTypCd,
            String chTypNm,
            String ccyCd,
            BigDecimal amt,
            BigDecimal idrAmt,
            BigDecimal fxRate,
            String fxRateType,
            String fxRateSide,
            String tariffSource) {
    }

    /**
     * The whole charge of one transfer: its components and their IDR total.
     *
     * <p>{@code totalIdr} is the sum of the components' {@code idrAmt}, computed after each
     * component was rounded on its own. Rounding the sum instead would leave a breakdown
     * that does not add up to the total the customer is shown, which is the kind of
     * discrepancy that costs an afternoon to explain.
     */
    record ChargeQuote(List<ChargeComponent> components, BigDecimal totalIdr) {

        /** The empty quote: a service the charge matrix prices at nothing. */
        public static ChargeQuote none() {
            return new ChargeQuote(List.of(), BigDecimal.ZERO);
        }
    }

    /**
     * Price one transfer.
     *
     * @param companyId    the corporate whose tariff applies
     * @param srvcCd       the legacy service code (GCM_FTR_DOM_LLG and friends)
     * @param preferredCcy the transaction's currency, used to choose between tariff rows
     *                     when a charge type is priced in more than one currency
     * @throws id.co.bni.direct.transaction.exception.BusinessRuleException when neither
     *         the company nor its package prices the service, or when a tariff row is of a
     *         type this engine cannot read
     */
    ChargeQuote quote(String companyId, String srvcCd, String preferredCcy);

    /**
     * Re-price the frozen components at today's rate, for the release-time re-quote that
     * {@code SYS_PARAM_CHARGE_FX_REQUOTE_ON_RELEASE} switches on. Components already in
     * IDR are returned untouched - there is nothing to re-quote about them.
     */
    ChargeQuote requote(List<ChargeComponent> frozen);

    /** Whether release should re-quote foreign-currency charges at all. */
    boolean requoteOnRelease();

    /**
     * How far a re-quoted total may drift from the approved one before the transfer is
     * refused rather than booked, as a percentage. A drift inside the tolerance is priced
     * at the new rate; a drift outside it means the figure the approver signed off is no
     * longer the figure being charged, and nobody has agreed to the new one.
     */
    BigDecimal requoteTolerancePercent();
}
