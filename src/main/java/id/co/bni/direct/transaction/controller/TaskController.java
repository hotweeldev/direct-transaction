package id.co.bni.direct.transaction.controller;

import id.co.bni.direct.transaction.dto.request.TaskRequests.ApproveTaskRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.RejectTaskRequest;
import id.co.bni.direct.transaction.dto.response.TaskResponses.InboxResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.TaskActionResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.TaskDetailResponse;
import id.co.bni.direct.transaction.security.RequiresPermission;
import id.co.bni.direct.transaction.security.TokenIdentity;
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

    public TaskController(TaskApprovalService taskApprovalService,
                          TransferService transferService) {
        this.taskApprovalService = taskApprovalService;
        this.transferService = transferService;
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
