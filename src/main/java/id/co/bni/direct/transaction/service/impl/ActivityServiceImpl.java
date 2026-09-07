package id.co.bni.direct.transaction.service.impl;

import id.co.bni.direct.transaction.dto.request.ActivityRequests.ActivitySearchRequest;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityDetailResponse;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityItemResponse;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityPageResponse;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityRecentResponse;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityTrailResponse;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.WorkflowResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.PendingCandidatesResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.StageResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.StageActionResponse;
import id.co.bni.direct.transaction.entity.ActivityRows.ActivityFilter;
import id.co.bni.direct.transaction.entity.ActivityRows.ActivityRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.ActionRow;
import id.co.bni.direct.transaction.entity.TrxTaskRows.StageRow;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.NotFoundException;
import id.co.bni.direct.transaction.repository.mapper.ActivityMapper;
import id.co.bni.direct.transaction.repository.mapper.TrxTaskMapper;
import id.co.bni.direct.transaction.service.ActivityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aktivitas Transaksi over TRX_TASK (rewrite, every status) plus BASE_FT (legacy, executed).
 *
 * <p>The status the screen shows is computed in SQL (STATUS_GROUP) so the same rule serves
 * filtering and display. Since the stage split it has SIX values, not three: the two
 * waiting stages are their own statuses (PENDING_APPROVAL, PENDING_RELEASE), DIPROSES now
 * means EXECUTION is under way (READY_TO_EXECUTE / QUEUED / EXECUTING, and UNKNOWN, which
 * waits for reconciliation), EXECUTED = BERHASIL, REJECTED = DITOLAK, FAILED = GAGAL.
 * Legacy rows are BERHASIL by construction (BASE_FT only ever held executed transfers) and
 * can never carry a stage status.
 *
 * <p>Scope is per company, narrowed to the accounts the user holds inquiry rights on; the
 * mapper applies that, so a reference number outside the user's accounts reads as 404, the
 * same as one that does not exist - the existence of another group's transaction is not
 * something this screen should confirm.
 */
@Service
public class ActivityServiceImpl implements ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityServiceImpl.class);

    /** FE contract §7.1: at most 92 days, counted inclusively. */
    static final long MAX_RANGE_DAYS = 92;

    /**
     * Screen aliases for the menu code, so the drawer can send either. Only menus this
     * service can produce rows for are listed; anything else passes through as a menu code.
     */
    private static final Map<String, String> TYPE_ALIASES = Map.of(
            "TRANSFER_BNI", TransferServiceImpl.MENU_CD);

    /** The two statuses whose ladder is still being walked - the only ones §6 renders. */
    private static final List<String> IN_FLIGHT_STATUSES = List.of("PENDING_APPROVAL", "PENDING_RELEASE");

    /**
     * BACKWARD COMPATIBILITY, remove once every FE sends the six-value status.
     *
     * <p>Before the stage split, DIPROSES meant "anything that is neither done nor
     * refused", which included both waiting stages. An FE built against that contract
     * still sends status=DIPROSES for its "in progress" chip, and answering it with only
     * the narrowed DIPROSES would silently hide every transaction awaiting approval - the
     * worst possible failure for this screen. So the old value is expanded; the new,
     * narrower values pass through as themselves.
     */
    private static final Map<String, List<String>> LEGACY_STATUS_ALIASES = Map.of(
            "DIPROSES", List.of("PENDING_APPROVAL", "PENDING_RELEASE", "DIPROSES"));

    /** How many "menunggu siapa" names a stage sends before it just counts them. */
    private static final int MAX_PENDING_CANDIDATE_NAMES = 10;

    private final ActivityMapper activityMapper;
    private final TrxTaskMapper trxTaskMapper;

    public ActivityServiceImpl(ActivityMapper activityMapper, TrxTaskMapper trxTaskMapper) {
        this.activityMapper = activityMapper;
        this.trxTaskMapper = trxTaskMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityRecentResponse recent(String companyId, String userId, int limit) {
        ActivityFilter filter = new ActivityFilter();
        filter.setCompanyId(companyId);
        filter.setUserId(userId);
        filter.setOffset(0);
        filter.setSize(Math.max(1, Math.min(limit, 50)));
        List<ActivityItemResponse> items = activityMapper.search(filter).stream().map(ActivityServiceImpl::toItem).toList();
        return new ActivityRecentResponse(items);
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityPageResponse search(String companyId, ActivitySearchRequest request) {
        ActivityFilter filter = toFilter(companyId, request);
        long total = activityMapper.count(filter);
        List<ActivityItemResponse> items = total == 0 ? List.of()
                : activityMapper.search(filter).stream().map(ActivityServiceImpl::toItem).toList();
        log.info("Activity search companyId={} userId={} type={} rows={} total={}",
                companyId, request.userId(), request.searchType(), items.size(), total);
        return new ActivityPageResponse(items, request.pageOrDefault(), request.sizeOrDefault(), total);
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityDetailResponse detail(String companyId, String userId, String referenceNo) {
        ActivityFilter filter = new ActivityFilter();
        filter.setCompanyId(companyId);
        filter.setUserId(userId);
        filter.setRefNo(referenceNo);
        filter.setSize(1);
        ActivityRow row = activityMapper.findByRefNo(filter);
        if (row == null) {
            throw new NotFoundException("Transaksi tidak ditemukan.");
        }
        List<ActivityTrailResponse> trail = "TASK".equals(row.source())
                ? taskTrail(row)
                : legacyTrail(row);
        return new ActivityDetailResponse(row.refNo(), row.source(), row.statusGroup(), row.rawStatus(),
                row.createdDt(), row.menuCd(), typeLabel(row), row.remAcctNo(), row.remAcctNm(),
                row.remAcctCcy(), row.benAcctNo(), row.benAcctNm(), row.trxAmt(), row.trxCcyCd(),
                row.remark1(), instruction(row), row.makerUserName(), row.journalNo(), row.trxRefNo(),
                row.executedDt(), row.orderPartyRefNo(), row.counterPartyRefNo(), trail,
                workflow(row));
    }

    // ---------------------------------------------------------------- mapping

    static ActivityFilter toFilter(String companyId, ActivitySearchRequest request) {
        ActivityFilter filter = new ActivityFilter();
        filter.setCompanyId(companyId);
        filter.setUserId(request.userId());
        if ("REFERENCE".equals(request.searchType())) {
            if (request.referenceNo() == null || request.referenceNo().isBlank()) {
                throw new BusinessRuleException("REFERENCE_REQUIRED", "Nomor referensi wajib diisi.");
            }
            filter.setRefNo(request.referenceNo().trim());
        } else {
            if (request.dateRange() == null) {
                throw new BusinessRuleException("DATE_RANGE_REQUIRED", "Rentang tanggal wajib diisi.");
            }
            if (request.dateRange().end().isBefore(request.dateRange().start())) {
                throw new BusinessRuleException("DATE_RANGE_INVALID", "Tanggal akhir harus setelah tanggal awal.");
            }
            long span = ChronoUnit.DAYS.between(request.dateRange().start(), request.dateRange().end()) + 1;
            if (span > MAX_RANGE_DAYS) {
                throw new BusinessRuleException("DATE_RANGE_TOO_WIDE",
                        "Rentang tanggal maksimal " + MAX_RANGE_DAYS + " hari.");
            }
            filter.setDateFrom(request.dateRange().start());
            filter.setDateTo(request.dateRange().end());
            if (request.referenceNo() != null && !request.referenceNo().isBlank()) {
                filter.setRefNo(request.referenceNo().trim());
            }
        }
        if (request.transactionType() != null && !request.transactionType().isBlank()) {
            String type = request.transactionType().trim();
            filter.setMenuCd(TYPE_ALIASES.getOrDefault(type, type));
        }
        filter.setStatusGroups(statusGroups(blankToNull(request.status())));
        filter.setSourceAccountNo(blankToNull(request.sourceAccountNo()));
        filter.setBeneficiaryName(blankToNull(request.beneficiaryName()));
        if (request.amountFrom() != null) {
            filter.setAmountFrom(request.amountFrom().amount());
        }
        if (request.amountTo() != null) {
            filter.setAmountTo(request.amountTo().amount());
        }
        if (filter.getAmountFrom() != null && filter.getAmountTo() != null
                && filter.getAmountFrom().compareTo(filter.getAmountTo()) > 0) {
            throw new BusinessRuleException("AMOUNT_RANGE_INVALID", "Nominal awal harus lebih kecil dari nominal akhir.");
        }
        int size = request.sizeOrDefault();
        filter.setSize(size);
        filter.setOffset((request.pageOrDefault() - 1) * size);
        return filter;
    }

    /** One chip to the STATUS_GROUP values it covers; null (no filter) stays null. */
    public static List<String> statusGroups(String status) {
        if (status == null) {
            return null;
        }
        return LEGACY_STATUS_ALIASES.getOrDefault(status, List.of(status));
    }

    static ActivityItemResponse toItem(ActivityRow row) {
        return new ActivityItemResponse(row.refNo(), row.source(), row.createdDt(), row.menuCd(),
                typeLabel(row), row.remAcctNo(), row.remAcctNm(), row.remAcctCcy(), row.benAcctNo(),
                row.benAcctNm(), row.trxAmt(), row.trxCcyCd(), instruction(row), row.statusGroup(),
                row.rawStatus(), row.orderPartyRefNo(), row.counterPartyRefNo(), null,
                stageProgress(row));
    }

    /**
     * "Approval 1 dari 2" / "Rilis" - where a waiting transaction stands, without opening
     * the detail. Null unless the row is a task actually waiting on a stage: a legacy row
     * has no stage columns at all, and a task past release has nothing left to wait for.
     */
    public static String stageProgress(ActivityRow row) {
        if (!IN_FLIGHT_STATUSES.contains(row.statusGroup())
                || row.currentStageSeq() == null || row.currentStageType() == null) {
            return null;
        }
        if ("RELEASE".equals(row.currentStageType())) {
            return "Rilis";
        }
        int total = row.approvalStageCount() == null ? 0 : row.approvalStageCount();
        // The approval stages come first, so the sequence number IS the approval ordinal.
        return total > 1
                ? "Approval " + row.currentStageSeq() + " dari " + total
                : "Approval";
    }

    /** The menu's display name when this service knows it, else core's service name. */
    static String typeLabel(ActivityRow row) {
        if (row.menuCd() != null && !row.menuCd().equals(TransferServiceImpl.menuName(row.menuCd()))) {
            return TransferServiceImpl.menuName(row.menuCd());
        }
        if (row.srvcNm() != null && !row.srvcNm().isBlank()) {
            return row.srvcNm();
        }
        return row.menuCd() != null ? row.menuCd() : row.srvcCd();
    }

    /**
     * INSTRUCTION_MODE: the rewrite writes {@code IMMEDIATE}, legacy BASE_FT writes {@code 1};
     * both mean "now". Anything else is a dated instruction.
     */
    static String instruction(ActivityRow row) {
        String mode = row.instructionMode() == null ? "" : row.instructionMode().trim().toUpperCase();
        if (mode.isEmpty() || "1".equals(mode) || "IMMEDIATE".equals(mode)) {
            return "Immediate";
        }
        return row.instructionDt() == null ? "Scheduled" : "Scheduled - " + row.instructionDt().toLocalDate();
    }

    /**
     * The task's action trail as the detail screen words it. Every recorded act succeeded
     * as an act (a REJECT is a successful rejection); the one act that can fail is EXECUTE,
     * and its outcome is the task's final status.
     */
    private List<ActivityTrailResponse> taskTrail(ActivityRow row) {
        List<ActivityTrailResponse> trail = new ArrayList<>();
        // EXECUTE rows are written by the execution seam without a display name; the act is
        // the releaser's, so carry the last named actor (the RELEASE) onto it.
        String lastActor = null;
        for (ActionRow action : trxTaskMapper.findActions(row.taskId())) {
            String type = action.action();
            String actor = action.actorUserName() != null ? action.actorUserName() : lastActor;
            if (action.actorUserName() != null) {
                lastActor = action.actorUserName();
            }
            String activityStatus = "SUKSES";
            String transactionStatus = "DIPROSES";
            if ("EXECUTE".equals(type)) {
                transactionStatus = row.statusGroup();
                activityStatus = "BERHASIL".equals(row.statusGroup()) ? "SUKSES" : "GAGAL";
            } else if ("REJECT".equals(type)) {
                transactionStatus = "DITOLAK";
            }
            trail.add(new ActivityTrailResponse(action.createdDt(), type, actor,
                    row.trxAmt(), row.trxCcyCd(), activityStatus, action.note(), transactionStatus));
        }
        return trail;
    }

    /**
     * The view-only ladder, built only for a transaction still walking it. Everything else
     * - executed, refused, failed, and every legacy/VA row - answers null: a finished
     * transaction's story is the activity trail, and a ladder frozen at its last state
     * would invite the reader to think something is still pending.
     *
     * <p>Read-only by construction: it reports stages, actions and who is being waited on,
     * and deliberately carries no flag saying whether THIS reader may act. The inbox
     * answers that question, and the action endpoints enforce it regardless.
     */
    private WorkflowResponse workflow(ActivityRow row) {
        if (!"TASK".equals(row.source()) || row.taskId() == null
                || !IN_FLIGHT_STATUSES.contains(row.statusGroup())) {
            return null;
        }
        Map<Integer, List<StageActionResponse>> actionsByStage = trxTaskMapper.findActions(row.taskId()).stream()
                .filter(a -> a.stageSeq() != null)
                .collect(Collectors.groupingBy(ActionRow::stageSeq,
                        Collectors.mapping(a -> new StageActionResponse(
                                        a.actorUserName(), a.action(), a.note(), a.createdDt()),
                                Collectors.toList())));
        List<StageRow> stageRows = trxTaskMapper.findStages(row.taskId());
        int approvalStages = (int) stageRows.stream().filter(st -> "APPROVAL".equals(st.stageType())).count();
        List<StageResponse> stages = new ArrayList<>();
        int approvalOrdinal = 0;
        for (StageRow st : stageRows) {
            String label;
            if ("APPROVAL".equals(st.stageType())) {
                approvalOrdinal++;
                label = approvalStages > 1 ? "Approval " + approvalOrdinal : "Approval";
            } else {
                label = "Rilis";
            }
            stages.add(new StageResponse(st.seqNo(), st.stageType(), st.aprvLvlCd(),
                    st.usrGrpOpt(), st.requiredCount(), st.completedCount(), st.status(),
                    actionsByStage.getOrDefault(st.seqNo(), List.of()),
                    label,
                    "ACTIVE".equals(st.status()) ? pendingCandidates(row.taskId(), st.seqNo()) : null));
        }
        return new WorkflowResponse(row.currentStageSeq(), approvalStages, stages);
    }

    /** Who the ACTIVE stage is still waiting on, capped; totalCount is the real number. */
    private PendingCandidatesResponse pendingCandidates(String taskId, Integer stageSeq) {
        List<String> names = trxTaskMapper.findPendingCandidateNames(taskId, stageSeq);
        if (names.isEmpty()) {
            return null;
        }
        List<String> shown = names.size() > MAX_PENDING_CANDIDATE_NAMES
                ? names.subList(0, MAX_PENDING_CANDIDATE_NAMES)
                : names;
        return new PendingCandidatesResponse(List.copyOf(shown), names.size());
    }

    /** A legacy row carries no workflow history: one synthetic EXECUTE at its creation. */
    private static List<ActivityTrailResponse> legacyTrail(ActivityRow row) {
        boolean executed = "BERHASIL".equals(row.statusGroup());
        return List.of(new ActivityTrailResponse(row.executedDt() != null ? row.executedDt() : row.createdDt(),
                "EXECUTE", row.makerUserName(), row.trxAmt(), row.trxCcyCd(),
                executed ? "SUKSES" : "GAGAL", null, row.statusGroup()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
