package io.fdrop.cli;

/** A failure that ends the command with one of the documented exit codes and a message for stderr. */
public final class CliException extends RuntimeException {

    /** Uploaded, or {@code whoami} succeeded. */
    public static final int OK = 0;
    /** Bad option, missing file, unreadable certificate or key. */
    public static final int USAGE = 1;
    /** TLS handshake refused, untrusted server certificate, or 401. */
    public static final int AUTH = 2;
    /** The target exists and {@code --overwrite} was not given. */
    public static final int EXISTS = 3;
    /** 403 (no write permission, method not allowed, source refused) or quota exceeded (413). */
    public static final int NOT_ALLOWED = 4;
    /** Gave up after {@code --retry-for} without reaching the server; the session is kept for a later run. */
    public static final int GAVE_UP = 5;
    /** Assembly failed, hash mismatch, or an unexpected response. */
    public static final int SERVER = 6;

    private final int code;

    public CliException(int code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
