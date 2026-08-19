package in.sandesh.common;

/** RFC 7807 body. Identical shape to Nirman's, so one client error handler serves both. */
public record ApiError(String type, String title, int status, String detail, String correlationId) {
}
