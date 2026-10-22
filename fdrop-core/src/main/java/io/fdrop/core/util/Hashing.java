package io.fdrop.core.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** SHA-256 helpers and random ids. */
public final class Hashing {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Hashing() {
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256().digest(data));
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A URL-safe random token with {@code bytes} bytes of entropy. */
    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** A sortable id: millisecond timestamp in base 36 plus 10 random characters, with a prefix. */
    public static String newId(String prefix) {
        String time = Long.toString(System.currentTimeMillis(), 36);
        String rnd = randomToken(8).replace('-', 'x').replace('_', 'y').substring(0, 10);
        return prefix + "_" + time + rnd;
    }

    /**
     * Wraps a channel so that every byte read is hashed and counted, and reading stops after {@code limit}
     * bytes.
     */
    public static final class DigestingChannel implements ReadableByteChannel {
        private final ReadableByteChannel in;
        private final MessageDigest digest = sha256();
        private final long limit;
        private long count;

        public DigestingChannel(ReadableByteChannel in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (limit >= 0 && count >= limit) {
                return -1;
            }
            ByteBuffer view = dst;
            if (limit >= 0 && dst.remaining() > limit - count) {
                view = dst.slice(dst.position(), (int) (limit - count));
            }
            int start = view.position();
            int n = in.read(view);
            if (n > 0) {
                ByteBuffer done = view.duplicate();
                done.flip();
                done.position(start);
                digest.update(done);
                count += n;
                if (view != dst) {
                    dst.position(dst.position() + n);
                }
            }
            return n;
        }

        public long count() {
            return count;
        }

        public String hex() {
            return HexFormat.of().formatHex(digest.digest());
        }

        @Override
        public boolean isOpen() {
            return in.isOpen();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
