package in.sandesh.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The only place an error body is built. {@code server.error.include-message} is {@code never},
 * so nothing leaks around this.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus()).body(new ApiError(
                ex.getCode(), ex.getStatus().getReasonPhrase(), ex.getStatus().value(),
                ex.getMessage(), MDC.get("correlationId")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("The request was not valid.");
        return ResponseEntity.badRequest().body(new ApiError(
                "validation", "Bad Request", 400, detail, MDC.get("correlationId")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex) {
        // Logged in full, returned as nothing: the detail is for us, not for the caller.
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                "internal", "Internal Server Error", 500,
                "Something went wrong. Try again.", MDC.get("correlationId")));
    }
}
