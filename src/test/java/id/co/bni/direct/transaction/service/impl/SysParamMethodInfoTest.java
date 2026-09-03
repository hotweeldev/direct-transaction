package id.co.bni.direct.transaction.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SYS_PARAM VALUE parser, pinned to the two real DEV rows verbatim plus every
 * lenient-failure path: a malformed token is null, never an exception.
 */
class SysParamMethodInfoTest {

    @Test
    void parsesTheDevLlgValueVerbatim() {
        var info = SysParamMethodInfo.parse("2 - 3 hari|IDR 1|IDR 10,000,000,000,00|IDR 2,900");

        assertThat(info.estimatedDuration()).isEqualTo("2 - 3 hari");
        assertThat(info.minAmount()).isEqualByComparingTo("1");
        assertThat(info.maxAmount()).isEqualByComparingTo("10000000000");
        assertThat(info.fee()).isEqualByComparingTo("2900");
    }

    @Test
    void parsesTheDevRtgsValueVerbatim() {
        var info = SysParamMethodInfo.parse("3 - 4 jam|IDR 1,000,000,000|IDR 10,000,000,000,00|IDR 30,000");

        assertThat(info.estimatedDuration()).isEqualTo("3 - 4 jam");
        assertThat(info.minAmount()).isEqualByComparingTo("1000000000");
        assertThat(info.maxAmount()).isEqualByComparingTo("10000000000");
        assertThat(info.fee()).isEqualByComparingTo("30000");
    }

    @Test
    void aMissingRowParsesToAllNulls() {
        for (String raw : new String[]{null, "", "   "}) {
            var info = SysParamMethodInfo.parse(raw);
            assertThat(info.estimatedDuration()).isNull();
            assertThat(info.minAmount()).isNull();
            assertThat(info.maxAmount()).isNull();
            assertThat(info.fee()).isNull();
        }
    }

    @Test
    void aGarbageTokenBecomesNullNotAnException() {
        var info = SysParamMethodInfo.parse("besok|IDR abc|garbage|IDR 2,900");

        assertThat(info.estimatedDuration()).isEqualTo("besok");
        assertThat(info.minAmount()).isNull();
        assertThat(info.maxAmount()).isNull();
        assertThat(info.fee()).isEqualByComparingTo("2900");
    }

    @Test
    void missingTrailingTokensAreNull() {
        var info = SysParamMethodInfo.parse("2 - 3 hari|IDR 1");

        assertThat(info.estimatedDuration()).isEqualTo("2 - 3 hari");
        assertThat(info.minAmount()).isEqualByComparingTo("1");
        assertThat(info.maxAmount()).isNull();
        assertThat(info.fee()).isNull();
    }

    @Test
    void theDecimalCommaSuffixIsDecimalsNotAThousandGroup() {
        assertThat(SysParamMethodInfo.parseAmount("IDR 10,000,000,000,00"))
                .isEqualByComparingTo("10000000000");
        // ",000" is a thousand group, not the ",00" decimal suffix.
        assertThat(SysParamMethodInfo.parseAmount("IDR 1,000,000,000"))
                .isEqualByComparingTo("1000000000");
    }

    @Test
    void aBareMinimumTokenParses() {
        assertThat(SysParamMethodInfo.parseAmount("IDR 1")).isEqualByComparingTo("1");
        assertThat(SysParamMethodInfo.parseAmount(" idr 2,900 ")).isEqualByComparingTo("2900");
        assertThat(SysParamMethodInfo.parseAmount("IDR")).isNull();
        assertThat(SysParamMethodInfo.parseAmount(null)).isNull();
    }

    @Test
    void parsesTheDevOnlineValueVerbatimIncludingTheProseToken() {
        // The real DEV row: the max token carries trailing prose and a nonstandard
        // thousand grouping - the leading numeric run wins, the prose is ignored.
        var info = SysParamMethodInfo.parse(
                "Real Time|IDR 20,000|IDR 1000,000,000 per transaksi, sehari 1 M|IDR 6,500");

        assertThat(info.estimatedDuration()).isEqualTo("Real Time");
        assertThat(info.minAmount()).isEqualByComparingTo("20000");
        assertThat(info.maxAmount()).isEqualByComparingTo("1000000000");
        assertThat(info.fee()).isEqualByComparingTo("6500");
    }

    @Test
    void trailingProseIsIgnoredButLeadingProseStillFails() {
        assertThat(SysParamMethodInfo.parseAmount("IDR 1000,000,000 per transaksi, sehari 1 M"))
                .isEqualByComparingTo("1000000000");
        // The decimal-comma suffix still binds on a prose-trimmed token.
        assertThat(SysParamMethodInfo.parseAmount("IDR 10,000,000,000,00 per hari"))
                .isEqualByComparingTo("10000000000");
        assertThat(SysParamMethodInfo.parseAmount("sekitar IDR 20,000")).isNull();
    }
}
