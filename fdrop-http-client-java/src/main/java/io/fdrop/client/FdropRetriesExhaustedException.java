package io.fdrop.client;

/**
 * A call kept failing with errors worth retrying (network errors, timeouts, 429 and 5xx) and the client gave up after
 * {@code attempts} tries. The cause is the last failure. For an upload, the client then tries once to discard the
 * unfinished upload session; if the server cannot be reached either, it discards the session itself when it expires.
 * Nothing partial is ever visible as a file.
 */
public class FdropRetriesExhaustedException extends FdropClientException {

    private final int attempts;

    public FdropRetriesExhaustedException(String operation, int attempts, Throwable lastFailure) {
        super(operation + ": gave up after " + attempts + " attempt" + (attempts == 1 ? "" : "s") + ": "
                + lastFailure.getMessage(), lastFailure);
        this.attempts = attempts;
    }

    public int attempts() {
        return attempts;
    }
}
