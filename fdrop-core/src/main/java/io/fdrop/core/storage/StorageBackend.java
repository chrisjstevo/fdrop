package io.fdrop.core.storage;

import io.fdrop.core.config.FdropConfig;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * The storage SPI every file byte goes through, for HTTP and SFTP alike. Implementations are found with
 * {@link ServiceLoader} and picked by {@code storage.type}. All methods block; callers run them on the storage
 * executor, never on a Netty event loop or SSHD NIO thread.
 */
public interface StorageBackend extends AutoCloseable {

    /** {@code "local"}, {@code "s3"}, ... */
    String type();

    void init(FdropConfig config) throws IOException;

    Optional<ObjectInfo> stat(StoragePath path) throws IOException;

    /** Lists a directory's direct children, or everything beneath it when {@code recursive}. */
    Stream<ObjectInfo> list(StoragePath dir, boolean recursive) throws IOException;

    /** Opens {@code length} bytes from {@code offset}; {@code length < 0} reads to the end. */
    ReadableByteChannel openRead(StoragePath path, long offset, long length) throws IOException;

    WriteHandle openWrite(StoragePath path, WriteOptions opts) throws IOException;

    /** Deletes a file, or a directory with everything beneath it. Missing paths are not an error. */
    void delete(StoragePath path) throws IOException;

    /** Moves a file or a directory tree within this backend. Fails if {@code dst} exists. */
    void move(StoragePath src, StoragePath dst) throws IOException;

    void mkdirs(StoragePath dir) throws IOException;

    MultipartUpload beginMultipart(StoragePath target, WriteOptions opts) throws IOException;

    /** Reopens a multipart upload started earlier, e.g. after a restart. */
    MultipartUpload resumeMultipart(StoragePath target, String uploadId) throws IOException;

    /** Total bytes stored under {@code prefix}. May be slow; used for quota repair, not per request. */
    default long usage(StoragePath prefix) throws IOException {
        try (Stream<ObjectInfo> s = list(prefix, true)) {
            return s.filter(o -> !o.directory()).mapToLong(ObjectInfo::size).sum();
        }
    }

    @Override
    void close() throws IOException;

    /** Finds the backend named by {@code storage.type} and initialises it. */
    static StorageBackend load(FdropConfig config) throws IOException {
        for (StorageBackend b : ServiceLoader.load(StorageBackend.class)) {
            if (b.type().equals(config.storage.type)) {
                b.init(config);
                return b;
            }
        }
        throw new IllegalStateException("no storage backend of type '" + config.storage.type
                + "' on the classpath");
    }
}
