package io.fdrop.client;

/**
 * fdrop answered with an error that retrying will not fix (4xx other than 429): the request was refused, the path
 * does not exist, the quota is full, and so on. {@code type} is the RFC 9457 problem type fdrop sent (for example
 * {@code two-factor-required}), or null.
 */
public class FdropApiException extends FdropClientException {

    private final int status;
    private final String type;
    private final String title;
    private final String detail;

    public FdropApiException(String operation, int status, String type, String title, String detail) {
        super(operation + ": HTTP " + status + (title == null ? "" : " " + title) + (detail == null || detail.isBlank()
                ? "" : ": " + detail));
        this.status = status;
        this.type = type;
        this.title = title;
        this.detail = detail;
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }
}
