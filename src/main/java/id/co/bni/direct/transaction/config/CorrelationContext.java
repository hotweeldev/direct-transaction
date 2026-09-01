package id.co.bni.direct.transaction.config;

import org.slf4j.MDC;

/**
 * Reads the current request's correlation id.
 *
 * <p>Deliberately the same shape as {@code hw.auth.shared.correlation.CorrelationContext},
 * including the {@code "-"} answer off a request thread, so swapping this service onto the
 * shared starter later is an import change rather than a rewrite.
 */
public final class CorrelationContext {

    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_REQUEST_PATH = "requestPath";

    private CorrelationContext() {
    }

    /** The id, or {@code "-"} when called outside a request thread. */
    public static String currentCorrelationId() {
        String value = MDC.get(MDC_CORRELATION_ID);
        return value == null ? "-" : value;
    }

    public static String currentRequestPath() {
        String value = MDC.get(MDC_REQUEST_PATH);
        return value == null ? "-" : value;
    }
}
