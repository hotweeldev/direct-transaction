package id.co.bni.direct.transaction.service;

import id.co.bni.direct.transaction.dto.request.ActivityRequests.ActivitySearchRequest;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityDetailResponse;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityPageResponse;
import id.co.bni.direct.transaction.dto.response.ActivityResponses.ActivityRecentResponse;

/** Aktivitas Transaksi: the company's transactions the user may see, newest first. */
public interface ActivityService {

    ActivityRecentResponse recent(String companyId, String userId, int limit);

    ActivityPageResponse search(String companyId, ActivitySearchRequest request);

    ActivityDetailResponse detail(String companyId, String userId, String referenceNo);
}
