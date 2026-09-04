package id.co.bni.direct.transaction.service;

import id.co.bni.direct.transaction.dto.request.TransferRequests.InquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.InterbankInquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpChallengeRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.SubmitTransferRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.BiFastInquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.VaInquiryRequest;
import id.co.bni.direct.transaction.dto.response.TransferResponses.InquiryResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.InterbankInquiryResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.OtpChallengeResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.BankResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.MethodInfoResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.SubmitResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.TaskDetailResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.BiFastInquiryResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.BiFastPurposeResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.VaInquiryResponse;

import java.util.List;

/**
 * The transfer maker flow: inquiry, OTP challenge, submit, status - Transfer ke BNI
 * (P0) plus the P1 domestic types (LLG/RTGS), which add the destination-bank picker.
 */
public interface TransferService {

    InquiryResponse inquiry(String companyId, InquiryRequest request);

    OtpChallengeResponse otpChallenge(String companyId, OtpChallengeRequest request);

    SubmitResponse submit(String companyId, String actor, SubmitTransferRequest request);

    TaskDetailResponse detail(String companyId, String taskId, String userId);

    /** Destination banks routable for {@code method} (LLG, RTGS or ONLINE). */
    List<BankResponse> banks(String companyId, String method);

    /** Display parameters (duration, min/max nominal, fee) for {@code method} (LLG, RTGS or ONLINE). */
    MethodInfoResponse methodInfo(String companyId, String method);

    /** The interbank (ONLINE) beneficiary inquiry - the FE's "Periksa" step (P2). */
    InterbankInquiryResponse interbankInquiry(String companyId, InterbankInquiryRequest request);

    /** The Transfer ke Virtual Account billing inquiry (P3). */
    VaInquiryResponse vaInquiry(String companyId, VaInquiryRequest request);

    /** P7: the active BI-Fast transaction purposes the submit accepts. */
    List<BiFastPurposeResponse> bifastPurposes(String companyId);

    /** P7: the BI-Fast beneficiary inquiry - the "Periksa" step before a BIFAST submit. */
    BiFastInquiryResponse bifastInquiry(String companyId, BiFastInquiryRequest request);
}
