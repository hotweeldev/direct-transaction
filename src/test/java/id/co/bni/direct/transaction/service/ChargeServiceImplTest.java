package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import id.co.bni.direct.transaction.entity.ChargeRows.TariffRow;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.repository.mapper.ChargeMapper;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.service.impl.ChargeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The charge engine over mocked tariff rows: the fallback ladder, the two ambiguities the
 * legacy tables leave open (duplicate rows, a charge type priced in several currencies),
 * and the currency conversion.
 */
class ChargeServiceImplTest {

    private static final String COMPANY = "PTDEMO";
    private static final String SRVC_LLG = "GCM_FTR_DOM_LLG";
    private static final LocalDateTime Y2015 = LocalDateTime.of(2015, 9, 23, 18, 54);
    private static final LocalDateTime Y2021 = LocalDateTime.of(2021, 5, 10, 10, 39);

    private ChargeMapper chargeMapper;
    private TransferMapper transferMapper;
    private CoreTransferClient coreTransferClient;
    private ChargeServiceImpl service;

    @BeforeEach
    void setUp() {
        chargeMapper = mock(ChargeMapper.class);
        transferMapper = mock(TransferMapper.class);
        coreTransferClient = mock(CoreTransferClient.class);
        service = new ChargeServiceImpl(chargeMapper, transferMapper, coreTransferClient);
    }

    private static TariffRow row(String code, String name, String ccy, String value, LocalDateTime updated) {
        return new TariffRow(code, name, ccy, "F", value, updated);
    }

    // ---- the fallback ladder ----

    @Test
    void theCompanyTariffIsUsedWhenItExists() {
        when(chargeMapper.findCompanyTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of(
                row("014", "Transfer Fee", "IDR", "1000", Y2021),
                row("009", "LLG Fee", "IDR", "1000", Y2021)));

        var quote = service.quote(COMPANY, SRVC_LLG, "IDR");

        // Two components, and the total is their sum - not one flat number.
        assertThat(quote.components()).hasSize(2);
        assertThat(quote.totalIdr()).isEqualByComparingTo("2000");
        assertThat(quote.components()).allMatch(c -> "COMPANY".equals(c.tariffSource()));
        verify(chargeMapper, never()).findPackageTariffs(anyString(), anyString());
    }

    @Test
    void thePackageIsTheFallbackWhenTheCompanyHasNoRow() {
        when(chargeMapper.findCompanyTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of());
        when(chargeMapper.findPackageTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of(
                row("009", "LLG Fee", "IDR", "2900", Y2015)));

        var quote = service.quote(COMPANY, SRVC_LLG, "IDR");

        assertThat(quote.totalIdr()).isEqualByComparingTo("2900");
        assertThat(quote.components().get(0).tariffSource()).isEqualTo("PACKAGE");
    }

    @Test
    void aServiceNeitherPricesIsRefusedRatherThanChargedNothing() {
        when(chargeMapper.findCompanyTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of());
        when(chargeMapper.findPackageTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of());

        assertThatThrownBy(() -> service.quote(COMPANY, SRVC_LLG, "IDR"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("CHARGE_NOT_CONFIGURED"));
    }

    // ---- the two ambiguities the tariff tables leave open ----

    @Test
    void theNewestOfTwoDuplicateRowsWins() {
        // The live tables have no unique key and no soft-delete column, so a 2015 row sits
        // beside a 2021 one. Charging the 2015 tariff would undercharge every customer.
        when(chargeMapper.findCompanyTariffs(COMPANY, "GCM_FTR_DOM_RTGS")).thenReturn(List.of(
                row("012", "RTGS Fee", "IDR", "30000", Y2015),
                row("012", "RTGS Fee", "IDR", "35000", Y2021)));

        var quote = service.quote(COMPANY, "GCM_FTR_DOM_RTGS", "IDR");

        assertThat(quote.components()).hasSize(1);
        assertThat(quote.totalIdr()).isEqualByComparingTo("35000");
    }

    @Test
    void theTariffInTheTransactionCurrencyWinsOverTheOthers() {
        when(chargeMapper.findCompanyTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of(
                row("014", "Transfer Fee", "IDR", "1000", Y2021),
                row("014", "Transfer Fee", "USD", "2", Y2021)));

        var quote = service.quote(COMPANY, SRVC_LLG, "IDR");

        assertThat(quote.components().get(0).ccyCd()).isEqualTo("IDR");
        assertThat(quote.totalIdr()).isEqualByComparingTo("1000");
        // Nothing to convert, so the rate hop is never made.
        verify(coreTransferClient, never()).fetchRates(anyString());
    }

    @Test
    void aValueTypeThisEngineCannotReadIsRefused() {
        // 'B' carries an id into another table; reading it as money would invent a charge.
        when(chargeMapper.findCompanyTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of(
                new TariffRow("014", "Transfer Fee", "IDR", "B", "4028c4d72849bfe2", Y2021)));

        assertThatThrownBy(() -> service.quote(COMPANY, SRVC_LLG, "IDR"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("CHARGE_TYPE_UNSUPPORTED"));
    }

    // ---- currency ----

    @Test
    void aForeignTariffIsConvertedAtTheSellRateOfTheRegularRateType() {
        when(chargeMapper.findCompanyTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of(
                row("014", "Transfer Fee", "USD", "1.50", Y2021)));
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of(
                new CoreTransferClient.Rate("USD", "IDR", "1", "15000", "16000", "15500")));

        var quote = service.quote(COMPANY, SRVC_LLG, "IDR");

        var component = quote.components().get(0);
        // Both sides are kept: USD 1.50 as the tariff, and the IDR figure with its rate.
        assertThat(component.amt()).isEqualByComparingTo("1.50");
        assertThat(component.idrAmt()).isEqualByComparingTo("23250");
        assertThat(component.fxRate()).isEqualByComparingTo("15500");
        // Fees convert at the standard rate, never at a maker's negotiated one.
        assertThat(component.fxRateType()).isEqualTo("02");
        assertThat(component.fxRateSide()).isEqualTo("SELL");
        verify(coreTransferClient).fetchRates("02");
    }

    @Test
    void theRateSideIsConfigurable() {
        when(transferMapper.findSysParamValue(ChargeServiceImpl.SYS_PARAM_FX_RATE_SIDE)).thenReturn("MID");
        when(chargeMapper.findCompanyTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of(
                row("014", "Transfer Fee", "USD", "1", Y2021)));
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of(
                new CoreTransferClient.Rate("USD", "IDR", "1", "15000", "16000", "15500")));

        assertThat(service.quote(COMPANY, SRVC_LLG, "IDR").totalIdr()).isEqualByComparingTo("15000");
    }

    @Test
    void aMissingRateStopsTheSubmitRatherThanGuessing() {
        when(chargeMapper.findCompanyTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of(
                row("014", "Transfer Fee", "SGD", "5", Y2021)));
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of(
                new CoreTransferClient.Rate("USD", "IDR", "1", "15000", "16000", "15500")));

        assertThatThrownBy(() -> service.quote(COMPANY, SRVC_LLG, "IDR"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("CHARGE_RATE_UNAVAILABLE"));
    }

    @Test
    void quotedRatesAreScaledByTheirQuotationUnits() {
        when(chargeMapper.findCompanyTariffs(COMPANY, SRVC_LLG)).thenReturn(List.of(
                row("014", "Transfer Fee", "JPY", "100", Y2021)));
        // JPY is quoted per hundred: a "sell 10,000" means 100.00 rupiah per yen.
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of(
                new CoreTransferClient.Rate("JPY", "IDR", "100", "9500", "9000", "10000")));

        assertThat(service.quote(COMPANY, SRVC_LLG, "IDR").totalIdr()).isEqualByComparingTo("10000");
    }

    // ---- release-time re-quote ----

    @Test
    void requoteIsOffUnlessTheParameterSaysOtherwise() {
        assertThat(service.requoteOnRelease()).isFalse();
        when(transferMapper.findSysParamValue(ChargeServiceImpl.SYS_PARAM_FX_REQUOTE)).thenReturn("Y");
        assertThat(service.requoteOnRelease()).isTrue();
    }

    @Test
    void anUnreadableToleranceFallsBackRatherThanFailing() {
        when(transferMapper.findSysParamValue(ChargeServiceImpl.SYS_PARAM_FX_REQUOTE_TOLERANCE))
                .thenReturn("lima persen");
        assertThat(service.requoteTolerancePercent()).isEqualByComparingTo("5");
    }

    @Test
    void requotingLeavesIdrComponentsAloneAndNeverCallsForARate() {
        var frozen = new ChargeService.ChargeComponent(1, "009", "LLG Fee", "IDR",
                new BigDecimal("2900"), new BigDecimal("2900"), null, null, null, "COMPANY");

        var requoted = service.requote(List.of(frozen));

        assertThat(requoted.totalIdr()).isEqualByComparingTo("2900");
        verify(coreTransferClient, never()).fetchRates(anyString());
    }

    @Test
    void requotingRepricesAForeignComponentAtTodaysRate() {
        var frozen = new ChargeService.ChargeComponent(1, "014", "Transfer Fee", "USD",
                new BigDecimal("2"), new BigDecimal("30000"), new BigDecimal("15000"),
                "02", "SELL", "COMPANY");
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of(
                new CoreTransferClient.Rate("USD", "IDR", "1", "15000", "16000", "15500")));

        var requoted = service.requote(List.of(frozen));

        assertThat(requoted.totalIdr()).isEqualByComparingTo("31000");
        // The tariff itself never moves - only the rate it is converted at.
        assertThat(requoted.components().get(0).amt()).isEqualByComparingTo("2");
    }

    @Test
    void aComponentWhoseRateIsMissingKeepsTheApprovedFigure() {
        var frozen = new ChargeService.ChargeComponent(1, "014", "Transfer Fee", "USD",
                new BigDecimal("2"), new BigDecimal("30000"), new BigDecimal("15000"),
                "02", "SELL", "COMPANY");
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of());

        // A released transfer must not fail because this morning's rate has not landed.
        assertThat(service.requote(List.of(frozen)).totalIdr()).isEqualByComparingTo("30000");
    }

    // ---- the charge bearer ----

    @Test
    void anAbsentOrUnknownBearerMeansTheSender() {
        assertThat(ChargeService.ChargeBearer.parse(null)).isEqualTo(ChargeService.ChargeBearer.REMITTER);
        assertThat(ChargeService.ChargeBearer.parse("  ")).isEqualTo(ChargeService.ChargeBearer.REMITTER);
        assertThat(ChargeService.ChargeBearer.parse("nonsense")).isEqualTo(ChargeService.ChargeBearer.REMITTER);
        assertThat(ChargeService.ChargeBearer.parse("beneficiary"))
                .isEqualTo(ChargeService.ChargeBearer.BENEFICIARY);
    }
}
