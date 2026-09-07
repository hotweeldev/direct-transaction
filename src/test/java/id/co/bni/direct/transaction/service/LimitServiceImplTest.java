package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.util.List;

import id.co.bni.direct.transaction.entity.LimitRows;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.integration.CoreTransferClient;
import id.co.bni.direct.transaction.repository.mapper.LimitMapper;
import id.co.bni.direct.transaction.repository.mapper.TransferMapper;
import id.co.bni.direct.transaction.service.impl.LimitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reservation: which row a transfer consumes, in which currency, and what happens when
 * it is given back.
 */
class LimitServiceImplTest {

    private static final String COMPANY = "PTDEMO";
    private static final String GROUP = "GRP1";
    private static final String SRVC = "GCM_FTR_DOM_RTGS";

    private LimitMapper limitMapper;
    private TransferMapper transferMapper;
    private CoreTransferClient coreTransferClient;
    private LimitServiceImpl service;

    @BeforeEach
    void setUp() {
        limitMapper = mock(LimitMapper.class);
        transferMapper = mock(TransferMapper.class);
        coreTransferClient = mock(CoreTransferClient.class);
        service = new LimitServiceImpl(limitMapper, transferMapper, coreTransferClient);
    }

    private static LimitRows.LimitRow row(String id, String ccy, String maxAmt, String usedAmt,
                                          Long maxOc, Long usedOc) {
        return new LimitRows.LimitRow(id, "MTRX1", "LL", ccy,
                maxAmt == null ? null : new BigDecimal(maxAmt),
                usedAmt == null ? null : new BigDecimal(usedAmt), maxOc, usedOc);
    }

    // ---- which row applies ----

    @Test
    void theRowIsFoundByCurrencyCombinationNotByTheTransactionCurrency() {
        // A Forex-Local transfer: source USD, transaction IDR. Its ceiling is the FL row,
        // and that row is denominated in IDR - matching by the transaction's currency used
        // to find nothing here, and a missing row read as "no limit at all".
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "FL"))
                .thenReturn(row("CL1", "IDR", "1000000000", "0", 1000L, 0L));

        var reservation = service.reserve(COMPANY, null, SRVC, "USD", "IDR",
                new BigDecimal("10000000"), "budi");

        assertThat(reservation.ccyMtrxCd()).isEqualTo("FL");
        verify(limitMapper).lockCorpLimit(COMPANY, SRVC, "FL");
        verify(limitMapper).addCorpUsage("CL1", new BigDecimal("10000000"), 1L);
    }

    @Test
    void everyCombinationIsDerivedFromTheTwoCurrencies() {
        assertThat(LimitService.CurrencyCombination.of("IDR", "IDR"))
                .isEqualTo(LimitService.CurrencyCombination.LL);
        assertThat(LimitService.CurrencyCombination.of(null, "IDR"))
                .isEqualTo(LimitService.CurrencyCombination.LL);
        assertThat(LimitService.CurrencyCombination.of("IDR", "USD"))
                .isEqualTo(LimitService.CurrencyCombination.LF);
        assertThat(LimitService.CurrencyCombination.of("USD", "IDR"))
                .isEqualTo(LimitService.CurrencyCombination.FL);
        assertThat(LimitService.CurrencyCombination.of("USD", "USD"))
                .isEqualTo(LimitService.CurrencyCombination.FS);
        assertThat(LimitService.CurrencyCombination.of("USD", "SGD"))
                .isEqualTo(LimitService.CurrencyCombination.FC);
    }

    @Test
    void thePackageCeilingIsCopiedDownWhenTheCompanyHasNoRow() {
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "LL"))
                .thenReturn(null)
                .thenReturn(row("NEW1", "IDR", "5000000000", "0", 100L, 0L));
        when(limitMapper.findPackageLimit(COMPANY, SRVC, "LL")).thenReturn(
                new LimitRows.PackageLimitRow("MTRX1", "LL", "IDR", new BigDecimal("5000000000"), 100L));

        var reservation = service.reserve(COMPANY, null, SRVC, "IDR", "IDR",
                new BigDecimal("1000000"), "budi");

        // The package has nowhere to record usage, so the company row is materialised from
        // it first - the same thing the legacy INJECT_LIMIT_COMPANY procedure does.
        verify(limitMapper).insertCorpLimitFromPackage(anyString(), eq(COMPANY), eq(SRVC),
                eq("MTRX1"), eq("IDR"), eq(new BigDecimal("5000000000")), eq(100L), eq("budi"));
        assertThat(reservation.isEmpty()).isFalse();
    }

    @Test
    void noCeilingAnywhereMeansNoReservationRatherThanARefusal() {
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "LL")).thenReturn(null);
        when(limitMapper.findPackageLimit(COMPANY, SRVC, "LL")).thenReturn(null);

        var reservation = service.reserve(COMPANY, null, SRVC, "IDR", "IDR",
                new BigDecimal("1000000"), "budi");

        // A ceiling is a restriction; its absence has always meant unrestricted here. That
        // is the opposite of the charge engine, where a missing tariff stops the transfer.
        assertThat(reservation.isEmpty()).isTrue();
        verify(limitMapper, never()).addCorpUsage(anyString(), any(), anyLong());
    }

    // ---- the two ceilings ----

    @Test
    void anAmountBeyondTheRemainingHeadroomIsRefused() {
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "LL"))
                .thenReturn(row("CL1", "IDR", "10000000", "9500000", 1000L, 3L));

        assertThatThrownBy(() -> service.reserve(COMPANY, null, SRVC, "IDR", "IDR",
                new BigDecimal("1000000"), "budi"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("LIMIT_AMOUNT_EXCEEDED"));
        verify(limitMapper, never()).addCorpUsage(anyString(), any(), anyLong());
    }

    @Test
    void theDailyTransactionCountIsACeilingOfItsOwn() {
        // Room for the money, none for another transaction: the count ceiling is what stops
        // a thousand small transfers that never come near the amount ceiling.
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "LL"))
                .thenReturn(row("CL1", "IDR", "10000000000", "0", 5L, 5L));

        assertThatThrownBy(() -> service.reserve(COMPANY, null, SRVC, "IDR", "IDR",
                new BigDecimal("1000"), "budi"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("LIMIT_COUNT_EXCEEDED"));
    }

    @Test
    void aZeroCeilingBlocksTheServiceRatherThanMeaningUnlimited() {
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "LL"))
                .thenReturn(row("CL1", "IDR", "0", "0", 1000L, 0L));

        assertThatThrownBy(() -> service.reserve(COMPANY, null, SRVC, "IDR", "IDR",
                BigDecimal.ONE, "budi"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("LIMIT_BLOCKED"));
    }

    @Test
    void bothTheCompanyAndTheGroupCeilingAreConsumed() {
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "LL"))
                .thenReturn(row("CL1", "IDR", "10000000000", "0", 1000L, 0L));
        when(limitMapper.lockGroupLimit(GROUP, SRVC, "LL"))
                .thenReturn(row("GL1", "IDR", "5000000000", "0", null, null));

        service.reserve(COMPANY, GROUP, SRVC, "IDR", "IDR", new BigDecimal("2000000"), "budi");

        verify(limitMapper).addCorpUsage("CL1", new BigDecimal("2000000"), 1L);
        verify(limitMapper).addGroupUsage("GL1", new BigDecimal("2000000"), 1L);
    }

    // ---- currency ----

    @Test
    void theAmountIsConvertedIntoTheCeilingsOwnCurrency() {
        // REGRESSION6's RTGS Forex-Local ceiling is denominated in USD, so a rupiah
        // transfer has to be expressed in dollars before it is measured against it.
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "LL"))
                .thenReturn(row("CL1", "USD", "100000", "0", 1000L, 0L));
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of(
                new CoreTransferClient.Rate("USD", "IDR", "1", "15000", "16000", "15500")));

        service.reserve(COMPANY, null, SRVC, "IDR", "IDR", new BigDecimal("15500000"), "budi");

        ArgumentCaptor<BigDecimal> reserved = ArgumentCaptor.forClass(BigDecimal.class);
        verify(limitMapper).addCorpUsage(eq("CL1"), reserved.capture(), eq(1L));
        assertThat(reserved.getValue()).isEqualByComparingTo("1000.00");
    }

    @Test
    void aMissingRateStopsTheSubmitRatherThanComparingTwoCurrencies() {
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "LL"))
                .thenReturn(row("CL1", "USD", "100000", "0", 1000L, 0L));
        when(coreTransferClient.fetchRates("02")).thenReturn(List.of());

        assertThatThrownBy(() -> service.reserve(COMPANY, null, SRVC, "IDR", "IDR",
                new BigDecimal("15500000"), "budi"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("LIMIT_RATE_UNAVAILABLE"));
    }

    @Test
    void aCeilingInAThirdCurrencyIsRefusedRatherThanCrossed() {
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "FS"))
                .thenReturn(row("CL1", "SGD", "100000", "0", 1000L, 0L));

        assertThatThrownBy(() -> service.reserve(COMPANY, null, SRVC, "USD", "USD",
                new BigDecimal("1000"), "budi"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code())
                        .isEqualTo("LIMIT_CURRENCY_UNSUPPORTED"));
    }

    // ---- release ----

    @Test
    void aReleaseGivesBackExactlyWhatWasTaken() {
        when(limitMapper.lockCorpLimit(COMPANY, SRVC, "LL"))
                .thenReturn(row("CL1", "IDR", "10000000000", "2000000", 1000L, 1L));
        when(limitMapper.lockGroupLimit(GROUP, SRVC, "LL"))
                .thenReturn(row("GL1", "IDR", "5000000000", "2000000", null, null));

        service.release(COMPANY, GROUP, SRVC, new LimitService.Reservation(
                "MTRX1", "LL", "IDR", new BigDecimal("2000000")));

        verify(limitMapper).addCorpUsage("CL1", new BigDecimal("-2000000"), -1L);
        verify(limitMapper).addGroupUsage("GL1", new BigDecimal("-2000000"), -1L);
    }

    @Test
    void releasingNothingTouchesNothing() {
        service.release(COMPANY, GROUP, SRVC, LimitService.Reservation.none());
        service.release(COMPANY, GROUP, SRVC, null);

        verify(limitMapper, never()).lockCorpLimit(anyString(), anyString(), anyString());
    }
}
