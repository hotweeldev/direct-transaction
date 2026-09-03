package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;

import id.co.bni.direct.transaction.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The velis decimal rules (task 5.6) in isolation. */
class AmountRulesTest {

    @Test
    void idrAndJpyAreWholeNumberCurrencies() {
        assertThat(AmountRules.maxDecimals("IDR")).isZero();
        assertThat(AmountRules.maxDecimals("JPY")).isZero();
        assertThat(AmountRules.maxDecimals("jpy")).isZero();
        assertThat(AmountRules.maxDecimals("USD")).isEqualTo(2);
        assertThat(AmountRules.maxDecimals("SGD")).isEqualTo(2);
    }

    @Test
    void anIdrAmountWithDecimalsIsA422() {
        assertThatThrownBy(() -> AmountRules.validateScale(
                new BigDecimal("10000.50"), "IDR", "Nominal transfer"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> {
                    assertThat(((BusinessRuleException) e).code())
                            .isEqualTo("TRANSFER_FIELDS_INVALID");
                    assertThat(e.getMessage()).contains("IDR").contains("bilangan bulat");
                });
    }

    @Test
    void aJpyAmountWithDecimalsIsA422ButTrailingZerosPass() {
        assertThatThrownBy(() -> AmountRules.validateScale(
                new BigDecimal("100.5"), "JPY", "Nominal debit"))
                .isInstanceOf(BusinessRuleException.class);
        // 100.00 JPY is the integer 100 wearing decimals - not an offence.
        assertThatCode(() -> AmountRules.validateScale(
                new BigDecimal("100.00"), "JPY", "Nominal debit"))
                .doesNotThrowAnyException();
    }

    @Test
    void otherCurrenciesAllowTwoDecimalsAndRejectThree() {
        assertThatCode(() -> AmountRules.validateScale(
                new BigDecimal("123.45"), "USD", "Nominal transfer"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AmountRules.validateScale(
                new BigDecimal("123.456"), "USD", "Nominal transfer"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("2 angka desimal"));
    }

    @Test
    void nullAmountOrCurrencyIsNotThisRulesProblem() {
        assertThatCode(() -> AmountRules.validateScale(null, "IDR", "x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> AmountRules.validateScale(BigDecimal.ONE, null, "x"))
                .doesNotThrowAnyException();
    }

    @Test
    void roundingFollowsTheCurrencyRuleHalfUp() {
        assertThat(AmountRules.round(new BigDecimal("2036992.89975"), "IDR"))
                .isEqualByComparingTo("2036993");
        assertThat(AmountRules.round(new BigDecimal("2036992.4"), "IDR"))
                .isEqualByComparingTo("2036992");
        assertThat(AmountRules.round(new BigDecimal("1.005"), "USD"))
                .isEqualByComparingTo("1.01");
    }
}
