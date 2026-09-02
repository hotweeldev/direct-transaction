package id.co.bni.direct.transaction.controller;

import id.co.bni.direct.transaction.dto.request.ActivityRequests.ActivitySearchRequest;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityDetailResponse;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityPageResponse;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityRecentResponse;
import id.co.bni.direct.transaction.security.RequiresPermission;
import id.co.bni.direct.transaction.security.TokenIdentity;
import id.co.bni.direct.transaction.service.ActivityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aktivitas Transaksi (FE contract §4.5 home card, §7.1 search, §7.2 detail).
 *
 * <p>Guarded by the corporate nav resource {@code activity} (UMAS V20). The {@code userId}
 * query parameter and body field are bound to the bearer identity the same way the task
 * inbox is ({@link id.co.bni.direct.transaction.security.IdentityBindingInterceptor} for the
 * query, {@link TokenIdentity} for the body).
 */
@RestController
@RequestMapping("api/v1/companies/{companyId}/transactions")
@RequiresPermission(resource = "activity")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    /** Home card: the newest few, newest first. */
    @GetMapping("/recent")
    public ResponseEntity<ActivityRecentResponse> recent(@PathVariable String companyId,
                                                         @RequestParam String userId,
                                                         @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(activityService.recent(companyId, userId, limit));
    }

    @PostMapping("/search")
    public ResponseEntity<ActivityPageResponse> search(@PathVariable String companyId,
                                                       @Valid @RequestBody ActivitySearchRequest request,
                                                       HttpServletRequest servletRequest) {
        TokenIdentity.requireSameUser(servletRequest, request.userId());
        return ResponseEntity.ok(activityService.search(companyId, request));
    }

    @GetMapping("/{referenceNo}")
    public ResponseEntity<ActivityDetailResponse> detail(@PathVariable String companyId,
                                                         @PathVariable String referenceNo,
                                                         @RequestParam String userId) {
        return ResponseEntity.ok(activityService.detail(companyId, userId, referenceNo));
    }
}
