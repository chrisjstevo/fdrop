package io.fdrop.core.error;

/**
 * A failure with an HTTP-style status, turned into RFC 9457 problem JSON over HTTP and into the closest SFTP
 * status code over SFTP.
 */
public class FdropException extends RuntimeException {

    private final int status;
    private final String title;
    private String type;

    public FdropException(int status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public FdropException(int status, String title, String detail, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** The RFC 9457 problem type, or null for {@code about:blank}. */
    public String type() {
        return type;
    }

    public FdropException withType(String type) {
        this.type = type;
        return this;
    }

    public static FdropException badRequest(String detail) {
        return new FdropException(400, "Bad request", detail);
    }

    public static FdropException unauthorized(String detail) {
        return new FdropException(401, "Unauthorized", detail);
    }

    public static FdropException forbidden(String detail) {
        return new FdropException(403, "Forbidden", detail);
    }

    public static FdropException notFound(String detail) {
        return new FdropException(404, "Not found", detail);
    }

    public static FdropException conflict(String detail) {
        return new FdropException(409, "Conflict", detail);
    }

    public static FdropException tooLarge(String detail) {
        return new FdropException(413, "Payload too large", detail);
    }

    public static FdropException quota(String detail) {
        return new FdropException(507, "Insufficient storage", detail);
    }

    public static FdropException unavailable(String detail) {
        return new FdropException(503, "Service unavailable", detail);
    }
}
