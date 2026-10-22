package io.fdrop.storage.s3;

import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.storage.MultipartUpload;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.PartInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.storage.WriteHandle;
import io.fdrop.core.storage.WriteOptions;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Stores objects in an S3 bucket (or MinIO, Ceph and other S3-compatible stores via {@code endpointOverride} and
 * path-style access). Directories are zero-byte {@code dir/} marker objects plus prefix listing; chunked uploads
 * map one-to-one onto native S3 multipart uploads, so assembly happens inside S3 with no bytes through fdrop.
 */
public final class S3StorageBackend implements StorageBackend {

    /** Below this, a write is buffered and sent as a single PutObject. */
    static final int SINGLE_PUT_MAX = 16 * 1024 * 1024;
    /** Part size for streaming writes of unknown or large size (SFTP). */
    static final int STREAM_PART = 8 * 1024 * 1024;
    private static final long MAX_COPY = 5L * 1024 * 1024 * 1024;

    private S3Client s3;
    private String bucket;
    private String prefix;
    private ServerSideEncryption sse;

    @Override
    public String type() {
        return "s3";
    }

    @Override
    public void init(FdropConfig config) {
        FdropConfig.S3 c = config.storage.s3;
        if (c.bucket == null || c.bucket.isBlank()) {
            throw new IllegalArgumentException("storage.s3.bucket is required");
        }
        S3ClientBuilder b = S3Client.builder()
                .region(Region.of(c.region))
                .httpClient(AwsCrtHttpClient.builder().build())
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .forcePathStyle(c.pathStyle)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (c.endpointOverride != null && !c.endpointOverride.isBlank()) {
            b.endpointOverride(URI.create(c.endpointOverride));
        }
        init(b.build(), c.bucket, c.prefix, c.sse);
    }

    /** For tests: an explicit client. */
    public void init(S3Client client, String bucket, String prefix, String sse) {
        this.s3 = client;
        this.bucket = bucket;
        String p = prefix == null ? "" : prefix.strip();
        if (!p.isEmpty() && !p.endsWith("/")) {
            p = p + "/";
        }
        this.prefix = p.startsWith("/") ? p.substring(1) : p;
        this.sse = sse == null || sse.isBlank() ? null : ServerSideEncryption.fromValue(sse);
    }

    public static S3Client client(String endpoint, String region, AwsCredentialsProvider creds, boolean pathStyle) {
        return S3Client.builder().region(Region.of(region)).endpointOverride(URI.create(endpoint))
                .credentialsProvider(creds).forcePathStyle(pathStyle)
                .httpClient(AwsCrtHttpClient.builder().build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED).build();
    }

    // ---- keys ----

    String key(StoragePath p) {
        return prefix + p;
    }

    String dirKey(StoragePath p) {
        return p.isRoot() ? prefix : prefix + p + "/";
    }

    private StoragePath pathOf(String key) {
        String rel = key.substring(prefix.length());
        if (rel.endsWith("/")) {
            rel = rel.substring(0, rel.length() - 1);
        }
        return StoragePath.of(rel);
    }

    private static IOException io(String what, S3Exception e) {
        return new IOException(what + ": " + e.awsErrorDetails().errorCode() + " " + e.getMessage(), e);
    }

    private Optional<HeadObjectResponse> head(String key) throws IOException {
        try {
            return Optional.of(s3.headObject(r -> r.bucket(bucket).key(key)));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw io("head " + key, e);
        }
    }

    // ---- objects ----

    @Override
    public Optional<ObjectInfo> stat(StoragePath path) throws IOException {
        if (path.isRoot()) {
            return Optional.of(ObjectInfo.directory(path, Instant.EPOCH));
        }
        Optional<HeadObjectResponse> h = head(key(path));
        if (h.isPresent()) {
            return Optional.of(new ObjectInfo(path, h.get().contentLength(), h.get().lastModified(),
                    stripQuotes(h.get().eTag()), h.get().contentType(), false));
        }
        Optional<HeadObjectResponse> marker = head(dirKey(path));
        if (marker.isPresent()) {
            return Optional.of(ObjectInfo.directory(path, marker.get().lastModified()));
        }
        try {
            ListObjectsV2Response l = s3.listObjectsV2(r -> r.bucket(bucket).prefix(dirKey(path)).maxKeys(1));
            if (l.keyCount() != null && l.keyCount() > 0) {
                return Optional.of(ObjectInfo.directory(path, Instant.EPOCH));
            }
        } catch (S3Exception e) {
            throw io("list " + path, e);
        }
        return Optional.empty();
    }

    private static String stripQuotes(String etag) {
        return etag == null ? null : etag.replace("\"", "");
    }

    @Override
    public Stream<ObjectInfo> list(StoragePath dir, boolean recursive) throws IOException {
        String p = dirKey(dir);
        List<ObjectInfo> out = new ArrayList<>();
        try {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket).prefix(p);
            if (!recursive) {
                req.delimiter("/");
            }
            java.util.Set<String> dirs = new java.util.TreeSet<>();
            for (ListObjectsV2Response page : s3.listObjectsV2Paginator(req.build())) {
                for (CommonPrefix cp : page.commonPrefixes()) {
                    dirs.add(cp.prefix());
                }
                for (S3Object o : page.contents()) {
                    if (o.key().equals(p)) {
                        continue;
                    }
                    if (o.key().endsWith("/")) {
                        dirs.add(o.key());
                        continue;
                    }
                    out.add(new ObjectInfo(pathOf(o.key()), o.size(), o.lastModified(), stripQuotes(o.eTag()), null,
                            false));
                    if (recursive) {
                        // implied parent directories of nested objects
                        String rel = o.key().substring(p.length());
                        int slash = rel.lastIndexOf('/');
                        while (slash > 0) {
                            dirs.add(p + rel.substring(0, slash) + "/");
                            slash = rel.lastIndexOf('/', slash - 1);
                        }
                    }
                }
            }
            for (String d : dirs) {
                out.add(ObjectInfo.directory(pathOf(d), Instant.EPOCH));
            }
        } catch (S3Exception e) {
            throw io("list " + dir, e);
        }
        return out.stream();
    }

    @Override
    public ReadableByteChannel openRead(StoragePath path, long offset, long length) throws IOException {
        if (length == 0) {
            return Channels.newChannel(InputStream.nullInputStream());
        }
        String range = length < 0 ? (offset > 0 ? "bytes=" + offset + "-" : null)
                : "bytes=" + offset + "-" + (offset + length - 1);
        try {
            InputStream in = s3.getObject(r -> {
                r.bucket(bucket).key(key(path));
                if (range != null) {
                    r.range(range);
                }
            });
            return Channels.newChannel(in);
        } catch (NoSuchKeyException e) {
            throw FdropException.notFound("no such file");
        } catch (S3Exception e) {
            if (e.statusCode() == 416) {
                return Channels.newChannel(InputStream.nullInputStream());
            }
            throw io("get " + path, e);
        }
    }

    @Override
    public WriteHandle openWrite(StoragePath path, WriteOptions opts) throws IOException {
        if (!opts.overwrite() && head(key(path)).isPresent()) {
            throw FdropException.conflict("file exists");
        }
        return new S3Write(path, opts);
    }

    /** Buffers up to one part; small writes become one PutObject, larger ones a multipart upload. */
    private final class S3Write implements WriteHandle {
        private final StoragePath path;
        private final WriteOptions opts;
        private final int bufferSize;
        private ByteBuffer buf;
        private String uploadId;
        private final List<CompletedPart> parts = new ArrayList<>();
        private long written;
        private boolean finished;

        S3Write(StoragePath path, WriteOptions opts) {
            this.path = path;
            this.opts = opts;
            this.bufferSize = opts.expectedSize() >= 0 && opts.expectedSize() <= SINGLE_PUT_MAX
                    ? (int) Math.max(1, opts.expectedSize()) : STREAM_PART;
            this.buf = ByteBuffer.allocate(bufferSize);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            int n = 0;
            while (src.hasRemaining()) {
                if (!buf.hasRemaining()) {
                    if (bufferSize < STREAM_PART) {
                        // more than the declared size: grow into streaming mode
                        ByteBuffer bigger = ByteBuffer.allocate(STREAM_PART);
                        buf.flip();
                        bigger.put(buf);
                        buf = bigger;
                        if (buf.hasRemaining()) {
                            continue;
                        }
                    }
                    flushPart();
                }
                int take = Math.min(src.remaining(), buf.remaining());
                ByteBuffer slice = src.slice(src.position(), take);
                buf.put(slice);
                src.position(src.position() + take);
                n += take;
            }
            written += n;
            return n;
        }

        private void flushPart() throws IOException {
            try {
                if (uploadId == null) {
                    uploadId = s3.createMultipartUpload(createRequest(key(path))).uploadId();
                }
                buf.flip();
                int number = parts.size() + 1;
                UploadPartResponse r = s3.uploadPart(UploadPartRequest.builder().bucket(bucket).key(key(path))
                        .uploadId(uploadId).partNumber(number).contentLength((long) buf.remaining()).build(),
                        RequestBody.fromByteBuffer(buf));
                parts.add(CompletedPart.builder().partNumber(number).eTag(r.eTag()).build());
                buf.clear();
            } catch (S3Exception e) {
                abort();
                throw io("upload part", e);
            }
        }

        @Override
        public long written() {
            return written;
        }

        @Override
        public ObjectInfo commit() throws IOException {
            if (finished) {
                throw new IllegalStateException("already finished");
            }
            try {
                if (!opts.overwrite() && head(key(path)).isPresent()) {
                    abort();
                    throw FdropException.conflict("file exists");
                }
                if (uploadId == null) {
                    buf.flip();
                    s3.putObject(putRequest(key(path)), RequestBody.fromByteBuffer(buf));
                } else {
                    if (buf.position() > 0) {
                        flushPart();
                    }
                    s3.completeMultipartUpload(r -> r.bucket(bucket).key(key(path)).uploadId(uploadId)
                            .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()));
                }
                finished = true;
                buf = null;
                return stat(path).orElseThrow(() -> new IOException("object missing after upload"));
            } catch (S3Exception e) {
                abort();
                throw io("commit " + path, e);
            }
        }

        @Override
        public void abort() {
            finished = true;
            buf = null;
            if (uploadId != null) {
                try {
                    s3.abortMultipartUpload(r -> r.bucket(bucket).key(key(path)).uploadId(uploadId));
                } catch (S3Exception ignored) {
                    // the bucket lifecycle rule cleans up leftovers
                }
                uploadId = null;
            }
        }

        @Override
        public boolean isOpen() {
            return !finished;
        }

        @Override
        public void close() {
            if (!finished) {
                abort();
            }
        }
    }

    private PutObjectRequest putRequest(String key) {
        PutObjectRequest.Builder b = PutObjectRequest.builder().bucket(bucket).key(key);
        if (sse != null) {
            b.serverSideEncryption(sse);
        }
        return b.build();
    }

    private CreateMultipartUploadRequest createRequest(String key) {
        CreateMultipartUploadRequest.Builder b = CreateMultipartUploadRequest.builder().bucket(bucket).key(key);
        if (sse != null) {
            b.serverSideEncryption(sse);
        }
        return b.build();
    }

    @Override
    public void delete(StoragePath path) throws IOException {
        if (path.isRoot()) {
            throw FdropException.forbidden("cannot delete the bucket root");
        }
        List<ObjectIdentifier> batch = new ArrayList<>();
        batch.add(ObjectIdentifier.builder().key(key(path)).build());
        batch.add(ObjectIdentifier.builder().key(dirKey(path)).build());
        try {
            for (ListObjectsV2Response page : s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(dirKey(path)))) {
                for (S3Object o : page.contents()) {
                    batch.add(ObjectIdentifier.builder().key(o.key()).build());
                    if (batch.size() == 1000) {
                        deleteBatch(batch);
                    }
                }
            }
            deleteBatch(batch);
        } catch (S3Exception e) {
            throw io("delete " + path, e);
        }
    }

    private void deleteBatch(List<ObjectIdentifier> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<ObjectIdentifier> copy = List.copyOf(batch);
        batch.clear();
        s3.deleteObjects(r -> r.bucket(bucket).delete(Delete.builder().objects(copy).quiet(true).build()));
    }

    @Override
    public void move(StoragePath src, StoragePath dst) throws IOException {
        Optional<ObjectInfo> s = stat(src);
        if (s.isEmpty()) {
            throw FdropException.notFound("no such file");
        }
        if (stat(dst).isPresent()) {
            throw FdropException.conflict("target exists");
        }
        try {
            if (!s.get().directory()) {
                copy(key(src), key(dst), s.get().size());
                s3.deleteObject(r -> r.bucket(bucket).key(key(src)));
                return;
            }
            String from = dirKey(src);
            String to = dirKey(dst);
            for (ListObjectsV2Response page : s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(from))) {
                for (S3Object o : page.contents()) {
                    copy(o.key(), to + o.key().substring(from.length()), o.size());
                }
            }
            if (head(from).isEmpty()) {
                s3.putObject(putRequest(to), RequestBody.empty());
            }
            delete(src);
        } catch (S3Exception e) {
            throw io("move " + src, e);
        }
    }

    /** CopyObject, or a multipart copy above the 5 GiB single-copy limit. */
    private void copy(String from, String to, long size) {
        if (size <= MAX_COPY) {
            s3.copyObject(r -> {
                r.sourceBucket(bucket).sourceKey(from).destinationBucket(bucket).destinationKey(to);
                if (sse != null) {
                    r.serverSideEncryption(sse);
                }
            });
            return;
        }
        String id = s3.createMultipartUpload(createRequest(to)).uploadId();
        try {
            long part = 512L * 1024 * 1024;
            List<CompletedPart> done = new ArrayList<>();
            int n = 1;
            for (long off = 0; off < size; off += part, n++) {
                long end = Math.min(size, off + part) - 1;
                int number = n;
                long start = off;
                var r = s3.uploadPartCopy(b -> b.sourceBucket(bucket).sourceKey(from).destinationBucket(bucket)
                        .destinationKey(to).uploadId(id).partNumber(number).copySourceRange("bytes=" + start + "-" + end));
                done.add(CompletedPart.builder().partNumber(number).eTag(r.copyPartResult().eTag()).build());
            }
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(to).uploadId(id)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(done).build()));
        } catch (S3Exception e) {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(to).uploadId(id));
            throw e;
        }
    }

    @Override
    public void mkdirs(StoragePath dir) throws IOException {
        if (dir.isRoot()) {
            return;
        }
        if (head(key(dir)).isPresent()) {
            throw FdropException.conflict("a file with that name exists");
        }
        try {
            if (head(dirKey(dir)).isEmpty()) {
                s3.putObject(putRequest(dirKey(dir)), RequestBody.empty());
            }
        } catch (S3Exception e) {
            throw io("mkdir " + dir, e);
        }
    }

    // ---- multipart ----

    @Override
    public MultipartUpload beginMultipart(StoragePath target, WriteOptions opts) throws IOException {
        try {
            String id = s3.createMultipartUpload(createRequest(key(target))).uploadId();
            return new S3Multipart(target, id, opts.overwrite());
        } catch (S3Exception e) {
            throw io("create multipart upload", e);
        }
    }

    @Override
    public MultipartUpload resumeMultipart(StoragePath target, String uploadId) {
        return new S3Multipart(target, uploadId, true);
    }

    private final class S3Multipart implements MultipartUpload {
        private final StoragePath target;
        private final String id;
        private final boolean overwrite;

        S3Multipart(StoragePath target, String id, boolean overwrite) {
            this.target = target;
            this.id = id;
            this.overwrite = overwrite;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public StoragePath target() {
            return target;
        }

        @Override
        public PartInfo putPart(int partNumber, ReadableByteChannel src, long length, String sha256Hex)
                throws IOException {
            CountingInput in = new CountingInput(Channels.newInputStream(src), length);
            UploadPartRequest.Builder req = UploadPartRequest.builder().bucket(bucket).key(key(target)).uploadId(id)
                    .partNumber(partNumber).contentLength(length);
            if (sha256Hex != null) {
                // S3 verifies the part against this before storing it
                req.checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex)));
            }
            try {
                UploadPartResponse r = s3.uploadPart(req.build(), RequestBody.fromInputStream(in, length));
                if (in.count < length) {
                    throw new IOException("part " + partNumber + " is short: " + in.count + " of " + length);
                }
                return new PartInfo(partNumber, length, r.eTag(), sha256Hex);
            } catch (S3Exception e) {
                if (in.count < length) {
                    throw new IOException("part " + partNumber + " is short: " + in.count + " of " + length, e);
                }
                String code = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
                if ("BadDigest".equals(code) || "InvalidDigest".equals(code) || "XAmzContentChecksumMismatch".equals(code)
                        || (e.getMessage() != null && e.getMessage().toLowerCase().contains("checksum"))) {
                    throw new IOException("part " + partNumber + " SHA-256 mismatch", e);
                }
                if (e instanceof NoSuchUploadException) {
                    throw FdropException.notFound("upload parts are gone");
                }
                throw io("upload part " + partNumber, e);
            } catch (RuntimeException e) {
                if (in.count < length) {
                    throw new IOException("part " + partNumber + " is short: " + in.count + " of " + length, e);
                }
                throw e;
            }
        }

        @Override
        public List<PartInfo> listParts() throws IOException {
            try {
                List<PartInfo> out = new ArrayList<>();
                for (var page : s3.listPartsPaginator(r -> r.bucket(bucket).key(key(target)).uploadId(id))) {
                    for (Part p : page.parts()) {
                        out.add(new PartInfo(p.partNumber(), p.size(), p.eTag(), null));
                    }
                }
                return out;
            } catch (S3Exception e) {
                throw io("list parts", e);
            }
        }

        @Override
        public ObjectInfo complete(List<PartInfo> partList) throws IOException {
            if (!overwrite && head(key(target)).isPresent()) {
                throw FdropException.conflict("file exists");
            }
            List<CompletedPart> done = partList.stream().map(p -> CompletedPart.builder().partNumber(p.partNumber())
                    .eTag(p.etag()).build()).toList();
            try {
                s3.completeMultipartUpload(r -> r.bucket(bucket).key(key(target)).uploadId(id)
                        .multipartUpload(CompletedMultipartUpload.builder().parts(done).build()));
            } catch (S3Exception e) {
                throw io("complete multipart upload", e);
            }
            return stat(target).orElseThrow(() -> new IOException("object missing after completion"));
        }

        @Override
        public void abort() {
            try {
                s3.abortMultipartUpload(r -> r.bucket(bucket).key(key(target)).uploadId(id));
            } catch (S3Exception ignored) {
                // already gone
            }
        }
    }

    /** Counts bytes and stops at the declared length, so a long body cannot overrun a part. */
    private static final class CountingInput extends InputStream {
        private final InputStream in;
        private final long limit;
        long count;

        CountingInput(InputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            if (count >= limit) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (count >= limit) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, limit - count));
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }

    @Override
    public void close() {
        if (s3 != null) {
            s3.close();
        }
    }
}
