package id.co.bni.direct.transaction.config;

import id.co.bni.direct.transaction.dto.response.CommonResponses.ErrorMessageResponse;
import id.co.bni.direct.transaction.exception.BulkAbortedException;
import id.co.bni.direct.transaction.exception.BusinessRuleException;
import id.co.bni.direct.transaction.exception.ForbiddenException;
import id.co.bni.direct.transaction.exception.NotFoundException;
import id.co.bni.direct.transaction.exception.ServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One error body shape for the whole service: {@code { "message", "errorCode" }}, matching
 * direct-account and direct-admin so the MFE has one conflict shape to read.
 *
 * <p>The raw exception text is returned only outside production. In production the client
 * gets a fixed sentence and the correlation id; the detail stays in the log, reachable by
 * that id. Leaking an {@code ORA-} message to a browser hands out schema names.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final boolean exposeDetail;

    public GlobalExceptionHandler(@Value("${app.errors.expose-detail:false}") boolean exposeDetail) {
        this.exposeDetail = exposeDetail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorMessageResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorMessageResponse(ex.getMessage(), "BAD_REQUEST"));
    }

    /**
     * Identity binding said no inside a controller ({@code TokenIdentity}): a
     * body-supplied userId contradicted the verified token. The exception's message names
     * both identities for the log; the caller gets the same opaque body every other
     * denial carries — never the detail.
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorMessageResponse> handleForbidden(ForbiddenException ex) {
        log.info("Forbidden: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorMessageResponse("Akses ditolak", "FORBIDDEN"));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorMessageResponse> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorMessageResponse(ex.getMessage(), "NOT_FOUND"));
    }

    /**
     * A submit-pipeline rule said no. 422, never 400: the request was well-formed, the
     * business state refused it, and the FE switches on {@code errorCode} to decide which
     * screen state to show. The message is Indonesian and renderable as-is.
     */
    /**
     * A batch refused as a whole. Same 422 and same body shape as a single business-rule
     * refusal, plus the per-task list: the screen has to be able to point at the rows that
     * blocked the batch, and one message cannot do that for twenty selections.
     */
    @ExceptionHandler(BulkAbortedException.class)
    public ResponseEntity<BulkErrorResponse> handleBulkAborted(BulkAbortedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new BulkErrorResponse(ex.getMessage(), "BULK_ABORTED", ex.failures()));
    }

    /** The batch error body: the usual pair, plus what failed. */
    public record BulkErrorResponse(String message, String errorCode,
                                    java.util.List<BulkAbortedException.Failure> failures) {
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorMessageResponse> handleBusinessRule(BusinessRuleException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorMessageResponse(ex.getMessage(), ex.code()));
    }

    /**
     * A downstream this service fronts is unreachable, or cannot be called at all in this
     * deployment. 503 rather than 500: the request was fine, the dependency is not - and
     * the screen must say so instead of pretending the inquiry failed on its merits.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorMessageResponse> handleUnavailable(ServiceUnavailableException ex) {
        log.warn("Downstream unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorMessageResponse(ex.getMessage(), "SERVICE_UNAVAILABLE"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorMessageResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Request validation failed");
        return ResponseEntity.badRequest().body(new ErrorMessageResponse(message, "VALIDATION_FAILED"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorMessageResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = CorrelationContext.currentCorrelationId();
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);

        String message = exposeDetail
                ? ex.getMessage()
                : "An unexpected error occurred. Quote correlation id " + correlationId + " when reporting it.";

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorMessageResponse(message, "INTERNAL_ERROR"));
    }
}
