package id.co.bni.direct.transaction.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import id.co.bni.direct.transaction.dto.request.ActivityRequests.ActivitySearchRequest;
import id.co.bni.direct.transaction.dto.request.ActivityRequests.DateRange;
import id.co.bni.direct.transaction.entity.ActivityRows.ActivityFilter;
import id.co.bni.direct.transaction.entity.ActivityRows.ActivityRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.StageRow;
import id.co.bni.direct.transaction.repository.mapper.ActivityMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.impl.ActivityServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The stage split seen from Aktivitas Transaksi: the six-value status, the "where is it
 * now" line on a list row, and the view-only ladder on the detail.
 */
class ActivityStageStatusTest {

    private static final String COMPANY = "CORP1";
    private static final String USER = "ani";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 1, 17, 43);

    private ActivityMapper activityMapper;
    private TrxTaskMapper trxTaskMapper;
    private ActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(ActivityMapper.class);
        trxTaskMapper = mock(TrxTaskMapper.class);
        service = new ActivityServiceImpl(activityMapper, trxTaskMapper);
    }

    private static ActivityRow row(String source, String rawStatus, String statusGroup,
                                   Integer currentStageSeq, Integer approvalStages, String stageType) {
        return new ActivityRow(source, "TASK".equals(source) ? "T1" : null,
                "20260901174314000002", null, "MNU_GCME_050100", "GCM_FTR_IH_3RD", "Transfer",
                rawStatus, statusGroup, new BigDecimal("10000000"), "IDR",
                "5930011554", "Cek", "IDR", "18000014067", "NABILA", "gaji",
                "IMMEDIATE", null, CREATED, null, null, "BUDI", null, null,
                currentStageSeq, approvalStages, stageType);
    }

    // ---- status filter ----

    @Test
    void aNarrowStatusPassesThroughAsItself() {
        assertThat(ActivityServiceImpl.statusGroups("PENDING_APPROVAL"))
                .containsExactly("PENDING_APPROVAL");
        assertThat(ActivityServiceImpl.statusGroups("DITOLAK")).containsExactly("DITOLAK");
        assertThat(ActivityServiceImpl.statusGroups(null)).isNull();
    }

    @Test
    void theOldDiprosesChipStillCoversBothWaitingStages() {
        // An FE built before the split means "anything still moving" by DIPROSES. Answering
        // it with only the narrowed value would hide every transaction awaiting approval.
        assertThat(ActivityServiceImpl.statusGroups("DIPROSES"))
                .containsExactly("PENDING_APPROVAL", "PENDING_RELEASE", "DIPROSES");
    }

    @Test
    void theFilterCarriesTheExpandedStatusList() {
        when(activityMapper.count(any())).thenReturn(0L);
        service.search(COMPANY, new ActivitySearchRequest(USER, "DATE_RANGE",
                new DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
                null, "DIPROSES", null, null, null, null, null, 1, 10));

        ArgumentCaptor<ActivityFilter> captor = ArgumentCaptor.forClass(ActivityFilter.class);
        verify(activityMapper).count(captor.capture());
        assertThat(captor.getValue().getStatusGroups())
                .containsExactly("PENDING_APPROVAL", "PENDING_RELEASE", "DIPROSES");
    }

    // ---- stageProgress ----

    @Test
    void aTaskOnTheFirstOfTwoApprovalsSaysSo() {
        assertThat(ActivityServiceImpl.stageProgress(
                row("TASK", "PENDING_APPROVAL", "PENDING_APPROVAL", 1, 2, "APPROVAL")))
                .isEqualTo("Approval 1 dari 2");
    }

    @Test
    void aSingleApprovalLevelIsNotCounted() {
        assertThat(ActivityServiceImpl.stageProgress(
                row("TASK", "PENDING_APPROVAL", "PENDING_APPROVAL", 1, 1, "APPROVAL")))
                .isEqualTo("Approval");
    }

    @Test
    void aTaskAwaitingReleaseSaysRilis() {
        assertThat(ActivityServiceImpl.stageProgress(
                row("TASK", "PENDING_RELEASE", "PENDING_RELEASE", 2, 1, "RELEASE")))
                .isEqualTo("Rilis");
    }

    @Test
    void nothingIsWaitedOnOnceTheTaskIsBeingExecutedOrDone() {
        assertThat(ActivityServiceImpl.stageProgress(
                row("TASK", "READY_TO_EXECUTE", "DIPROSES", null, 2, null))).isNull();
        assertThat(ActivityServiceImpl.stageProgress(
                row("TASK", "EXECUTED", "BERHASIL", null, 2, null))).isNull();
        assertThat(ActivityServiceImpl.stageProgress(
                row("LEGACY", "EXECUTED", "BERHASIL", null, null, null))).isNull();
    }

    // ---- the view-only ladder ----

    @Test
    void aTaskStillWalkingTheLadderCarriesEveryStageIncludingTheOnesNotReached() {
        when(activityMapper.findByRefNo(any()))
                .thenReturn(row("TASK", "PENDING_APPROVAL", "PENDING_APPROVAL", 2, 2, "APPROVAL"));
        when(trxTaskMapper.findStages("T1")).thenReturn(List.of(
                new StageRow(1, "APPROVAL", "AL01", "2", 1, 1, "DONE"),
                new StageRow(2, "APPROVAL", "AL02", "3", 2, 1, "ACTIVE"),
                new StageRow(3, "RELEASE", null, null, 1, 0, "WAITING")));
        when(trxTaskMapper.findActions("T1")).thenReturn(List.of(
                new ActionRow(null, "SUBMIT", "BUDI", null, CREATED),
                new ActionRow(1, "APPROVE", "SITI", null, CREATED.plusMinutes(5)),
                new ActionRow(2, "APPROVE", "ANDI", "ok", CREATED.plusMinutes(9))));
        when(trxTaskMapper.findPendingCandidateNames("T1", 2))
                .thenReturn(List.of("DEWI", "RINA"));

        var detail = service.detail(COMPANY, USER, "20260901174314000002");

        assertThat(detail.workflow()).isNotNull();
        assertThat(detail.workflow().currentStageSeq()).isEqualTo(2);
        assertThat(detail.workflow().totalApprovalStages()).isEqualTo(2);
        assertThat(detail.workflow().stages()).extracting("label")
                .containsExactly("Approval 1", "Approval 2", "Rilis");
        // The SUBMIT row belongs to no stage and must not be attached to one.
        assertThat(detail.workflow().stages().get(0).actions()).hasSize(1);
        assertThat(detail.workflow().stages().get(2).actions()).isEmpty();
        // Only the ACTIVE stage answers "menunggu siapa".
        assertThat(detail.workflow().stages().get(1).pendingCandidates().names())
                .containsExactly("DEWI", "RINA");
        assertThat(detail.workflow().stages().get(1).pendingCandidates().totalCount()).isEqualTo(2);
        assertThat(detail.workflow().stages().get(0).pendingCandidates()).isNull();
        assertThat(detail.workflow().stages().get(2).pendingCandidates()).isNull();
    }

    @Test
    void aFinishedTransactionHasNoLadderAtAll() {
        when(activityMapper.findByRefNo(any()))
                .thenReturn(row("TASK", "EXECUTED", "BERHASIL", null, 2, null));
        when(trxTaskMapper.findActions("T1")).thenReturn(List.of(
                new ActionRow(1, "APPROVE", "SITI", null, CREATED.plusMinutes(5))));

        var detail = service.detail(COMPANY, USER, "20260901174314000002");

        // A ladder frozen at its last state would read as "still pending" on a transaction
        // that is finished; the activity trail tells that story instead.
        assertThat(detail.workflow()).isNull();
        assertThat(detail.activities()).isNotEmpty();
        verify(trxTaskMapper, never()).findStages(any());
    }

    @Test
    void aLegacyRowHasNoLadderEither() {
        when(activityMapper.findByRefNo(any()))
                .thenReturn(row("LEGACY", "EXECUTED", "BERHASIL", null, null, null));

        assertThat(service.detail(COMPANY, USER, "20260901174314000002").workflow()).isNull();
    }

    @Test
    void aRejectionReadsAsDitolakInTheTrailRatherThanGagal() {
        when(activityMapper.findByRefNo(any()))
                .thenReturn(row("TASK", "REJECTED", "DITOLAK", null, 2, null));
        when(trxTaskMapper.findActions("T1")).thenReturn(List.of(
                new ActionRow(1, "REJECT", "SITI", "data salah", CREATED.plusMinutes(5))));

        var trail = service.detail(COMPANY, USER, "20260901174314000002").activities();

        assertThat(trail).hasSize(1);
        assertThat(trail.get(0).transactionStatus()).isEqualTo("DITOLAK");
    }
}
