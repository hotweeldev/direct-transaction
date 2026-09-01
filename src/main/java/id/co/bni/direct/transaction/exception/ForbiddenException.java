package id.co.bni.direct.transaction.exception;

/**
 * 403 with the same opaque body every other denial carries ("Akses ditolak",
 * {@code FORBIDDEN}). Thrown where a check runs inside a controller or service rather
 * than on the filter/interceptor chain — today that is {@code TokenIdentity} refusing a
 * body-supplied {@code userId} that contradicts the verified token.
 *
 * <p>The message is for the LOG only; {@code GlobalExceptionHandler} never echoes it to
 * the caller, so it may name the identities involved.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String logMessage) {
        super(logMessage);
    }
}
