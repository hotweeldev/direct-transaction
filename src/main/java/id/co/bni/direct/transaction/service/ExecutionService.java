package id.co.bni.direct.transaction.service;

/**
 * THE EXECUTION SEAM. A task that reaches READY_TO_EXECUTE (its RELEASE stage completed,
 * or a single-user submit) is handed here. The real implementation
 * ({@link id.co.bni.direct.transaction.service.impl.CoreExecutionServiceImpl}) claims the
 * task EXECUTING, increments limit usage, calls core banking through direct-integration,
 * writes the legacy BASE_FT row and lands the task on EXECUTED / FAILED / UNKNOWN.
 * {@link id.co.bni.direct.transaction.service.impl.LoggingExecutionService} remains as
 * the no-op used where a core stack does not exist.
 *
 * <p>MUST be called OUTSIDE any transaction that holds the TRX_TASK row lock (i.e. after
 * the workflow claim committed): the implementation opens its own transactions and would
 * self-block on an uncommitted update of the same row. Both call sites
 * ({@code TransferServiceImpl} single-user submit, {@code TaskApprovalServiceImpl}
 * release completion) commit their workflow transaction first and then call this
 * synchronously in the request, so the HTTP answer carries the execution verdict.
 * Running it async (queue or READY_TO_EXECUTE poller) is future work.
 */
public interface ExecutionService {

    /**
     * The execution verdict. {@code status} is the task's status after the attempt
     * (EXECUTED / FAILED / UNKNOWN - or READY_TO_EXECUTE when execution did not run);
     * {@code message} is the readable reason on FAILED / UNKNOWN, null otherwise.
     */
    record ExecutionResult(String status, String message) {
    }

    /** Take over a task whose status just became READY_TO_EXECUTE (and was committed). */
    ExecutionResult execute(String taskId);
}
