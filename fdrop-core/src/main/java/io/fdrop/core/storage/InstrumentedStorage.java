package io.fdrop.core.storage;

import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.metrics.FdropMetrics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Wraps a backend and records {@code fdrop_storage_operation_seconds} for every call. */
public final class InstrumentedStorage implements StorageBackend {

    @FunctionalInterface
    private interface Op<T> {
        T run() throws IOException;
    }

    private final StorageBackend inner;
    private final FdropMetrics metrics;

    public InstrumentedStorage(StorageBackend inner, FdropMetrics metrics) {
        this.inner = inner;
        this.metrics = metrics;
    }

    public StorageBackend inner() {
        return inner;
    }

    private <T> T timed(String op, Op<T> work) throws IOException {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            T result = work.run();
            ok = true;
            return result;
        } finally {
            metrics.storage(inner.type(), op, ok, System.nanoTime() - start);
        }
    }

    @Override
    public String type() {
        return inner.type();
    }

    @Override
    public void init(FdropConfig config) throws IOException {
        inner.init(config);
    }

    @Override
    public Optional<ObjectInfo> stat(StoragePath path) throws IOException {
        return timed("stat", () -> inner.stat(path));
    }

    @Override
    public Stream<ObjectInfo> list(StoragePath dir, boolean recursive) throws IOException {
        return timed("list", () -> inner.list(dir, recursive));
    }

    @Override
    public ReadableByteChannel openRead(StoragePath path, long offset, long length) throws IOException {
        ReadableByteChannel ch = timed("read", () -> inner.openRead(path, offset, length));
        return ch;
    }

    @Override
    public WriteHandle openWrite(StoragePath path, WriteOptions opts) throws IOException {
        WriteHandle h = timed("write", () -> inner.openWrite(path, opts));
        return new WriteHandle() {
            @Override
            public long written() {
                return h.written();
            }

            @Override
            public ObjectInfo commit() throws IOException {
                return timed("write", h::commit);
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
            public int write(ByteBuffer src) throws IOException {
                return h.write(src);
            }

            @Override
            public boolean isOpen() {
                return h.isOpen();
            }
        };
    }

    @Override
    public void delete(StoragePath path) throws IOException {
        timed("delete", () -> {
            inner.delete(path);
            return null;
        });
    }

    @Override
    public void move(StoragePath src, StoragePath dst) throws IOException {
        timed("move", () -> {
            inner.move(src, dst);
            return null;
        });
    }

    @Override
    public void mkdirs(StoragePath dir) throws IOException {
        timed("mkdirs", () -> {
            inner.mkdirs(dir);
            return null;
        });
    }

    @Override
    public MultipartUpload beginMultipart(StoragePath target, WriteOptions opts) throws IOException {
        return wrap(timed("multipart_begin", () -> inner.beginMultipart(target, opts)));
    }

    @Override
    public MultipartUpload resumeMultipart(StoragePath target, String uploadId) throws IOException {
        return wrap(timed("multipart_begin", () -> inner.resumeMultipart(target, uploadId)));
    }

    private MultipartUpload wrap(MultipartUpload m) {
        return new MultipartUpload() {
            @Override
            public String id() {
                return m.id();
            }

            @Override
            public StoragePath target() {
                return m.target();
            }

            @Override
            public PartInfo putPart(int partNumber, ReadableByteChannel src, long length, String sha256Hex)
                    throws IOException {
                return timed("multipart_part", () -> m.putPart(partNumber, src, length, sha256Hex));
            }

            @Override
            public List<PartInfo> listParts() throws IOException {
                return timed("list", m::listParts);
            }

            @Override
            public ObjectInfo complete(List<PartInfo> parts) throws IOException {
                return timed("multipart_complete", () -> m.complete(parts));
            }

            @Override
            public void abort() {
                m.abort();
            }
        };
    }

    @Override
    public long usage(StoragePath prefix) throws IOException {
        return timed("list", () -> inner.usage(prefix));
    }

    @Override
    public void close() throws IOException {
        inner.close();
    }
}
