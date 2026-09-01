package id.co.bni.direct.transaction.controller;

import id.co.bni.direct.transaction.dto.request.TransferRequests.InquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpChallengeRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.SubmitTransferRequest;
import id.co.bni.direct.transaction.dto.response.TransferResponses.InquiryResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.OtpChallengeResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.SubmitResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.TaskDetailResponse;
import id.co.bni.direct.transaction.security.RequiresPermission;
import id.co.bni.direct.transaction.security.TokenIdentity;
import id.co.bni.direct.transaction.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * Transfer ke BNI (MNU_GCME_050200), maker phase: beneficiary inquiry, OTP challenge,
 * submit, and the status screen's task detail. Approve/release land in a later phase.
 *
 * <p>Shaped like direct-admin's controllers: {@code companyId} comes from the path and is
 * the authority for every query underneath - never from a body or a header a client could
 * vary independently.
 *
 * <p>Guarded, off {@code app.umas.auth.enabled}, by direct-admin's copied stack.
 * {@code UmasBearerAuthFilter} validates the Bearer JWT the UMAS gateway forwards
 * (signature, expiry, POSTLOGIN scope, optional jti presence check), 403s an X-User-Id
 * that contradicts the token, and replaces that header with the token's identity - so
 * {@code actor} below IS the token identity once the flag is on.
 * {@code IdentityBindingInterceptor} requires the token's {@code company_id} claim to
 * equal the path {companyId} and any {@code userId} query parameter to equal the token
 * identity; {@link TokenIdentity} does the same for the {@code userId} body fields, which
 * stay accepted for wire-shape compatibility but may no longer name somebody else.
 * {@code @RequiresPermission(transfer-bni)} additionally asks UMAS, off
 * {@code app.umas.authorization.enabled}, whether the caller holds the Transfer ke BNI
 * menu (seeded, ACCESS, tenant bni-direct). With both flags off (local development
 * without the UMAS stack) the endpoints are open and the service must not be reachable
 * from outside the cluster.
 */
@RestController
@RequestMapping("api/v1/companies/{companyId}/transfers")
@RequiresPermission(resource = "transfer-bni")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /** The beneficiary's registered name, proxied to direct-integration. */
    @PostMapping("/inquiry")
    public ResponseEntity<InquiryResponse> inquiry(@PathVariable String companyId,
                                                   @Valid @RequestBody InquiryRequest request) {
        return ResponseEntity.ok(transferService.inquiry(companyId, request));
    }

    /** A fresh OTP challenge for the maker, from the UMAS authenticator. */
    @PostMapping("/otp/challenge")
    public ResponseEntity<OtpChallengeResponse> otpChallenge(@PathVariable String companyId,
                                                             @Valid @RequestBody OtpChallengeRequest request,
                                                             HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.ok(transferService.otpChallenge(companyId, request));
    }

    /** The maker submit. 201 with the new task; 422 with a machine code on any refusal. */
    @PostMapping
    public ResponseEntity<SubmitResponse> submit(
            @PathVariable String companyId,
            @Valid @RequestBody SubmitTransferRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor,
            HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transferService.submit(companyId, actor, request));
    }

    /** Task detail with its stages - the success/status screen. */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskDetailResponse> detail(@PathVariable String companyId,
                                                     @PathVariable String taskId,
                                                     @RequestParam String userId) {
        return ResponseEntity.ok(transferService.detail(companyId, taskId, userId));
    }
}
