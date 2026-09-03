package id.co.bni.direct.transaction.service;

import id.co.bni.direct.transaction.dto.response.TaskResponses.ReconcileResponse;

/**
 * P6 task 6.5: resolves a two-leg task whose leg 2 was left UNKNOWN (timeout, or a
 * bookkeeping failure after the money moved) by asking core banking what actually left
 * the simsem account. Endpoint-driven and idempotent; no scheduler.
 */
public interface ReconciliationService {

    /**
     * Reconcile one task. {@code actor} is recorded on the verdict and the action row.
     * Answers the outcome without throwing for the "nothing could be proven" case - an
     * inconclusive reconciliation is a normal answer, not an error.
     */
    ReconcileResponse reconcile(String companyId, String taskId, String actor);
}
