package id.co.bni.direct.transaction.service.impl;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parsed display parameters of one domestic transfer method, from the legacy
 * SYS_PARAM rows {@code SYS_PARAM_TRF_SME_LLG} / {@code SYS_PARAM_TRF_SME_RTGS} /
 * {@code SYS_PARAM_TRF_SME_ONLINE}. The VALUE column is pipe-separated -
 * {@code estimatedDuration|min|max|fee} - with amount tokens shaped like
 * {@code "IDR 1,000,000,000"}, sometimes carrying a decimal-comma suffix
 * ({@code "IDR 10,000,000,000,00"} is 10,000,000,000.00), and sometimes trailing prose
 * (the real ONLINE row: {@code "IDR 1000,000,000 per transaksi, sehari 1 M"}).
 *
 * <p>This feeds an FE display/validation hint only, so parsing is lenient by design:
 * a token that cannot be understood becomes null for that field, never an exception -
 * a mistyped parameter row must not take the transfer form down.
 */
public record SysParamMethodInfo(
        String estimatedDuration,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal fee) {

    /** What a missing or soft-deleted SYS_PARAM row parses to: nothing known. */
    public static final SysParamMethodInfo EMPTY = new SysParamMethodInfo(null, null, null, null);

    /** The leading numeric run of an amount token: digits and comma groups. */
    private static final Pattern LEADING_NUMBER = Pattern.compile("^[0-9][0-9,]*");

    /** One SYS_PARAM VALUE ({@code duration|min|max|fee}); null/blank answers {@link #EMPTY}. */
    public static SysParamMethodInfo parse(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY;
        }
        String[] parts = value.split("\\|", -1);
        String duration = parts[0].isBlank() ? null : parts[0].trim();
        return new SysParamMethodInfo(
                duration,
                parts.length > 1 ? parseAmount(parts[1]) : null,
                parts.length > 2 ? parseAmount(parts[2]) : null,
                parts.length > 3 ? parseAmount(parts[3]) : null);
    }

    /**
     * One amount token: strip the "IDR" prefix and whitespace, then keep only the
     * LEADING numeric run - the ONLINE row appends prose to its max token
     * ({@code "IDR 1000,000,000 per transaksi, sehari 1 M"} is 1,000,000,000), which
     * must not turn the whole token null. A trailing {@code ,00} on that run is the
     * legacy decimal-comma suffix (dropped - the value is integral either way);
     * remaining commas are thousand separators, however irregular the grouping.
     * Anything that still fails is null.
     */
    static BigDecimal parseAmount(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        if (t.regionMatches(true, 0, "IDR", 0, 3)) {
            t = t.substring(3).trim();
        }
        Matcher leading = LEADING_NUMBER.matcher(t);
        if (!leading.find()) {
            return null;
        }
        t = leading.group();
        if (t.endsWith(",00")) {
            t = t.substring(0, t.length() - 3);
        }
        t = t.replace(",", "").trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
