package id.co.bni.direct.transaction.controller;

import id.co.bni.direct.transaction.dto.request.TransferRequests.InquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.InterbankInquiryRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.OtpChallengeRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.SubmitTransferRequest;
import id.co.bni.direct.transaction.dto.request.TransferRequests.VaInquiryRequest;
import id.co.bni.direct.transaction.dto.response.TransferResponses.BankResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.InquiryResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.InterbankInquiryResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.MethodInfoResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.OtpChallengeResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.SubmitResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.TaskDetailResponse;
import id.co.bni.direct.transaction.dto.response.TransferResponses.VaInquiryResponse;

import java.util.List;
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

    /**
     * The interbank (ONLINE / RTOL / ATM Bersama) beneficiary inquiry - the FE's
     * "Periksa" step before an ONLINE submit (P2), proxied to direct-integration's
     * switch hop. Bound to the caller's identity like the OTP challenge: the body's
     * {@code userId} may not name somebody else once auth is on.
     */
    @PostMapping("/interbank-inquiry")
    public ResponseEntity<InterbankInquiryResponse> interbankInquiry(
            @PathVariable String companyId,
            @Valid @RequestBody InterbankInquiryRequest request,
            HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.ok(transferService.interbankInquiry(companyId, request));
    }

    /**
     * The Transfer ke Virtual Account billing inquiry (P3) - the FE's "Periksa" step
     * before a VA submit, proxied to direct-integration's VA hop. Bound to the caller's
     * identity like the interbank inquiry; the maker must hold debit rights on the
     * source account (the VA service keys the inquiry on the paying account).
     */
    @PostMapping("/va/inquiry")
    public ResponseEntity<VaInquiryResponse> vaInquiry(
            @PathVariable String companyId,
            @Valid @RequestBody VaInquiryRequest request,
            HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.ok(transferService.vaInquiry(companyId, request));
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

    /**
     * The destination-bank picker for the domestic methods (P1). {@code method} is LLG
     * or RTGS; each answers only banks that can actually be routed for that method (see
     * the mapper). Read-only, from the legacy COM_MT_DOM_BANK master - guarded by the
     * same {@code transfer-bni} resource as the rest of this controller (no new RBAC
     * resource is introduced; whether Transfer ke Bank Lain warrants its own
     * {@code transfer-other} enforcement is a UMAS-catalog decision, noted, not made
     * here).
     */
    @GetMapping("/banks")
    public ResponseEntity<List<BankResponse>> banks(@PathVariable String companyId,
                                                    @RequestParam String method) {
        return ResponseEntity.ok(transferService.banks(companyId, method));
    }

    /**
     * The method's display parameters (P1) - estimated duration, min/max nominal and the
     * fee - from the legacy SYS_PARAM_TRF_SME_* rows, so the FE validates the RTGS/LLG
     * minimum and shows the fee without hardcoding either value. Read-only, guarded like
     * {@code /banks} by the controller-level {@code transfer-bni} resource; a
     * {@code method} other than LLG/RTGS is the same 422 as there.
     */
    @GetMapping("/method-info")
    public ResponseEntity<MethodInfoResponse> methodInfo(@PathVariable String companyId,
                                                         @RequestParam String method) {
        return ResponseEntity.ok(transferService.methodInfo(companyId, method));
    }

    /** Task detail with its stages - the success/status screen. */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskDetailResponse> detail(@PathVariable String companyId,
                                                     @PathVariable String taskId,
                                                     @RequestParam String userId) {
        return ResponseEntity.ok(transferService.detail(companyId, taskId, userId));
    }
}
