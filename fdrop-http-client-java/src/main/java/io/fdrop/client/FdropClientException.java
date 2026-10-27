package io.fdrop.client;

/** Every failure the client reports. Unchecked, so calls read like local ones. */
public class FdropClientException extends RuntimeException {

    public FdropClientException(String message) {
        super(message);
    }

    public FdropClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
