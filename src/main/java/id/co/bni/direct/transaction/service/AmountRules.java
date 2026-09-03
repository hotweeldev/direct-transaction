package id.co.bni.direct.transaction.service;

import id.co.bni.direct.transaction.exception.BusinessRuleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * The velis Fund Transfer sheet's decimal rules (task 5.6): JPY and IDR amounts must be
 * whole numbers; every other currency allows at most 2 decimal places. Applied to the
 * credit amount and the debit amount at submit, and reused to round the server-computed
 * baseAmount (an IDR figure, so 0 dp).
 */
public final class AmountRules {

    /** Whole-number currencies per the velis sheet. */
    private static final Set<String> ZERO_DECIMAL = Set.of("IDR", "JPY");

    private AmountRules() {
    }

    /** Max decimal places for a currency: 0 for IDR/JPY, 2 otherwise. */
    public static int maxDecimals(String currency) {
        return currency != null && ZERO_DECIMAL.contains(currency.trim().toUpperCase()) ? 0 : 2;
    }

    /**
     * 422 {@code TRANSFER_FIELDS_INVALID} when the amount carries more decimals than the
     * currency allows. Trailing zeros are not an offence ({@code 100.00 JPY} passes);
     * {@code label} names the offending field in the Indonesian message ("Nominal
     * transfer", "Nominal debit"). Null amount or currency is not this rule's problem.
     */
    public static void validateScale(BigDecimal amount, String currency, String label) {
        if (amount == null || currency == null) {
            return;
        }
        int max = maxDecimals(currency);
        if (amount.stripTrailingZeros().scale() > max) {
            String ccy = currency.trim().toUpperCase();
            throw new BusinessRuleException("TRANSFER_FIELDS_INVALID",
                    max == 0
                            ? label + " dalam mata uang " + ccy
                                    + " harus bilangan bulat tanpa desimal."
                            : label + " dalam mata uang " + ccy
                                    + " maksimal " + max + " angka desimal.");
        }
    }

    /** A computed amount rounded to the currency's decimal rule (HALF_UP). */
    public static BigDecimal round(BigDecimal amount, String currency) {
        return amount.setScale(maxDecimals(currency), RoundingMode.HALF_UP);
    }
}
