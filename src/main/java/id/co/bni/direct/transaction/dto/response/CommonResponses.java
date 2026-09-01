package id.co.bni.direct.transaction.dto.response;

/** Response shapes shared by more than one controller. */
public final class CommonResponses {

    private CommonResponses() {
    }

    /** Error body: {@code { "message": ..., "errorCode": ... }}. */
    public record ErrorMessageResponse(String message, String errorCode) {
    }
}
