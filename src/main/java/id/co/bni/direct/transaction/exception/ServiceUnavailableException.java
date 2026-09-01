package id.co.bni.direct.transaction.exception;

/**
 * A downstream this service fronts (direct-integration, the UMAS authenticator) is
 * unreachable or cannot be called in this deployment. Answered as 503: the request was
 * fine, the dependency is not.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
