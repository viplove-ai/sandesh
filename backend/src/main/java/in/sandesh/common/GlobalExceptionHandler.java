package in.sandesh.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The only place an error body is built. {@code server.error.include-message} is {@code never},
 * so nothing leaks around this.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException ex, HttpServletRequest request) {
        return asJson(ex.getStatus(), new ApiError(
                ex.getCode(), ex.getStatus().getReasonPhrase(), ex.getStatus().value(),
                ex.getMessage(), MDC.get("correlationId")), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex,
                                               HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("The request was not valid.");
        return asJson(HttpStatus.BAD_REQUEST, new ApiError(
                "validation", "Bad Request", 400, detail, MDC.get("correlationId")), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        // Logged in full, returned as nothing: the detail is for us, not for the caller.
        log.error("Unhandled exception", ex);
        return asJson(HttpStatus.INTERNAL_SERVER_ERROR, new ApiError(
                "internal", "Internal Server Error", 500,
                "Something went wrong. Try again.", MDC.get("correlationId")), request);
    }

    /**
     * Every error is JSON, including one raised by an endpoint that produces something else.
     *
     * <p>The stream endpoint declares {@code text/event-stream}, and Spring carries that
     * declaration into the error path as the only representation it may write — so a failure
     * there was answered with {@code HttpMediaTypeNotAcceptableException} and a 500 carrying no
     * body at all. That is the least useful thing this class can do, and it happened on the one
     * request where the cause was hardest to guess from the outside.</p>
     *
     * <p>Dropping the attribute retracts the endpoint's promise, which no longer applies once the
     * response is an error rather than a stream; naming the type explicitly then keeps content
     * negotiation out of the error path altogether, including for a client whose {@code Accept}
     * never mentioned JSON. Both are needed — either alone leaves one of the two ways this
     * fails.</p>
     */
    private ResponseEntity<ApiError> asJson(HttpStatusCode status, ApiError body,
                                            HttpServletRequest request) {
        if (request != null) {
            request.removeAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
