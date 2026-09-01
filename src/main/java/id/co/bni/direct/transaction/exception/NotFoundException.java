package id.co.bni.direct.transaction.exception;

/** The thing addressed does not exist within the caller's company. Answered as 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
