package io.fdrop.core.crypto;

import java.io.IOException;

/**
 * Holds the key-encryption keys (KEKs) and wraps and unwraps per-object data keys with them. The KEKs never leave
 * the provider: fdrop sees only wrapped keys and, briefly, the data keys they protect.
 */
public interface KeyProvider {

    /** A data key wrapped under a KEK, with that KEK's id. */
    record Wrapped(String keyId, byte[] wrapped) {
    }

    /** {@code local}, {@code aws-kms}, {@code vault}. */
    String name();

    /** The code stored in object headers: 1 local, 2 aws-kms, 3 vault. */
    int code();

    /** The KEK new objects are wrapped under. */
    String primaryKeyId();

    Wrapped wrap(byte[] dataKey) throws IOException;

    /** Unwraps a data key; an unknown key id fails with {@link EncryptionException.Reason#UNKNOWN_KEY}. */
    byte[] unwrap(String keyId, byte[] wrapped) throws IOException;
}
