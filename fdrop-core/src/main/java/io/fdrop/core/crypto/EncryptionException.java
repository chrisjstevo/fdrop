package io.fdrop.core.crypto;

import java.io.IOException;
import java.util.Locale;

/** A stored object that fails authentication or cannot be decrypted. Never carries plaintext or key material. */
public final class EncryptionException extends IOException {

    /** Why a read was refused; the lower-case name is the metric and audit reason. */
    public enum Reason {
        HEADER("header_mac"), SEGMENT("segment_auth"), TRUNCATED("truncated"), UNKNOWN_KEY("unknown_key"),
        PLAINTEXT("plaintext_refused");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Reason reason;

    public EncryptionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    @Override
    public String toString() {
        return "EncryptionException(" + reason.name().toLowerCase(Locale.ROOT) + "): " + getMessage();
    }
}
