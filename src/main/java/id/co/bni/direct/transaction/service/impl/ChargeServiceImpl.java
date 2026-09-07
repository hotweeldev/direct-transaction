package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.entity.ChargeRows.TariffRow;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.repository.mapper.ChargeMapper;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.service.AmountRules;
import id.co.bni.direct.transaction.service.ChargeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The charge engine, over the legacy tariff tables.
 *
 * <p>The reading order is the decision recorded on 2026-09-07: the company's own row in
 * CORP_CH_PC_DTL first, the charge package behind it as a fallback, and a refusal when
 * neither prices the service. Refusing is deliberate - the alternative, quietly charging
 * zero or a constant, prices a transfer with a number no tariff ever authorised. The
 * fallback is not an edge case either: in the live data 1,548 of 2,303 active companies
 * have no per-company row at all.
 *
 * <p>CURRENCY. IDR is the baseline: every ceiling, every stored total and every figure the
 * screens show is IDR, so a tariff denominated in anything else is converted at submit and
 * both sides are kept on the task. The rate side is configuration
 * ({@code SYS_PARAM_CHARGE_FX_RATE_SIDE}, default SELL) because it is a pricing decision
 * the bank owns, not an engineering one, and SELL is the default for the same reason
 * {@code TransferServiceImpl.buyRate} uses BUY on a valas debit: the side follows the
 * direction of the exchange, and a foreign-currency fee settled from an IDR position is
 * the customer buying that currency. The rate TYPE, by contrast, is fixed at Regular by
 * decision - a company on a negotiated rate gets it on the principal, not on the fees.
 *
 * <p>Only the {@code TRX} (per-transaction) charge occurrence is read here. {@code PRD}
 * rows are periodical charges billed by a different process entirely.
 */
@Service
public class ChargeServiceImpl implements ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeServiceImpl.class);

    /** The baseline every stored charge total is expressed in. */
    public static final String BASELINE_CCY = "IDR";

    /**
     * Charges convert at the standard rate, never at the maker's negotiated one - the
     * discount is on the amount being sent, not on the bank's fee for sending it.
     */
    static final String CHARGE_RATE_TYPE = TransferServiceImpl.RATE_TYPE_REGULAR;

    public static final String SYS_PARAM_FX_RATE_SIDE = "SYS_PARAM_CHARGE_FX_RATE_SIDE";
    public static final String SYS_PARAM_FX_REQUOTE = "SYS_PARAM_CHARGE_FX_REQUOTE_ON_RELEASE";
    /** Shortened deliberately: SYS_PARAM.CD is 40 characters and the obvious name is 41. */
    public static final String SYS_PARAM_FX_REQUOTE_TOLERANCE = "SYS_PARAM_CHARGE_FX_TOLERANCE_PCT";

    private static final String SIDE_SELL = "SELL";
    private static final String SIDE_BUY = "BUY";
    private static final String SIDE_MID = "MID";

    /** Applied when the parameter row is missing, blank or unparsable. */
    private static final BigDecimal DEFAULT_TOLERANCE_PCT = new BigDecimal("5");

    /** The only value type this engine can read; see {@link TariffRow}. */
    private static final String VAL_TYP_FLAT = "F";

    private final ChargeMapper chargeMapper;
    private final TransferMapper transferMapper;
    private final CoreTransferClient coreTransferClient;

    public ChargeServiceImpl(ChargeMapper chargeMapper,
                             TransferMapper transferMapper,
                             CoreTransferClient coreTransferClient) {
        this.chargeMapper = chargeMapper;
        this.transferMapper = transferMapper;
        this.coreTransferClient = coreTransferClient;
    }

    @Override
    public ChargeQuote quote(String companyId, String srvcCd, String preferredCcy) {
        List<TariffRow> rows = chargeMapper.findCompanyTariffs(companyId, srvcCd);
        String source = "COMPANY";
        if (rows.isEmpty()) {
            rows = chargeMapper.findPackageTariffs(companyId, srvcCd);
            source = "PACKAGE";
        }
        if (rows.isEmpty()) {
            // Neither the company nor its package prices this service. Guessing here would
            // book a fee nobody authorised, so the transfer stops instead.
            throw new BusinessRuleException("CHARGE_NOT_CONFIGURED",
                    "Biaya untuk layanan ini belum diatur untuk perusahaan Anda. "
                            + "Hubungi administrator bank.");
        }

        List<TariffRow> chosen = resolve(rows, preferredCcy);
        // The rate is fetched once for the whole quote, and only when something actually
        // needs converting - which for every domestic transfer tariff in the live data is
        // never. An unconditional call would put a core-banking hop, and a way to fail,
        // in front of ordinary rupiah transfers that have no use for either.
        boolean needsFx = chosen.stream().anyMatch(r -> !BASELINE_CCY.equalsIgnoreCase(r.ccyCd()));
        String side = rateSide();
        Map<String, BigDecimal> rates = needsFx ? fetchRates(side) : Map.of();

        List<ChargeComponent> components = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int seq = 1;
        for (TariffRow row : chosen) {
            BigDecimal amount = flatAmount(row);
            String ccy = row.ccyCd() == null ? BASELINE_CCY : row.ccyCd().trim().toUpperCase();
            BigDecimal idrAmount;
            BigDecimal rate = null;
            if (BASELINE_CCY.equals(ccy)) {
                idrAmount = AmountRules.round(amount, BASELINE_CCY);
            } else {
                rate = rates.get(ccy);
                if (rate == null) {
                    throw new BusinessRuleException("CHARGE_RATE_UNAVAILABLE",
                            "Kurs " + ccy + " untuk perhitungan biaya tidak tersedia saat ini.");
                }
                idrAmount = AmountRules.round(amount.multiply(rate), BASELINE_CCY);
            }
            components.add(new ChargeComponent(seq++, row.chTypCd(), row.chTypNm(), ccy, amount,
                    idrAmount, rate, rate == null ? null : CHARGE_RATE_TYPE,
                    rate == null ? null : side, source));
            total = total.add(idrAmount);
        }
        log.debug("Charge quote companyId={} srvcCd={} source={} components={} totalIdr={}",
                companyId, srvcCd, source, components.size(), total);
        return new ChargeQuote(List.copyOf(components), total);
    }

    @Override
    public ChargeQuote requote(List<ChargeComponent> frozen) {
        boolean needsFx = frozen.stream().anyMatch(c -> !BASELINE_CCY.equalsIgnoreCase(c.ccyCd()));
        if (!needsFx) {
            return new ChargeQuote(List.copyOf(frozen), sum(frozen));
        }
        String side = rateSide();
        Map<String, BigDecimal> rates = fetchRates(side);
        List<ChargeComponent> repriced = new ArrayList<>();
        for (ChargeComponent c : frozen) {
            BigDecimal rate = BASELINE_CCY.equalsIgnoreCase(c.ccyCd()) ? null : rates.get(c.ccyCd());
            if (rate == null) {
                // A released task must not fail because this morning's rate has not landed:
                // the approved figure still stands and is what gets booked.
                repriced.add(c);
                continue;
            }
            repriced.add(new ChargeComponent(c.seqNo(), c.chTypCd(), c.chTypNm(), c.ccyCd(),
                    c.amt(), AmountRules.round(c.amt().multiply(rate), BASELINE_CCY), rate,
                    CHARGE_RATE_TYPE, side, c.tariffSource()));
        }
        return new ChargeQuote(List.copyOf(repriced), sum(repriced));
    }

    @Override
    public boolean requoteOnRelease() {
        String value = transferMapper.findSysParamValue(SYS_PARAM_FX_REQUOTE);
        // Absent parameter means the safe behaviour: what the approver saw is what is booked.
        return value != null && "Y".equalsIgnoreCase(value.trim());
    }

    @Override
    public BigDecimal requoteTolerancePercent() {
        String value = transferMapper.findSysParamValue(SYS_PARAM_FX_REQUOTE_TOLERANCE);
        if (value == null || value.isBlank()) {
            return DEFAULT_TOLERANCE_PCT;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("{} is not a number ({}); using {}", SYS_PARAM_FX_REQUOTE_TOLERANCE,
                    value, DEFAULT_TOLERANCE_PCT);
            return DEFAULT_TOLERANCE_PCT;
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * One tariff row per charge type, out of a table that guarantees neither.
     *
     * <p>Two ambiguities have to be settled here because the schema settles neither.
     * FIRST, a charge type can be priced in several currencies; the row matching the
     * transaction's own currency wins, then IDR, then whatever is left - a tariff in a
     * currency unrelated to the transfer is the least likely thing the bank meant.
     * SECOND, the tariff tables have no unique key and no soft-delete column, so the same
     * (charge type, currency) appears more than once - typically a 2015 row beside a 2021
     * row with a different amount. The newest UPDATED_DT wins. That rule is an INFERENCE
     * awaiting confirmation from the legacy team; if it is wrong, every customer is
     * charged a decade-old tariff, so it is written here in one place where it can be
     * changed once.
     */
    static List<TariffRow> resolve(List<TariffRow> rows, String preferredCcy) {
        String preferred = preferredCcy == null ? BASELINE_CCY : preferredCcy.trim().toUpperCase();
        Map<String, List<TariffRow>> byType = new LinkedHashMap<>();
        for (TariffRow row : rows) {
            byType.computeIfAbsent(row.chTypCd(), k -> new ArrayList<>()).add(row);
        }
        List<TariffRow> chosen = new ArrayList<>();
        for (List<TariffRow> candidates : byType.values()) {
            String ccy = pickCurrency(candidates, preferred);
            candidates.stream()
                    .filter(r -> ccy.equalsIgnoreCase(r.ccyCd()))
                    .max(Comparator.comparing(TariffRow::updatedDt,
                            Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder())))
                    .ifPresent(chosen::add);
        }
        return chosen;
    }

    private static String pickCurrency(List<TariffRow> candidates, String preferred) {
        boolean hasPreferred = candidates.stream().anyMatch(r -> preferred.equalsIgnoreCase(r.ccyCd()));
        if (hasPreferred) {
            return preferred;
        }
        boolean hasBaseline = candidates.stream().anyMatch(r -> BASELINE_CCY.equalsIgnoreCase(r.ccyCd()));
        if (hasBaseline) {
            return BASELINE_CCY;
        }
        return candidates.get(0).ccyCd() == null
                ? BASELINE_CCY : candidates.get(0).ccyCd().trim().toUpperCase();
    }

    /**
     * The flat amount of a tariff row. A {@code B} row carries an id into another table
     * rather than a number - it exists only on international services - and reading its
     * VALUE as money would produce a nonsense charge, so it is refused by name.
     */
    private static BigDecimal flatAmount(TariffRow row) {
        if (row.valTyp() == null || !VAL_TYP_FLAT.equalsIgnoreCase(row.valTyp().trim())) {
            throw new BusinessRuleException("CHARGE_TYPE_UNSUPPORTED",
                    "Jenis tarif biaya untuk layanan ini belum didukung.");
        }
        try {
            return new BigDecimal(row.value().trim());
        } catch (RuntimeException e) {
            throw new BusinessRuleException("CHARGE_TYPE_UNSUPPORTED",
                    "Nilai tarif biaya tidak dapat dibaca.");
        }
    }

    /** Today's rates by currency, on the configured side, already scaled by quotation units. */
    private Map<String, BigDecimal> fetchRates(String side) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (CoreTransferClient.Rate rate : coreTransferClient.fetchRates(CHARGE_RATE_TYPE)) {
            BigDecimal value = sided(rate, side);
            if (rate.currency() != null && value != null) {
                rates.putIfAbsent(rate.currency().trim().toUpperCase(), value);
            }
        }
        return rates;
    }

    /** One rate, on the requested side, divided by its quotation units ("100" = per hundred). */
    private static BigDecimal sided(CoreTransferClient.Rate rate, String side) {
        String raw = switch (side) {
            case SIDE_BUY -> rate.buyRate();
            case SIDE_MID -> rate.midRate();
            default -> rate.sellRate();
        };
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            BigDecimal units = rate.units() == null || rate.units().isBlank()
                    ? BigDecimal.ONE : new BigDecimal(rate.units().trim());
            if (units.compareTo(BigDecimal.ZERO) <= 0 || units.compareTo(BigDecimal.ONE) == 0) {
                return value;
            }
            return value.divide(units, 7, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** SELL unless the bank says otherwise; an unrecognised value is not silently honoured. */
    private String rateSide() {
        String value = transferMapper.findSysParamValue(SYS_PARAM_FX_RATE_SIDE);
        if (value == null || value.isBlank()) {
            return SIDE_SELL;
        }
        String side = value.trim().toUpperCase();
        if (SIDE_BUY.equals(side) || SIDE_MID.equals(side) || SIDE_SELL.equals(side)) {
            return side;
        }
        log.warn("{} holds an unknown side ({}); using {}", SYS_PARAM_FX_RATE_SIDE, value, SIDE_SELL);
        return SIDE_SELL;
    }

    private static BigDecimal sum(List<ChargeComponent> components) {
        return components.stream()
                .map(ChargeComponent::idrAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
