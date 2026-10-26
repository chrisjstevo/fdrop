package io.fdrop.core.crypto;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.crypto.StreamFormat.Header;
import io.fdrop.core.crypto.StreamFormat.ObjectKeys;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.storage.MultipartUpload;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.PartInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.storage.WriteHandle;
import io.fdrop.core.storage.WriteOptions;

import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Encryption at rest: wraps the configured backend so every byte written is encrypted with the object's own data key
 * and every byte read is decrypted and authenticated, as a stream (see "Encryption at rest" in SPEC.md and
 * {@link StreamFormat}). Callers see plaintext and plaintext sizes; the backend sees only ciphertext.
 *
 * <p>A resumable upload carries its header in its multipart id ({@code fdrop-enc1|…}), so any node can encrypt any
 * chunk after a restart: the id holds the wrapped data key, never the key itself.
 */
public final class EncryptingStorage implements StorageBackend {

    static final String MULTIPART_PREFIX = "fdrop-enc1|";

    private final StorageBackend inner;
    private final KeyProvider keys;
    private final FdropConfig.Encryption cfg;
    private final FdropMetrics metrics;
    private final Lru<ObjectKeys> keyCache;
    private final Lru<Object> headerCache;
    private final long cacheTtl;

    /**
     * @param keys the KEK provider; may be null only when encryption is off, and then encrypted objects cannot be
     *             read
     */
    public EncryptingStorage(StorageBackend inner, KeyProvider keys, FdropConfig.Encryption cfg,
                             FdropMetrics metrics) {
        if (cfg.enabled && keys == null) {
            throw new IllegalStateException("encryption at rest is on but no key provider is configured");
        }
        this.inner = inner;
        this.keys = keys;
        this.cfg = cfg;
        this.metrics = metrics;
        this.keyCache = new Lru<>(cfg.dekCache.maxEntries);
        this.headerCache = new Lru<>(cfg.dekCache.maxEntries);
        this.cacheTtl = Durations.parse(cfg.dekCache.ttl).toMillis();
    }

    public StorageBackend inner() {
        return inner;
    }

    public KeyProvider keyProvider() {
        return keys;
    }

    public boolean enabled() {
        return cfg.enabled;
    }

    /** Objects without the header may be plaintext: while encryption is off, or while migrating. */
    private boolean plaintextPossible() {
        return !cfg.enabled || cfg.allowPlaintextReads;
    }

    /** Wraps and unwraps a test data key with the primary KEK: the startup and readiness check. */
    public void checkKeys() throws IOException {
        if (keys == null) {
            return;
        }
        byte[] dek = StreamFormat.newDataKey();
        KeyProvider.Wrapped w = wrap(dek);
        if (!Arrays.equals(dek, unwrap(w.keyId(), w.wrapped()))) {
            throw new IOException("the key provider returned a different key");
        }
    }

    // ---- key provider calls, timed ----

    private KeyProvider.Wrapped wrap(byte[] dek) throws IOException {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            KeyProvider.Wrapped w = keys.wrap(dek);
            ok = true;
            return w;
        } finally {
            metrics.kek(keys.name(), "wrap", ok, System.nanoTime() - start);
        }
    }

    private byte[] unwrap(String keyId, byte[] wrapped) throws IOException {
        if (keys == null) {
            throw new EncryptionException(EncryptionException.Reason.UNKNOWN_KEY, "no key provider is configured");
        }
        long start = System.nanoTime();
        boolean ok = false;
        try {
            byte[] dek = keys.unwrap(keyId, wrapped);
            ok = true;
            return dek;
        } catch (EncryptionException e) {
            ok = true;
            throw e;
        } finally {
            metrics.kek(keys.name(), "unwrap", ok, System.nanoTime() - start);
        }
    }

    /** The object's keys, checking its header MAC; cached by header hash. */
    ObjectKeys keysFor(Header h) throws IOException {
        if (keys != null && h.provider() != keys.code()) {
            throw new EncryptionException(EncryptionException.Reason.UNKNOWN_KEY, "object was encrypted with key"
                    + " provider " + h.provider() + ", not " + keys.name());
        }
        String id = HexFormat.of().formatHex(h.hash());
        ObjectKeys cached = keyCache.get(id);
        if (cached != null) {
            metrics.dekCache(true);
            return cached;
        }
        metrics.dekCache(false);
        byte[] dek = unwrap(h.keyId(), h.wrapped());
        try {
            ObjectKeys k = StreamFormat.keys(h, dek);
            keyCache.put(id, k, cacheTtl);
            return k;
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** A refused read, for the admin screen. */
    public record Failure(String storageKey, String reason, long at) {
    }

    private final java.util.ArrayDeque<Failure> failures = new java.util.ArrayDeque<>();

    /** The last 20 refused reads on this node, newest first. */
    public List<Failure> recentFailures() {
        synchronized (failures) {
            return List.copyOf(failures);
        }
    }

    /** The KEK id in a stored object's header, or {@code plaintext} when it has none. */
    public String keyIdOf(ObjectInfo stored) throws IOException {
        Object h = header(stored);
        return h instanceof Header hh ? hh.keyId() : "plaintext";
    }

    private EncryptionException failed(StoragePath path, EncryptionException e) {
        synchronized (failures) {
            failures.addFirst(new Failure(path.toString(), e.reason().label(), System.currentTimeMillis()));
            while (failures.size() > 20) {
                failures.removeLast();
            }
        }
        metrics.encryptionFailure(e.reason().label());
        Audit.log("storage.decrypt.failed", "storageKey", path.toString(), "reason", e.reason().label(),
                "detail", e.getMessage());
        return e;
    }

    // ---- reading ----

    private static String cacheKey(ObjectInfo o) {
        return o.path() + "|" + o.etag() + "|" + o.size() + "|" + o.lastModified();
    }

    /** True when the stored object starts with the encryption header. Cached per object version. */
    private boolean encrypted(ObjectInfo o) throws IOException {
        if (!plaintextPossible()) {
            return true;
        }
        if (o.size() < StreamFormat.HEADER_LENGTH + StreamFormat.OVERHEAD) {
            return false;
        }
        Object h = header(o);
        return h instanceof Header;
    }

    /** The object's header, or {@link Boolean#FALSE} when it has none. */
    private Object header(ObjectInfo o) throws IOException {
        String ck = cacheKey(o);
        Object cached = headerCache.get(ck);
        if (cached != null) {
            return cached;
        }
        byte[] first = new byte[(int) Math.min(StreamFormat.HEADER_LENGTH, o.size())];
        try (ReadableByteChannel ch = inner.openRead(o.path(), 0, first.length)) {
            readFully(ch, ByteBuffer.wrap(first));
        }
        Object h = StreamFormat.hasMagic(first) && first.length == StreamFormat.HEADER_LENGTH
                ? StreamFormat.parseHeader(first) : Boolean.FALSE;
        headerCache.put(ck, h, cacheTtl);
        return h;
    }

    private ObjectInfo plain(ObjectInfo o) throws IOException {
        if (o.directory() || !encrypted(o)) {
            return o;
        }
        long p = StreamFormat.plainSize(o.size());
        return new ObjectInfo(o.path(), Math.max(0, p), o.lastModified(), o.etag(), o.contentType(), false);
    }

    @Override
    public Optional<ObjectInfo> stat(StoragePath path) throws IOException {
        Optional<ObjectInfo> o = inner.stat(path);
        return o.isEmpty() ? o : Optional.of(plain(o.get()));
    }

    @Override
    public Stream<ObjectInfo> list(StoragePath dir, boolean recursive) throws IOException {
        Stream<ObjectInfo> s = inner.list(dir, recursive);
        return s.map(o -> {
            try {
                return plain(o);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    @Override
    public ReadableByteChannel openRead(StoragePath path, long offset, long length) throws IOException {
        ObjectInfo o = inner.stat(path).orElseThrow(() -> new NoSuchFileException(path.toString()));
        if (plaintextPossible() && !encrypted(o)) {
            metrics.plaintextRead();
            return inner.openRead(path, offset, length);
        }
        Object hv = header(o);
        if (!(hv instanceof Header h)) {
            throw failed(path, new EncryptionException(EncryptionException.Reason.PLAINTEXT,
                    "object has no encryption header"));
        }
        long p = StreamFormat.plainSize(o.size());
        if (p < 0) {
            throw failed(path, new EncryptionException(EncryptionException.Reason.TRUNCATED,
                    "stored size " + o.size() + " is not a valid encrypted object"));
        }
        ObjectKeys k;
        try {
            k = keysFor(h);
        } catch (EncryptionException e) {
            throw failed(path, e);
        }
        long end = length < 0 ? p : Math.min(p, offset + length);
        if (offset >= end) {
            return new Empty();
        }
        long first = offset / StreamFormat.SEGMENT;
        long last = (end - 1) / StreamFormat.SEGMENT;
        ReadableByteChannel in = inner.openRead(path, StreamFormat.segmentOffset(first),
                StreamFormat.storedLength(p, first, last));
        return new Decrypting(in, k, path, p, first, last, offset - first * StreamFormat.SEGMENT, end - offset);
    }

    /** Decrypts segments {@code first..last} as they arrive, trimming to the requested plaintext range. */
    private final class Decrypting implements ReadableByteChannel {
        private final ReadableByteChannel in;
        private final ObjectKeys k;
        private final StoragePath path;
        private final long plainSize;
        private final long segments;
        private final long last;
        private final Cipher cipher = StreamFormat.cipher();
        private final ByteBuffer stored = ByteBuffer.allocate(StreamFormat.STORED_SEGMENT);
        private final ByteBuffer out = ByteBuffer.allocate(StreamFormat.SEGMENT);
        private long index;
        private long skip;
        private long remaining;
        private boolean open = true;

        Decrypting(ReadableByteChannel in, ObjectKeys k, StoragePath path, long plainSize, long first, long last,
                   long skip, long want) {
            this.in = in;
            this.k = k;
            this.path = path;
            this.plainSize = plainSize;
            this.segments = StreamFormat.segments(plainSize);
            this.index = first;
            this.last = last;
            this.skip = skip;
            this.remaining = want;
            out.limit(0);
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!out.hasRemaining()) {
                if (remaining == 0 || index > last) {
                    return -1;
                }
                next();
            }
            int n = Math.min(dst.remaining(), out.remaining());
            ByteBuffer slice = out.slice(out.position(), n);
            dst.put(slice);
            out.position(out.position() + n);
            return n;
        }

        private void next() throws IOException {
            boolean isLast = index == segments - 1;
            int len = isLast ? (int) (plainSize - index * StreamFormat.SEGMENT) + StreamFormat.OVERHEAD
                    : StreamFormat.STORED_SEGMENT;
            stored.clear().limit(len);
            int got = readFully(in, stored);
            if (got < len) {
                throw failed(path, new EncryptionException(EncryptionException.Reason.TRUNCATED,
                        "segment " + index + " is short"));
            }
            stored.flip();
            out.clear();
            try {
                StreamFormat.decryptSegment(cipher, k, index, isLast, stored, out);
            } catch (EncryptionException e) {
                throw failed(path, e);
            }
            out.flip();
            metrics.encryptionBytes("decrypt", out.remaining());
            if (skip > 0) {
                out.position((int) skip);
                skip = 0;
            }
            if (out.remaining() > remaining) {
                out.limit(out.position() + (int) remaining);
            }
            remaining -= out.remaining();
            index++;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
            in.close();
        }
    }

    private static final class Empty implements ReadableByteChannel {
        private boolean open = true;

        @Override
        public int read(ByteBuffer dst) {
            return -1;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    // ---- writing ----

    /** A fresh data key and header for a new object. */
    private record NewObject(Header header, ObjectKeys keys) {
    }

    private NewObject newObject() throws IOException {
        byte[] dek = StreamFormat.newDataKey();
        try {
            KeyProvider.Wrapped w = wrap(dek);
            Header h = StreamFormat.newHeader(keys.code(), w.keyId(), w.wrapped(), dek);
            ObjectKeys k = StreamFormat.keys(h, dek);
            keyCache.put(HexFormat.of().formatHex(h.hash()), k, cacheTtl);
            return new NewObject(h, k);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    @Override
    public WriteHandle openWrite(StoragePath path, WriteOptions opts) throws IOException {
        if (!cfg.enabled) {
            return inner.openWrite(path, opts);
        }
        NewObject n = newObject();
        WriteOptions storedOpts = opts.expectedSize() >= 0
                ? opts.withExpectedSize(StreamFormat.storedSize(opts.expectedSize())) : opts;
        WriteHandle h = inner.openWrite(path, storedOpts);
        try {
            writeFully(h, ByteBuffer.wrap(n.header().bytes()));
        } catch (IOException | RuntimeException e) {
            h.abort();
            throw e;
        }
        return new Encrypting(h, n.keys(), path);
    }

    /**
     * Encrypts as bytes arrive. One full segment is held back until more bytes come, since only {@link #commit}
     * knows which segment is the last.
     */
    private final class Encrypting implements WriteHandle {
        private final WriteHandle h;
        private final ObjectKeys k;
        private final StoragePath path;
        private final Cipher cipher = StreamFormat.cipher();
        private final ByteBuffer buf = ByteBuffer.allocate(StreamFormat.SEGMENT);
        private final ByteBuffer out = ByteBuffer.allocate(StreamFormat.STORED_SEGMENT);
        private long index;
        private long written;

        Encrypting(WriteHandle h, ObjectKeys k, StoragePath path) {
            this.h = h;
            this.k = k;
            this.path = path;
        }

        @Override
        public long written() {
            return written;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            int n = src.remaining();
            while (src.hasRemaining()) {
                if (!buf.hasRemaining()) {
                    flush(false);
                }
                int take = Math.min(buf.remaining(), src.remaining());
                ByteBuffer part = src.slice(src.position(), take);
                buf.put(part);
                src.position(src.position() + take);
            }
            written += n;
            return n;
        }

        private void flush(boolean last) throws IOException {
            buf.flip();
            out.clear();
            metrics.encryptionBytes("encrypt", buf.remaining());
            StreamFormat.encryptSegment(cipher, k, index++, last, buf, out);
            out.flip();
            writeFully(h, out);
            buf.clear();
        }

        @Override
        public ObjectInfo commit() throws IOException {
            flush(true);
            ObjectInfo o = h.commit();
            return new ObjectInfo(o.path(), written, o.lastModified(), o.etag(), o.contentType(), false);
        }

        @Override
        public void abort() {
            h.abort();
        }

        @Override
        public void close() {
            h.close();
        }

        @Override
        public boolean isOpen() {
            return h.isOpen();
        }

        @Override
        public String toString() {
            return "encrypting write to " + path;
        }
    }

    // ---- multipart ----

    @Override
    public MultipartUpload beginMultipart(StoragePath target, WriteOptions opts) throws IOException {
        if (!cfg.enabled) {
            return inner.beginMultipart(target, opts);
        }
        if (opts.expectedSize() < 0 || opts.partSize() <= 0 || opts.partSize() % StreamFormat.SEGMENT != 0) {
            throw new IllegalArgumentException("encrypted multipart uploads need the total size and a part size that"
                    + " is a multiple of " + StreamFormat.SEGMENT);
        }
        NewObject n = newObject();
        MultipartUpload m = inner.beginMultipart(target, opts.withExpectedSize(
                StreamFormat.storedSize(opts.expectedSize())));
        return new EncryptingMultipart(m, n.header(), n.keys(), opts.expectedSize(), opts.partSize());
    }

    @Override
    public MultipartUpload resumeMultipart(StoragePath target, String uploadId) throws IOException {
        if (!uploadId.startsWith(MULTIPART_PREFIX)) {
            return inner.resumeMultipart(target, uploadId);
        }
        String[] f = uploadId.substring(MULTIPART_PREFIX.length()).split("\\|", 6);
        if (f.length != 6) {
            throw new IOException("malformed encrypted upload id");
        }
        long total = Long.parseLong(f[0]);
        long part = Long.parseLong(f[1]);
        int provider = Integer.parseInt(f[2]);
        String keyId = new String(Base64.getUrlDecoder().decode(f[3]), StandardCharsets.UTF_8);
        byte[] wrapped = Base64.getUrlDecoder().decode(f[4]);
        byte[] dek = unwrap(keyId, wrapped);
        try {
            Header h = StreamFormat.newHeader(provider, keyId, wrapped, dek);
            ObjectKeys k = keyCache.get(HexFormat.of().formatHex(h.hash()));
            if (k == null) {
                k = StreamFormat.keys(h, dek);
                keyCache.put(HexFormat.of().formatHex(h.hash()), k, cacheTtl);
            }
            return new EncryptingMultipart(inner.resumeMultipart(target, f[5]), h, k, total, part);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Chunk {@code k} of a resumable upload is segments {@code k·part/S …}, encrypted on its own. */
    private final class EncryptingMultipart implements MultipartUpload {
        private final MultipartUpload m;
        private final Header header;
        private final ObjectKeys k;
        private final long total;
        private final long partSize;
        private final long parts;

        EncryptingMultipart(MultipartUpload m, Header header, ObjectKeys k, long total, long partSize) {
            this.m = m;
            this.header = header;
            this.k = k;
            this.total = total;
            this.partSize = partSize;
            this.parts = Math.max(1, (total + partSize - 1) / partSize);
        }

        @Override
        public String id() {
            Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
            return MULTIPART_PREFIX + total + "|" + partSize + "|" + header.provider() + "|"
                    + b.encodeToString(header.keyId().getBytes(StandardCharsets.UTF_8)) + "|"
                    + b.encodeToString(header.wrapped()) + "|" + m.id();
        }

        @Override
        public StoragePath target() {
            return m.target();
        }

        private long storedPartSize(int partNumber, long plainLength) {
            long segs = (plainLength + StreamFormat.SEGMENT - 1) / StreamFormat.SEGMENT;
            return (partNumber == 1 ? StreamFormat.HEADER_LENGTH : 0) + plainLength + segs * StreamFormat.OVERHEAD;
        }

        private long plainPartSize(int partNumber, long storedLength) {
            long d = storedLength - (partNumber == 1 ? StreamFormat.HEADER_LENGTH : 0);
            long segs = (d + StreamFormat.STORED_SEGMENT - 1) / StreamFormat.STORED_SEGMENT;
            return d - segs * StreamFormat.OVERHEAD;
        }

        @Override
        public PartInfo putPart(int partNumber, ReadableByteChannel src, long length, String sha256Hex)
                throws IOException {
            if (length <= 0) {
                throw new IOException("encrypted parts cannot be empty");
            }
            long firstIndex = (partNumber - 1) * partSize / StreamFormat.SEGMENT;
            boolean lastPart = partNumber == parts;
            Source source = new Source(src, length, partNumber == 1 ? header.bytes() : null, firstIndex, lastPart, k,
                    sha256Hex);
            PartInfo p = m.putPart(partNumber, source, storedPartSize(partNumber, length), null);
            return new PartInfo(partNumber, length, p.etag(), source.hex());
        }

        @Override
        public List<PartInfo> listParts() throws IOException {
            List<PartInfo> out = new ArrayList<>();
            for (PartInfo p : m.listParts()) {
                out.add(new PartInfo(p.partNumber(), plainPartSize(p.partNumber(), p.size()), p.etag(), null));
            }
            return out;
        }

        @Override
        public ObjectInfo complete(List<PartInfo> partList) throws IOException {
            List<PartInfo> stored = new ArrayList<>();
            for (PartInfo p : partList) {
                stored.add(new PartInfo(p.partNumber(), storedPartSize(p.partNumber(), p.size()), p.etag(), null));
            }
            ObjectInfo o = m.complete(stored);
            return new ObjectInfo(o.path(), Math.max(0, StreamFormat.plainSize(o.size())), o.lastModified(),
                    o.etag(), o.contentType(), false);
        }

        @Override
        public void abort() {
            m.abort();
        }
    }

    /**
     * Pulls plaintext from a request body and yields the stored part: the header (part 1 only), then the encrypted
     * segments. The SHA-256 of the plaintext is checked before the last segment is released, so a mismatch fails the
     * backend's write instead of storing the part.
     */
    private final class Source implements ReadableByteChannel {
        private final ReadableByteChannel src;
        private final ObjectKeys k;
        private final boolean lastPart;
        private final String expectedSha;
        private final MessageDigest digest;
        private final Cipher cipher = StreamFormat.cipher();
        private final ByteBuffer plain = ByteBuffer.allocate(StreamFormat.SEGMENT);
        private final ByteBuffer out = ByteBuffer.allocate(Math.max(StreamFormat.STORED_SEGMENT,
                StreamFormat.HEADER_LENGTH));
        private long remaining;
        private long index;
        private byte[] header;
        private String hex;
        private boolean open = true;

        Source(ReadableByteChannel src, long length, byte[] header, long firstIndex, boolean lastPart,
               ObjectKeys k, String expectedSha) {
            this.src = src;
            this.remaining = length;
            this.header = header;
            this.index = firstIndex;
            this.lastPart = lastPart;
            this.k = k;
            this.expectedSha = expectedSha;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            out.limit(0);
        }

        String hex() {
            return hex;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!out.hasRemaining()) {
                if (header != null) {
                    out.clear();
                    out.put(header).flip();
                    header = null;
                } else if (remaining > 0) {
                    next();
                } else {
                    return -1;
                }
            }
            int n = Math.min(dst.remaining(), out.remaining());
            dst.put(out.slice(out.position(), n));
            out.position(out.position() + n);
            return n;
        }

        private void next() throws IOException {
            int want = (int) Math.min(StreamFormat.SEGMENT, remaining);
            plain.clear().limit(want);
            if (readFully(src, plain) < want) {
                throw new IOException("part is short: the body ended early");
            }
            plain.flip();
            digest.update(plain.duplicate());
            remaining -= want;
            boolean lastSegment = remaining == 0;
            if (lastSegment) {
                hex = HexFormat.of().formatHex(digest.digest());
                if (expectedSha != null && !expectedSha.equalsIgnoreCase(hex)) {
                    throw new IOException("part SHA-256 mismatch");
                }
            }
            out.clear();
            metrics.encryptionBytes("encrypt", plain.remaining());
            StreamFormat.encryptSegment(cipher, k, index++, lastPart && lastSegment, plain, out);
            out.flip();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
            src.close();
        }
    }

    // ---- the rest passes through ----

    @Override
    public String type() {
        return inner.type();
    }

    @Override
    public void init(FdropConfig config) throws IOException {
        inner.init(config);
    }

    @Override
    public void delete(StoragePath path) throws IOException {
        inner.delete(path);
    }

    @Override
    public void move(StoragePath src, StoragePath dst) throws IOException {
        inner.move(src, dst);
    }

    @Override
    public void mkdirs(StoragePath dir) throws IOException {
        inner.mkdirs(dir);
    }

    @Override
    public void close() throws IOException {
        inner.close();
    }

    // ---- helpers ----

    /** Reads until {@code dst} is full or the channel ends; returns the bytes read. */
    static int readFully(ReadableByteChannel ch, ByteBuffer dst) throws IOException {
        int total = 0;
        while (dst.hasRemaining()) {
            int n = ch.read(dst);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    static void writeFully(WritableByteChannel ch, ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            ch.write(src);
        }
    }

    /** A small bounded, expiring cache. */
    static final class Lru<V> {
        private record Entry<V>(V value, long expiresAt) {
        }

        private final Map<String, Entry<V>> map;

        Lru(int max) {
            this.map = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry<V>> eldest) {
                    return size() > Math.max(16, max);
                }
            };
        }

        synchronized V get(String key) {
            Entry<V> e = map.get(key);
            if (e == null) {
                return null;
            }
            if (e.expiresAt() < System.currentTimeMillis()) {
                map.remove(key);
                return null;
            }
            return e.value();
        }

        synchronized void put(String key, V value, long ttl) {
            map.put(key, new Entry<>(value, System.currentTimeMillis() + ttl));
        }
    }
}
