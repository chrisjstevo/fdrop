package io.fdrop.search;

import io.fdrop.core.crypto.StreamFormat;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;

import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * A Lucene directory whose files are encrypted on disk: each file starts with a random 16-byte IV, and its contents
 * are AES-256-CTR under a key derived (HKDF) from the index key and that IV. CTR because Lucene reads index files at
 * random offsets; Lucene's own checksums over the plaintext catch corruption. File names and lengths stay visible.
 */
final class EncryptedDirectory extends FilterDirectory {

    static final int IV = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] indexKey;

    EncryptedDirectory(Directory in, byte[] indexKey) {
        super(in);
        this.indexKey = indexKey.clone();
    }

    private SecretKeySpec fileKey(byte[] iv) {
        byte[] info = new byte["fdrop-search-index".length() + IV];
        System.arraycopy("fdrop-search-index".getBytes(StandardCharsets.US_ASCII), 0, info, 0, 18);
        System.arraycopy(iv, 0, info, 18, IV);
        return new SecretKeySpec(StreamFormat.hkdf(indexKey, info), "AES");
    }

    private static Cipher ctr(SecretKeySpec key, byte[] iv, long block) {
        try {
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            byte[] counter = new BigInteger(1, iv).add(BigInteger.valueOf(block)).toByteArray();
            byte[] full = new byte[IV];
            int n = Math.min(counter.length, IV);
            System.arraycopy(counter, counter.length - n, full, IV - n, n);
            c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(full));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-CTR is not available", e);
        }
    }

    @Override
    public long fileLength(String name) throws IOException {
        return Math.max(0, in.fileLength(name) - IV);
    }

    @Override
    public IndexOutput createOutput(String name, IOContext context) throws IOException {
        return new Out(in.createOutput(name, context));
    }

    @Override
    public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
        return new Out(in.createTempOutput(prefix, suffix, context));
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        IndexInput raw = in.openInput(name, context);
        byte[] iv = new byte[IV];
        try {
            raw.readBytes(iv, 0, IV);
        } catch (EOFException e) {
            raw.close();
            throw new IOException("index file " + name + " is too short to be encrypted", e);
        }
        return new In("encrypted(" + name + ")", raw, fileKey(iv), iv, 0, raw.length() - IV);
    }

    /** Encrypts as Lucene writes; the file pointer and checksum are those of the plaintext. */
    private final class Out extends IndexOutput {
        private final IndexOutput raw;
        private final Cipher cipher;
        private final CRC32 crc = new CRC32();
        private final byte[] one = new byte[1];
        private byte[] out = new byte[8192];
        private long written;

        Out(IndexOutput raw) throws IOException {
            super("encrypted(" + raw.getName() + ")", raw.getName());
            this.raw = raw;
            byte[] iv = new byte[IV];
            RANDOM.nextBytes(iv);
            raw.writeBytes(iv, 0, IV);
            this.cipher = ctr(fileKey(iv), iv, 0);
        }

        @Override
        public void writeByte(byte b) throws IOException {
            one[0] = b;
            writeBytes(one, 0, 1);
        }

        @Override
        public void writeBytes(byte[] b, int offset, int length) throws IOException {
            crc.update(b, offset, length);
            if (out.length < length) {
                out = new byte[length];
            }
            try {
                int n = cipher.update(b, offset, length, out, 0);
                raw.writeBytes(out, 0, n);
            } catch (ShortBufferException e) {
                throw new IOException(e);
            }
            written += length;
        }

        @Override
        public long getFilePointer() {
            return written;
        }

        @Override
        public long getChecksum() {
            return crc.getValue();
        }

        @Override
        public void close() throws IOException {
            try {
                byte[] rest = cipher.doFinal();
                if (rest.length > 0) {
                    raw.writeBytes(rest, 0, rest.length);
                }
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            } finally {
                raw.close();
            }
        }
    }

    /** Decrypts a window of an encrypted file; clones and slices share the raw input's positioning rules. */
    private static final class In extends IndexInput {
        private static final int BUF = 4096;
        private final IndexInput raw;
        private final SecretKeySpec key;
        private final byte[] iv;
        private final long base;
        private final long length;
        private final byte[] buf = new byte[BUF];
        private long bufStart = -1;
        private int bufLen;
        private long pos;

        In(String desc, IndexInput raw, SecretKeySpec key, byte[] iv, long base, long length) {
            super(desc);
            this.raw = raw;
            this.key = key;
            this.iv = iv;
            this.base = base;
            this.length = length;
        }

        private void fill() throws IOException {
            long abs = base + pos;
            long start = abs - abs % 16;
            int n = (int) Math.min(BUF, base + length - start);
            if (n <= 0) {
                throw new EOFException("read past end of " + this);
            }
            byte[] enc = new byte[n];
            raw.seek(IV + start);
            raw.readBytes(enc, 0, n);
            try {
                ctr(key, iv, start / 16).doFinal(enc, 0, n, buf, 0);
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            bufStart = start;
            bufLen = n;
        }

        @Override
        public byte readByte() throws IOException {
            if (pos >= length) {
                throw new EOFException("read past end of " + this);
            }
            long abs = base + pos;
            if (bufStart < 0 || abs < bufStart || abs >= bufStart + bufLen) {
                fill();
            }
            pos++;
            return buf[(int) (abs - bufStart)];
        }

        @Override
        public void readBytes(byte[] b, int offset, int len) throws IOException {
            if (pos + len > length) {
                throw new EOFException("read past end of " + this);
            }
            while (len > 0) {
                long abs = base + pos;
                if (bufStart < 0 || abs < bufStart || abs >= bufStart + bufLen) {
                    fill();
                }
                int at = (int) (abs - bufStart);
                int n = Math.min(len, bufLen - at);
                System.arraycopy(buf, at, b, offset, n);
                offset += n;
                len -= n;
                pos += n;
            }
        }

        @Override
        public long getFilePointer() {
            return pos;
        }

        @Override
        public void seek(long p) throws IOException {
            if (p < 0 || p > length) {
                throw new EOFException("seek past end of " + this + ": " + p);
            }
            pos = p;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public IndexInput clone() {
            In c = new In(toString(), raw.clone(), key, iv, base, length);
            c.pos = pos;
            return c;
        }

        @Override
        public IndexInput slice(String sliceDescription, long offset, long len) throws IOException {
            if (offset < 0 || len < 0 || offset + len > length) {
                throw new IllegalArgumentException("slice out of bounds: " + sliceDescription);
            }
            return new In(sliceDescription, raw.clone(), key, iv, base + offset, len);
        }

        @Override
        public void close() throws IOException {
            raw.close();
        }

        @Override
        public String toString() {
            return super.toString();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        Arrays.fill(indexKey, (byte) 0);
        super.close();
    }
}
