package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.entity.LimitRows;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.repository.mapper.LimitMapper;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.service.AmountRules;
import id.co.bni.direct.transaction.service.LimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Daily ceilings over the legacy limit tables, with the reservation taken at submit.
 *
 * <p>THE LOOKUP. A limit row is found by the currency COMBINATION (LL / FL / ...), never by
 * the transaction's currency, which is the bug this class replaces: a Forex-Local transfer
 * whose ceiling is denominated in IDR used to match no row at all, and a missing row read
 * as "no limit", so the ceiling silently stopped binding. The company's own row wins; when
 * the company has no row for that line the service package's ceiling applies, and because a
 * package row has nowhere to record usage, the company row is created from it first - which
 * is exactly what the legacy procedure INJECT_LIMIT_COMPANY does, and it matters because
 * 1,712 of 2,303 active companies have such gaps.
 *
 * <p>THE CURRENCY. The row's {@code CCY_CD} is the denomination of its amount ceiling, so
 * the transfer's amount is converted into THAT currency before it is compared or added -
 * not into a single global baseline. Conversion follows the charge engine's rule (sell
 * side, rate type Regular) so one transfer never mixes two conventions.
 *
 * <p>ZERO BLOCKS. A ceiling of zero means the service is not allowed, not that it is
 * unlimited.
 */
@Service
public class LimitServiceImpl implements LimitService {

    private static final Logger log = LoggerFactory.getLogger(LimitServiceImpl.class);

    /** One transfer consumes one occurrence, whatever it is worth. */
    private static final long ONE_OCCURRENCE = 1L;

    private final LimitMapper limitMapper;
    private final TransferMapper transferMapper;
    private final CoreTransferClient coreTransferClient;

    public LimitServiceImpl(LimitMapper limitMapper,
                            TransferMapper transferMapper,
                            CoreTransferClient coreTransferClient) {
        this.limitMapper = limitMapper;
        this.transferMapper = transferMapper;
        this.coreTransferClient = coreTransferClient;
    }

    @Override
    public Reservation reserve(String companyId, String makerGroupId, String srvcCd,
                               String sourceCcy, String trxCcy, BigDecimal amount, String actor) {
        CurrencyCombination combination = CurrencyCombination.of(sourceCcy, trxCcy);
        LimitRows.LimitRow corpLimit = corpLimitRow(companyId, srvcCd, combination, actor);
        if (corpLimit == null) {
            // Neither the company nor its package prices this line. Unlike the charge
            // engine, that is not a refusal: a ceiling is a restriction, and the absence of
            // one has always meant "unrestricted" here. It is logged so the gap is visible.
            log.info("No daily limit configured: companyId={} srvcCd={} combination={}",
                    companyId, srvcCd, combination);
            return Reservation.none();
        }

        BigDecimal reserved = convert(amount, trxCcy, corpLimit.ccyCd());
        check(corpLimit, reserved, "perusahaan");
        limitMapper.addCorpUsage(corpLimit.id(), reserved, ONE_OCCURRENCE);

        // The maker's group ceiling, when the group has one. It is denominated on its own
        // row, so the amount is converted again rather than reusing the company figure.
        if (makerGroupId != null) {
            LimitRows.LimitRow groupLimit =
                    limitMapper.lockGroupLimit(makerGroupId, srvcCd, combination.name());
            if (groupLimit != null) {
                BigDecimal groupAmount = convert(amount, trxCcy, groupLimit.ccyCd());
                check(groupLimit, groupAmount, "grup pengguna");
                limitMapper.addGroupUsage(groupLimit.id(), groupAmount, ONE_OCCURRENCE);
            }
        }

        log.debug("Reserved {} {} (1 trx) on {}/{}/{}", reserved, corpLimit.ccyCd(),
                companyId, srvcCd, combination);
        return new Reservation(corpLimit.srvcCcyMtrxId(), combination.name(),
                corpLimit.ccyCd(), reserved);
    }

    @Override
    public void release(String companyId, String makerGroupId, String srvcCd,
                        Reservation reservation) {
        if (reservation == null || reservation.isEmpty()) {
            return;
        }
        // A release is the reservation with the sign flipped, against the same rows the
        // reservation locked. Both figures go back: the amount AND the one occurrence.
        LimitRows.LimitRow corpLimit =
                limitMapper.lockCorpLimit(companyId, srvcCd, reservation.ccyMtrxCd());
        if (corpLimit != null) {
            limitMapper.addCorpUsage(corpLimit.id(), reservation.amount().negate(), -ONE_OCCURRENCE);
        }
        if (makerGroupId != null) {
            LimitRows.LimitRow groupLimit =
                    limitMapper.lockGroupLimit(makerGroupId, srvcCd, reservation.ccyMtrxCd());
            if (groupLimit != null) {
                // The group row may be denominated differently from the company row, so the
                // figure to give back is the one that was taken from THAT row. It was
                // computed from the same amount and the same rule, so recomputing it from
                // the reservation is exact as long as the denominations match; when they do
                // not, the group row's own currency wins.
                BigDecimal groupAmount = reservation.ccyCd().equalsIgnoreCase(groupLimit.ccyCd())
                        ? reservation.amount()
                        : convert(reservation.amount(), reservation.ccyCd(), groupLimit.ccyCd());
                limitMapper.addGroupUsage(groupLimit.id(), groupAmount.negate(), -ONE_OCCURRENCE);
            }
        }
        log.info("Released {} {} on {}/{}/{}", reservation.amount(), reservation.ccyCd(),
                companyId, srvcCd, reservation.ccyMtrxCd());
    }

    // ------------------------------------------------------------------ internals

    /**
     * The company's ceiling row for this line, materialised from the service package when
     * the company has none. Locked FOR UPDATE either way: two submits racing for the last
     * of a ceiling must queue, not both read the same remaining balance.
     */
    private LimitRows.LimitRow corpLimitRow(String companyId, String srvcCd,
                                            CurrencyCombination combination, String actor) {
        LimitRows.LimitRow existing = limitMapper.lockCorpLimit(companyId, srvcCd, combination.name());
        if (existing != null) {
            return existing;
        }
        LimitRows.PackageLimitRow pkg =
                limitMapper.findPackageLimit(companyId, srvcCd, combination.name());
        if (pkg == null) {
            return null;
        }
        // Copy the package ceiling down to the company, usage at zero, then take the lock
        // on the row we just wrote. Racing submits are settled by the unique key: the loser
        // finds the row on its second lock attempt rather than inserting a duplicate.
        try {
            limitMapper.insertCorpLimitFromPackage(newId(), companyId, srvcCd,
                    pkg.srvcCcyMtrxId(), pkg.ccyCd(), pkg.maxAmtLmt(), pkg.maxOcLmt(), actor);
            log.info("Materialised the package limit for companyId={} srvcCd={} combination={}",
                    companyId, srvcCd, combination);
        } catch (RuntimeException e) {
            log.debug("Concurrent materialisation of the limit row for {}/{}/{}: {}",
                    companyId, srvcCd, combination, e.getMessage());
        }
        return limitMapper.lockCorpLimit(companyId, srvcCd, combination.name());
    }

    /**
     * Both ceilings, in the order that produces the most useful refusal: a blocked service
     * first (nothing about the amount matters then), the count next, the amount last.
     */
    private static void check(LimitRows.LimitRow limit, BigDecimal amount, String scope) {
        if (isZero(limit.maxAmtLmt()) || isZero(limit.maxOcLmt())) {
            throw new BusinessRuleException("LIMIT_BLOCKED",
                    "Layanan ini tidak diizinkan untuk " + scope + " Anda.");
        }
        if (limit.maxOcLmt() != null) {
            long used = limit.ocLmtUsage() == null ? 0L : limit.ocLmtUsage();
            if (used + ONE_OCCURRENCE > limit.maxOcLmt()) {
                throw new BusinessRuleException("LIMIT_COUNT_EXCEEDED",
                        "Jumlah transaksi harian " + scope + " sudah mencapai batas.");
            }
        }
        if (limit.maxAmtLmt() != null) {
            BigDecimal used = limit.amtLmtUsage() == null ? BigDecimal.ZERO : limit.amtLmtUsage();
            if (used.add(amount).compareTo(limit.maxAmtLmt()) > 0) {
                throw new BusinessRuleException("LIMIT_AMOUNT_EXCEEDED",
                        "Nominal transaksi melebihi sisa limit harian " + scope + ".");
            }
        }
    }

    /** Zero is a deliberate block, so it is tested before anything is compared. */
    private static boolean isZero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    private static boolean isZero(Long value) {
        return value != null && value == 0L;
    }

    /**
     * The amount in the ceiling's own currency.
     *
     * <p>Same rule as the charge engine - sell side, rate type Regular - so a transfer is
     * never measured by one convention and charged by another. A rate that cannot be had
     * stops the submit: quietly comparing a dollar figure against a rupiah ceiling would
     * pass every limit there is.
     */
    private BigDecimal convert(BigDecimal amount, String fromCcy, String toCcy) {
        String from = normalise(fromCcy);
        String to = normalise(toCcy);
        if (from.equals(to)) {
            return amount;
        }
        BigDecimal rate = rateOf(from, to);
        return AmountRules.round(amount.multiply(rate), to);
    }

    /**
     * The rate that turns {@code from} into {@code to}. Only two shapes occur in the live
     * data - a foreign amount against an IDR ceiling, and an IDR amount against a foreign
     * one - so the rate is the foreign currency's, applied one way or the other. A pair of
     * two different foreign currencies has no ceiling row in the data and is refused rather
     * than crossed through a third rate nobody quoted.
     */
    private BigDecimal rateOf(String from, String to) {
        String baseline = ChargeServiceImpl.BASELINE_CCY;
        if (baseline.equals(to)) {
            return sellRate(from);
        }
        if (baseline.equals(from)) {
            return BigDecimal.ONE.divide(sellRate(to), 10, RoundingMode.HALF_UP);
        }
        throw new BusinessRuleException("LIMIT_CURRENCY_UNSUPPORTED",
                "Limit dalam mata uang " + to + " tidak dapat dibandingkan dengan transaksi "
                        + from + ".");
    }

    private BigDecimal sellRate(String ccy) {
        for (CoreTransferClient.Rate rate : coreTransferClient.fetchRates(ChargeServiceImpl.CHARGE_RATE_TYPE)) {
            if (rate.currency() == null || !ccy.equalsIgnoreCase(rate.currency().trim())) {
                continue;
            }
            BigDecimal value = parse(rate.sellRate());
            BigDecimal units = parse(rate.units());
            if (value == null) {
                continue;
            }
            if (units == null || units.compareTo(BigDecimal.ONE) <= 0) {
                return value;
            }
            return value.divide(units, 7, RoundingMode.HALF_UP);
        }
        throw new BusinessRuleException("LIMIT_RATE_UNAVAILABLE",
                "Kurs " + ccy + " untuk perhitungan limit tidak tersedia saat ini.");
    }

    private static BigDecimal parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalise(String ccy) {
        return ccy == null || ccy.isBlank()
                ? ChargeServiceImpl.BASELINE_CCY : ccy.trim().toUpperCase();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
