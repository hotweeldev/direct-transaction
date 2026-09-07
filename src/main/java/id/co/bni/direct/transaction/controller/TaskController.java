package id.co.bni.direct.transaction.controller;

import id.co.bni.direct.transaction.dto.request.TaskRequests.ApproveTaskRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.BulkActionRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.ReconcileTaskRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.RejectTaskRequest;
import id.co.bni.direct.transaction.dto.response.TaskResponses.BulkActionResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.InboxResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.ReconcileResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.TaskActionResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.TaskDetailResponse;
import id.co.bni.direct.transaction.security.RequiresPermission;
import id.co.bni.direct.transaction.security.TokenIdentity;
import id.co.bni.direct.transaction.service.ReconciliationService;
import id.co.bni.direct.transaction.service.TaskApprovalService;
import id.co.bni.direct.transaction.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approval phase: the candidate inbox, task detail, approve (or release) and reject.
 *
 * <p>Same shape rules as {@link TransferController}: {@code companyId} from the path is
 * the authority for every query, the actor rides in X-User-Id — and the same guard stack
 * applies verbatim, off the same flags: bearer validation + identity binding
 * ({@code company_id} claim == path {companyId}; the {@code userId} query parameter and
 * body fields must equal the token identity, checked by the interceptor and
 * {@link TokenIdentity} respectively; X-User-Id is 403'd on contradiction then overridden
 * by the filter), and {@code @RequiresPermission(task-pending)} — the seeded pending-task
 * inbox resource (ACCESS, tenant bni-direct) — asked of UMAS when enforcement is on.
 * With the flags off (local development) these endpoints are open and the service must
 * not be reachable from outside the cluster.
 */
@RestController
@RequestMapping("api/v1/companies/{companyId}/tasks")
@RequiresPermission(resource = "task-pending")
public class TaskController {

    private final TaskApprovalService taskApprovalService;
    private final TransferService transferService;
    private final ReconciliationService reconciliationService;

    public TaskController(TaskApprovalService taskApprovalService,
                          TransferService transferService,
                          ReconciliationService reconciliationService) {
        this.taskApprovalService = taskApprovalService;
        this.transferService = transferService;
        this.reconciliationService = reconciliationService;
    }

    /**
     * The inbox: tasks where the user is a frozen candidate on the CURRENTLY ACTIVE
     * stage and has not acted on it yet, newest first. {@code status} only accepts
     * PENDING (the FE always sends it; anything else would promise a filter that does
     * not exist).
     */
    @GetMapping
    public ResponseEntity<InboxResponse> inbox(
            @PathVariable String companyId,
            @RequestParam String userId,
            @RequestParam(defaultValue = "PENDING") String status) {
        if (!"PENDING".equals(status)) {
            throw new IllegalArgumentException("Only status=PENDING is supported.");
        }
        return ResponseEntity.ok(taskApprovalService.inbox(companyId, userId));
    }

    /** Task detail with stages and their action history - same shape as /transfers/{taskId}. */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskDetailResponse> detail(@PathVariable String companyId,
                                                     @PathVariable String taskId,
                                                     @RequestParam String userId) {
        return ResponseEntity.ok(transferService.detail(companyId, taskId, userId));
    }

    /** Approve the active APPROVAL stage - or release, when the active stage is RELEASE. */
    @PostMapping("/{taskId}/approve")
    public ResponseEntity<TaskActionResponse> approve(
            @PathVariable String companyId,
            @PathVariable String taskId,
            @Valid @RequestBody ApproveTaskRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor,
            HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.ok(taskApprovalService.approve(companyId, actor, taskId, request));
    }

    /**
     * P6: reconcile a two-leg (simsem) task left UNKNOWN after leg 1 was confirmed - ask
     * core banking whether leg 2 left the simsem account, then finalize EXECUTED, refund,
     * or answer "inconclusive". Idempotent; a task in any other state is a 422
     * {@code TASK_NOT_RECONCILABLE}. Same guard stack as approve/reject.
     */
    @PostMapping("/{taskId}/reconcile")
    public ResponseEntity<ReconcileResponse> reconcile(
            @PathVariable String companyId,
            @PathVariable String taskId,
            @Valid @RequestBody ReconcileTaskRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor,
            HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.ok(reconciliationService.reconcile(companyId, taskId, actor));
    }

    /**
     * Approve every task in the body at once, under one token verification.
     *
     * <p>Three endpoints rather than one because a batch must be homogeneous. The
     * single-task {@code /approve} acts on whichever stage is active - approving or
     * releasing depending on what it finds - and that "depends what it finds" behaviour is
     * unsafe over a list: a user who ticked twenty rows cannot be left guessing which ones
     * were approved and which were released. Here a task sitting on the other kind of
     * stage is refused by name (STAGE_MISMATCH) and the whole batch stops.
     *
     * <p>Same guard stack as the single-task endpoints, and the same 422 vocabulary; a
     * refused batch adds {@code failures}, one entry per offending task.
     */
    @PostMapping("/bulk-approve")
    public ResponseEntity<BulkActionResponse> bulkApprove(
            @PathVariable String companyId,
            @Valid @RequestBody BulkActionRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor,
            HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.ok(taskApprovalService.bulkApprove(companyId, actor, request));
    }

    /** Release every task in the body at once; tasks not awaiting release are refused. */
    @PostMapping("/bulk-release")
    public ResponseEntity<BulkActionResponse> bulkRelease(
            @PathVariable String companyId,
            @Valid @RequestBody BulkActionRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor,
            HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.ok(taskApprovalService.bulkRelease(companyId, actor, request));
    }

    /** Reject every task in the body at once; one note, required, applies to all of them. */
    @PostMapping("/bulk-reject")
    public ResponseEntity<BulkActionResponse> bulkReject(
            @PathVariable String companyId,
            @Valid @RequestBody BulkActionRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor,
            HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.ok(taskApprovalService.bulkReject(companyId, actor, request));
    }

    /** Reject the task outright; the note saying why is required. */
    @PostMapping("/{taskId}/reject")
    public ResponseEntity<TaskActionResponse> reject(
            @PathVariable String companyId,
            @PathVariable String taskId,
            @Valid @RequestBody RejectTaskRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor,
            HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.ok(taskApprovalService.reject(companyId, actor, taskId, request));
    }
}
