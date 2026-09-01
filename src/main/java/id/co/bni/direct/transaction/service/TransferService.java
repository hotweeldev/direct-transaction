package id.co.bni.direct.transaction.service;

import id.co.bni.direct.transaction.dto.request.TransferRequests.InquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpChallengeRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.SubmitTransferRequest;
import id.co.bni.direct.transaction.dto.response.TransferResponses.InquiryResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.OtpChallengeResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.SubmitResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.TaskDetailResponse;

/** The Transfer ke BNI maker flow: inquiry, OTP challenge, submit, status. */
public interface TransferService {

    InquiryResponse inquiry(String companyId, InquiryRequest request);

    OtpChallengeResponse otpChallenge(String companyId, OtpChallengeRequest request);

    SubmitResponse submit(String companyId, String actor, SubmitTransferRequest request);

    TaskDetailResponse detail(String companyId, String taskId, String userId);
}
