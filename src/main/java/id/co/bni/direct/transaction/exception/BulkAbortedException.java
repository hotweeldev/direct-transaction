package id.co.bni.direct.transaction.exception;

import java.util.List;

/**
 * A batch action refused as a whole: at least one of the selected tasks could not be
 * processed, so none of them were.
 *
 * <p>All-or-nothing is the decided behaviour, and it makes one thing mandatory: the
 * caller must learn about EVERY task that blocked the batch, not the first one. A user
 * who fixes one selection, retries, and is told about the next one would need as many
 * round trips as there are bad rows - each one costing another OTP. So the failures are
 * collected before anything is written and travel together in this exception.
 */
public class BulkAbortedException extends RuntimeException {

    /** One task that could not be processed, and why, in the wire's own vocabulary. */
    public record Failure(String taskId, String refNo, String errorCode, String message) {
    }

    private final transient List<Failure> failures;

    public BulkAbortedException(List<Failure> failures) {
        super("Batch dibatalkan. " + failures.size() + " transaksi tidak dapat diproses.");
        this.failures = List.copyOf(failures);
    }

    public List<Failure> failures() {
        return failures;
    }
}
