package io.fdrop.core.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM encryption of small secrets at rest (forward destination private keys). */
public final class SecretBox {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretBox(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("encryption key must be 32 bytes, got " + key.length);
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /** From a base64 config value; returns null when the value is empty (feature disabled). */
    public static SecretBox fromBase64(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new SecretBox(Base64.getDecoder().decode(value.trim()));
    }

    public byte[] seal(byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(1 + NONCE_BYTES + ct.length).put((byte) 1).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public byte[] open(byte[] sealed) {
        try {
            ByteBuffer in = ByteBuffer.wrap(sealed);
            if (in.get() != 1) {
                throw new IllegalArgumentException("unknown secret format");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            in.get(nonce);
            byte[] ct = new byte[in.remaining()];
            in.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return c.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed (wrong forward.encryptionKey?)", e);
        }
    }
}
