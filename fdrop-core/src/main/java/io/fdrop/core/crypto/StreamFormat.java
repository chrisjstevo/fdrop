package io.fdrop.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The {@code fdrop-stream-v1} object format (see "Encryption at rest" in SPEC.md): a fixed 1024-byte header, then the
 * file in 64 KiB plaintext segments, each stored as {@code nonce(12) ‖ AES-256-GCM ciphertext ‖ tag(16)} with the
 * segment index and a final flag as additional data. Fixed sizes make a stored object's plaintext size a function of
 * its stored size alone, so listings never read headers.
 */
public final class StreamFormat {

    public static final byte[] MAGIC = {0x46, 0x44, 0x52, 0x50, 0x45, 0x4E, 0x43, 0x01};
    public static final int HEADER_LENGTH = 1024;
    public static final int SEGMENT = 64 * 1024;
    public static final int NONCE = 12;
    public static final int TAG = 16;
    public static final int OVERHEAD = NONCE + TAG;
    public static final int STORED_SEGMENT = SEGMENT + OVERHEAD;
    public static final int ALGORITHM_AES_GCM_HKDF = 1;
    static final int MAC = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private StreamFormat() {
    }

    /** What a header says, before its MAC is checked (which needs the unwrapped key). */
    public record Header(int provider, String keyId, byte[] wrapped, byte[] bytes) {
        /** SHA-256 of the header before its MAC: identifies the object's keys. */
        public byte[] hash() {
            return sha256(bytes, 0, HEADER_LENGTH - MAC);
        }
    }

    /** The keys of one object, derived from its data key and header. */
    public record ObjectKeys(SecretKeySpec segmentKey) {
    }

    // ---- sizes ----

    public static long segments(long plain) {
        return Math.max(1, (plain + SEGMENT - 1) / SEGMENT);
    }

    public static long storedSize(long plain) {
        return HEADER_LENGTH + plain + (long) OVERHEAD * segments(plain);
    }

    /** The plaintext size of a stored object of {@code stored} bytes, or -1 when no valid object has that size. */
    public static long plainSize(long stored) {
        long d = stored - HEADER_LENGTH;
        if (d < OVERHEAD) {
            return -1;
        }
        long n = (d + STORED_SEGMENT - 1) / STORED_SEGMENT;
        long p = d - (long) OVERHEAD * n;
        return p < 0 || segments(p) != n ? -1 : p;
    }

    /** Stored bytes of segments {@code first..last} of a file of {@code plain} bytes. */
    public static long storedLength(long plain, long first, long last) {
        long n = segments(plain);
        long full = Math.min(last, n - 2) - first + 1;
        long len = Math.max(0, full) * STORED_SEGMENT;
        if (last == n - 1) {
            len += plain - (n - 1) * (long) SEGMENT + OVERHEAD;
        }
        return len;
    }

    public static long segmentOffset(long index) {
        return HEADER_LENGTH + index * STORED_SEGMENT;
    }

    public static boolean hasMagic(byte[] firstBytes) {
        return firstBytes.length >= MAGIC.length && Arrays.equals(Arrays.copyOf(firstBytes, MAGIC.length), MAGIC);
    }

    // ---- keys and header ----

    public static byte[] newDataKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return k;
    }

    /** Builds a header for a new object, MAC included. */
    public static Header newHeader(int provider, String keyId, byte[] wrapped, byte[] dek) {
        byte[] id = keyId.getBytes(StandardCharsets.UTF_8);
        int used = MAGIC.length + 4 + 1 + 4 + 1 + 2 + id.length + 2 + wrapped.length;
        if (used > HEADER_LENGTH - MAC || id.length > 0xFFFF || wrapped.length > 0xFFFF) {
            throw new IllegalArgumentException("key id and wrapped key do not fit the header");
        }
        ByteBuffer b = ByteBuffer.allocate(HEADER_LENGTH);
        b.put(MAGIC).putInt(HEADER_LENGTH).put((byte) ALGORITHM_AES_GCM_HKDF).putInt(SEGMENT).put((byte) provider)
                .putShort((short) id.length).put(id).putShort((short) wrapped.length).put(wrapped);
        byte[] bytes = b.array();
        byte[] mac = hmac(hkdf(dek, "fdrop-stream-v1 header"), bytes, HEADER_LENGTH - MAC);
        System.arraycopy(mac, 0, bytes, HEADER_LENGTH - MAC, MAC);
        return new Header(provider, keyId, wrapped, bytes);
    }

    /** Reads a header's fields; the MAC is checked by {@link #keys} once the data key is known. */
    public static Header parseHeader(byte[] bytes) throws EncryptionException {
        if (bytes.length != HEADER_LENGTH || !hasMagic(bytes)) {
            throw new EncryptionException(EncryptionException.Reason.HEADER, "not an fdrop-stream-v1 header");
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            b.position(MAGIC.length);
            if (b.getInt() != HEADER_LENGTH || b.get() != ALGORITHM_AES_GCM_HKDF || b.getInt() != SEGMENT) {
                throw new EncryptionException(EncryptionException.Reason.HEADER, "unsupported header parameters");
            }
            int provider = b.get() & 0xFF;
            byte[] id = new byte[b.getShort() & 0xFFFF];
            b.get(id);
            byte[] wrapped = new byte[b.getShort() & 0xFFFF];
            b.get(wrapped);
            if (b.position() > HEADER_LENGTH - MAC) {
                throw new EncryptionException(EncryptionException.Reason.HEADER, "header fields overflow");
            }
            return new Header(provider, new String(id, StandardCharsets.UTF_8), wrapped, bytes);
        } catch (java.nio.BufferUnderflowException e) {
            throw new EncryptionException(EncryptionException.Reason.HEADER, "header fields overflow");
        }
    }

    /** Checks the header's MAC with the data key and derives the segment key. */
    public static ObjectKeys keys(Header h, byte[] dek) throws EncryptionException {
        byte[] expected = hmac(hkdf(dek, "fdrop-stream-v1 header"), h.bytes(), HEADER_LENGTH - MAC);
        byte[] actual = Arrays.copyOfRange(h.bytes(), HEADER_LENGTH - MAC, HEADER_LENGTH);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new EncryptionException(EncryptionException.Reason.HEADER, "header MAC does not verify");
        }
        byte[] info = concat("fdrop-stream-v1 segment".getBytes(StandardCharsets.US_ASCII), h.hash());
        return new ObjectKeys(new SecretKeySpec(hkdf(dek, info), "AES"));
    }

    // ---- segments ----

    /** Encrypts one segment of plaintext from {@code plain} (all remaining bytes) into {@code out}. */
    public static void encryptSegment(Cipher c, ObjectKeys k, long index, boolean last, ByteBuffer plain,
                                      ByteBuffer out) {
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        try {
            c.init(Cipher.ENCRYPT_MODE, k.segmentKey(), new GCMParameterSpec(TAG * 8, nonce));
            c.updateAAD(aad(index, last));
            out.put(nonce);
            c.doFinal(plain, out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM failed", e);
        }
    }

    /** Decrypts one stored segment (all remaining bytes of {@code stored}) into {@code out}. */
    public static void decryptSegment(Cipher c, ObjectKeys k, long index, boolean last, ByteBuffer stored,
                                      ByteBuffer out) throws EncryptionException {
        if (stored.remaining() < OVERHEAD) {
            throw new EncryptionException(EncryptionException.Reason.TRUNCATED, "segment " + index + " is too short");
        }
        byte[] nonce = new byte[NONCE];
        stored.get(nonce);
        try {
            c.init(Cipher.DECRYPT_MODE, k.segmentKey(), new GCMParameterSpec(TAG * 8, nonce));
            c.updateAAD(aad(index, last));
            c.doFinal(stored, out);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new EncryptionException(EncryptionException.Reason.SEGMENT, "segment " + index
                    + " does not authenticate");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM failed", e);
        }
    }

    public static Cipher cipher() {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    private static byte[] aad(long index, boolean last) {
        return ByteBuffer.allocate(9).putLong(index).put((byte) (last ? 1 : 0)).array();
    }

    // ---- primitives ----

    /** HKDF-SHA-256 (RFC 5869) with an empty salt, 32 bytes out. */
    static byte[] hkdf(byte[] ikm, String info) {
        return hkdf(ikm, info.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] hkdf(byte[] ikm, byte[] info) {
        byte[] prk = hmac(new byte[32], ikm, ikm.length);
        byte[] t = hmac(prk, concat(info, new byte[]{1}), info.length + 1);
        return Arrays.copyOf(t, 32);
    }

    private static byte[] hmac(byte[] key, byte[] data, int len) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            m.update(data, 0, len);
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[] data, int off, int len) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(data, off, len);
            return d.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
