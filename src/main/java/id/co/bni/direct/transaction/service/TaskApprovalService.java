package id.co.bni.direct.transaction.service;

import id.co.bni.direct.transaction.dto.request.TaskRequests.ApproveTaskRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.BulkActionRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.RejectTaskRequest;
import id.co.bni.direct.transaction.dto.response.TaskResponses.BulkActionResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.InboxResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.TaskActionResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.TaskSummaryResponse;

/** The approval phase: the candidate inbox, approve (or release), and reject. */
public interface TaskApprovalService {

    InboxResponse inbox(String companyId, String userId);

    /**
     * The badge counts behind {@link #inbox}: the same set of tasks, counted rather than
     * listed. Its own method (and its own single aggregate query) because the FE polls it
     * every 30 seconds and must never pay for building an inbox to get three numbers.
     */
    TaskSummaryResponse summary(String companyId, String userId);

    TaskActionResponse approve(String companyId, String actor, String taskId,
                               ApproveTaskRequest request);

    TaskActionResponse reject(String companyId, String actor, String taskId,
                              RejectTaskRequest request);

    /**
     * Approve a set of tasks under ONE token verification. All-or-nothing: if any task in
     * the set cannot be approved, none of them are, and the caller is told about every
     * offending task at once.
     */
    BulkActionResponse bulkApprove(String companyId, String actor, BulkActionRequest request);

    /** Release a set of tasks; same all-or-nothing contract as {@link #bulkApprove}. */
    BulkActionResponse bulkRelease(String companyId, String actor, BulkActionRequest request);

    /** Reject a set of tasks with one shared, required note. */
    BulkActionResponse bulkReject(String companyId, String actor, BulkActionRequest request);
}
