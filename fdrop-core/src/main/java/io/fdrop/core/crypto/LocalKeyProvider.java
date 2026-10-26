package io.fdrop.core.crypto;

import io.fdrop.core.config.FdropConfig;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KEKs from a keyring in an environment variable or secret file: {@code id:base64key,…}, 32-byte keys, the first
 * one primary. Data keys are wrapped with AES-256-GCM: {@code nonce(12) ‖ ciphertext(32) ‖ tag(16)}, the key id as
 * additional data.
 */
public final class LocalKeyProvider implements KeyProvider {

    public static final int CODE = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    private final String primary;

    public LocalKeyProvider(String keyring) {
        if (keyring == null || keyring.isBlank()) {
            throw new IllegalStateException("storage.encryption.local.keys is empty: set it to id:base64key (32 bytes;"
                    + " openssl rand -base64 32)");
        }
        String first = null;
        for (String entry : keyring.split(",")) {
            String e = entry.strip();
            int colon = e.indexOf(':');
            if (colon <= 0) {
                throw new IllegalStateException("storage.encryption.local.keys entries are id:base64key");
            }
            String id = e.substring(0, colon);
            byte[] key;
            try {
                key = Base64.getDecoder().decode(e.substring(colon + 1).strip());
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("key " + id + " is not base64");
            }
            if (key.length != 32) {
                throw new IllegalStateException("key " + id + " must be 32 bytes, not " + key.length);
            }
            if (keys.put(id, new SecretKeySpec(key, "AES")) != null) {
                throw new IllegalStateException("key id " + id + " appears twice");
            }
            first = first == null ? id : first;
        }
        this.primary = first;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public int code() {
        return CODE;
    }

    @Override
    public String primaryKeyId() {
        return primary;
    }

    @Override
    public Wrapped wrap(byte[] dataKey) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.get(primary), new GCMParameterSpec(128, nonce));
            c.updateAAD(primary.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(dataKey);
            return new Wrapped(primary, ByteBuffer.allocate(12 + ct.length).put(nonce).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not wrap a data key", e);
        }
    }

    @Override
    public byte[] unwrap(String keyId, byte[] wrapped) throws IOException {
        SecretKeySpec k = keys.get(keyId);
        if (k == null) {
            throw new EncryptionException(EncryptionException.Reason.UNKNOWN_KEY, "key " + keyId
                    + " is not in storage.encryption.local.keys");
        }
        if (wrapped.length < 12 + 16) {
            throw new EncryptionException(EncryptionException.Reason.HEADER, "wrapped key is too short");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, wrapped, 0, 12));
            c.updateAAD(keyId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return c.doFinal(wrapped, 12, wrapped.length - 12);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new EncryptionException(EncryptionException.Reason.HEADER, "wrapped key does not authenticate");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not unwrap a data key", e);
        }
    }

    /** Registered for {@code keyProvider: local}. */
    public static final class Factory implements KeyProviderFactory {
        @Override
        public String name() {
            return "local";
        }

        @Override
        public KeyProvider create(FdropConfig.Encryption cfg) {
            return new LocalKeyProvider(cfg.local.keys);
        }
    }
}
