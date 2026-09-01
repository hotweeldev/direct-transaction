package id.co.bni.direct.transaction.service;

import id.co.bni.direct.transaction.dto.request.TaskRequests.ApproveTaskRequest;
import id.co.bni.direct.transaction.dto.request.TaskRequests.RejectTaskRequest;
import id.co.bni.direct.transaction.dto.response.TaskResponses.InboxResponse;
import id.co.bni.direct.transaction.dto.response.TaskResponses.TaskActionResponse;

/** The approval phase: the candidate inbox, approve (or release), and reject. */
public interface TaskApprovalService {

    InboxResponse inbox(String companyId, String userId);

    TaskActionResponse approve(String companyId, String actor, String taskId,
                               ApproveTaskRequest request);

    TaskActionResponse reject(String companyId, String actor, String taskId,
                              RejectTaskRequest request);
}
