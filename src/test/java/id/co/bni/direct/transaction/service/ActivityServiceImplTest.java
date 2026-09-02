package id.co.bni.direct.transaction.service;

import id.co.bni.direct.transaction.dto.request.ActivityRequests.ActivitySearchRequest;
import id.co.bni.direct.transaction.dto.request.ActivityRequests.DateRange;
import id.co.bni.direct.transaction.dto.request.ActivityRequests.MoneyFilter;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityDetailResponse;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityPageResponse;
import id.co.bni.direct.transaction.entity.ActivityRows.ActivityFilter;
import id.co.bni.direct.transaction.entity.ActivityRows.ActivityRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionRow;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.NotFoundException;
import id.co.bni.direct.transaction.repository.mapper.ActivityMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.impl.ActivityServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mock both mappers; drive the filter mapping, the status wording and the trails. */
class ActivityServiceImplTest {

    private static final String COMPANY = "PTDEMO";
    private static final String USER = "ALFAKU";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 1, 17, 43, 14);

    private ActivityMapper activityMapper;
    private TrxTaskMapper trxTaskMapper;
    private ActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(ActivityMapper.class);
        trxTaskMapper = mock(TrxTaskMapper.class);
        service = new ActivityServiceImpl(activityMapper, trxTaskMapper);
    }

    private static ActivityRow row(String source, String rawStatus, String statusGroup) {
        return new ActivityRow(source, "TASK".equals(source) ? "T1" : null, "20260901174314000002", "20260901174533000003",
                "MNU_GCME_050200", "GCM_FTR_IH_3RD", "Transfer to BNI to Third Party Account",
                rawStatus, statusGroup, new BigDecimal("10000000"), "IDR",
                "5930011554", "Cek", "IDR", "18000014067", "NABILA LEKSANA PUTRI", "gaji",
                "TASK".equals(source) ? "IMMEDIATE" : "1", null, CREATED, CREATED.plusMinutes(2), "964026", "ALFAKU", null, null);
    }

    private static ActivitySearchRequest dateSearch(LocalDate start, LocalDate end) {
        return new ActivitySearchRequest(USER, "DATE_RANGE", new DateRange(start, end),
                null, null, null, null, null, null, null, 1, 10);
    }

    // ---- filter mapping ----

    @Test
    void aDateRangeSearchMapsEveryDrawerFieldOntoTheFilter() {
        when(activityMapper.count(any())).thenReturn(1L);
        when(activityMapper.search(any())).thenReturn(List.of(row("TASK", "EXECUTED", "BERHASIL")));
        var request = new ActivitySearchRequest(USER, "DATE_RANGE",
                new DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
                "TRANSFER_BNI", "BERHASIL", "5930011554", "nabila",
                new MoneyFilter(new BigDecimal("1000"), "IDR"), new MoneyFilter(new BigDecimal("99999"), "IDR"),
                null, 2, 25);

        ActivityPageResponse page = service.search(COMPANY, request);

        ArgumentCaptor<ActivityFilter> captor = ArgumentCaptor.forClass(ActivityFilter.class);
        verify(activityMapper).search(captor.capture());
        ActivityFilter f = captor.getValue();
        assertThat(f.getCompanyId()).isEqualTo(COMPANY);
        assertThat(f.getUserId()).isEqualTo(USER);
        assertThat(f.getDateFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(f.getDateTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        // the screen alias resolves to the menu code the rows carry
        assertThat(f.getMenuCd()).isEqualTo("MNU_GCME_050200");
        assertThat(f.getStatusGroup()).isEqualTo("BERHASIL");
        assertThat(f.getSourceAccountNo()).isEqualTo("5930011554");
        assertThat(f.getBeneficiaryName()).isEqualTo("nabila");
        assertThat(f.getAmountFrom()).isEqualByComparingTo("1000");
        assertThat(f.getAmountTo()).isEqualByComparingTo("99999");
        assertThat(f.getOffset()).isEqualTo(25);
        assertThat(f.getSize()).isEqualTo(25);
        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("BERHASIL");
            assertThat(item.transactionTypeLabel()).isEqualTo("Transfer ke BNI");
            assertThat(item.instruction()).isEqualTo("Immediate");
            assertThat(item.sourceAccountName()).isEqualTo("Cek");
        });
    }

    @Test
    void aReferenceSearchNeedsTheReferenceAndSkipsTheDateRange() {
        when(activityMapper.count(any())).thenReturn(0L);
        var request = new ActivitySearchRequest(USER, "REFERENCE", null, null, null, null, null,
                null, null, "20260901174314000002", null, null);

        ActivityPageResponse page = service.search(COMPANY, request);

        ArgumentCaptor<ActivityFilter> captor = ArgumentCaptor.forClass(ActivityFilter.class);
        verify(activityMapper).count(captor.capture());
        assertThat(captor.getValue().getRefNo()).isEqualTo("20260901174314000002");
        assertThat(captor.getValue().getDateFrom()).isNull();
        // no rows: the page query is skipped, the answer is an empty list, never a 404
        verify(activityMapper, never()).search(any());
        assertThat(page.items()).isEmpty();
        assertThat(page.size()).isEqualTo(10);
    }

    @Test
    void aReferenceSearchWithoutAReferenceIsRefused() {
        var request = new ActivitySearchRequest(USER, "REFERENCE", null, null, null, null, null,
                null, null, null, null, null);
        assertThatThrownBy(() -> service.search(COMPANY, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Nomor referensi");
    }

    @Test
    void aDateRangeWiderThanNinetyTwoDaysIsRefused() {
        assertThatThrownBy(() -> service.search(COMPANY,
                dateSearch(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 3))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("92");
        // exactly 92 days inclusive is legal
        when(activityMapper.count(any())).thenReturn(0L);
        service.search(COMPANY, dateSearch(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 2)));
    }

    @Test
    void aReversedDateRangeIsRefused() {
        assertThatThrownBy(() -> service.search(COMPANY,
                dateSearch(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 1))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Tanggal akhir");
    }

    // ---- recent ----

    @Test
    void recentClampsTheLimitAndReadsTheFirstPageOnly() {
        when(activityMapper.search(any())).thenReturn(List.of(row("LEGACY", "EXECUTED", "BERHASIL")));

        var recent = service.recent(COMPANY, USER, 500);

        ArgumentCaptor<ActivityFilter> captor = ArgumentCaptor.forClass(ActivityFilter.class);
        verify(activityMapper).search(captor.capture());
        assertThat(captor.getValue().getSize()).isEqualTo(50);
        assertThat(captor.getValue().getOffset()).isZero();
        assertThat(recent.items()).singleElement().satisfies(item -> {
            assertThat(item.source()).isEqualTo("LEGACY");
            assertThat(item.status()).isEqualTo("BERHASIL");
        });
    }

    // ---- detail ----

    @Test
    void aTaskDetailCarriesTheWorkflowTrailWithTheExecuteVerdict() {
        when(activityMapper.findByRefNo(any())).thenReturn(row("TASK", "FAILED", "GAGAL"));
        when(trxTaskMapper.findActions("T1")).thenReturn(List.of(
                new ActionRow(null, "SUBMIT", "ALFAKU", null, CREATED),
                new ActionRow(1, "APPROVE", "DEMOAP1", null, CREATED.plusMinutes(1)),
                new ActionRow(2, "RELEASE", "DEMORL1", null, CREATED.plusMinutes(2)),
                new ActionRow(null, "EXECUTE", null, "Limit transaksi bank berubah", CREATED.plusMinutes(2))));

        ActivityDetailResponse detail = service.detail(COMPANY, USER, "20260901174314000002");

        assertThat(detail.status()).isEqualTo("GAGAL");
        assertThat(detail.rawStatus()).isEqualTo("FAILED");
        assertThat(detail.activities()).hasSize(4);
        assertThat(detail.activities().get(0).type()).isEqualTo("SUBMIT");
        assertThat(detail.activities().get(0).activityStatus()).isEqualTo("SUKSES");
        assertThat(detail.activities().get(0).transactionStatus()).isEqualTo("DIPROSES");
        var execute = detail.activities().get(3);
        // the seam writes EXECUTE without a name: it inherits the releaser's
        assertThat(execute.actor()).isEqualTo("DEMORL1");
        assertThat(execute.activityStatus()).isEqualTo("GAGAL");
        assertThat(execute.transactionStatus()).isEqualTo("GAGAL");
        assertThat(execute.remark()).contains("Limit transaksi bank berubah");
    }

    @Test
    void aLegacyDetailGetsOneSyntheticExecuteAndNoTaskLookup() {
        when(activityMapper.findByRefNo(any())).thenReturn(row("LEGACY", "EXECUTED", "BERHASIL"));

        ActivityDetailResponse detail = service.detail(COMPANY, USER, "20260901174314000002");

        verify(trxTaskMapper, never()).findActions(any());
        assertThat(detail.source()).isEqualTo("LEGACY");
        assertThat(detail.activities()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo("EXECUTE");
            assertThat(a.activityStatus()).isEqualTo("SUKSES");
            assertThat(a.transactionStatus()).isEqualTo("BERHASIL");
        });
    }

    @Test
    void anUnknownOrOutOfScopeReferenceIs404() {
        when(activityMapper.findByRefNo(any())).thenReturn(null);
        assertThatThrownBy(() -> service.detail(COMPANY, USER, "999"))
                .isInstanceOf(NotFoundException.class);
    }
}
