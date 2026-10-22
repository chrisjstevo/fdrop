package io.fdrop.core.storage;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.List;

/** A multipart upload: parts stored independently, then concatenated in part order by {@link #complete}. */
public interface MultipartUpload {

    /** Backend id that lets the upload be reopened after a restart (S3 upload id, local directory name). */
    String id();

    StoragePath target();

    /**
     * Stores one part. Reading stops after {@code length} bytes; fewer bytes, or a SHA-256 mismatch when
     * {@code sha256Hex} is given, fails the part and stores nothing.
     *
     * @param partNumber 1-based part number
     */
    PartInfo putPart(int partNumber, ReadableByteChannel src, long length, String sha256Hex) throws IOException;

    List<PartInfo> listParts() throws IOException;

    /** Concatenates {@code parts} in order into the target and returns the final object. */
    ObjectInfo complete(List<PartInfo> parts) throws IOException;

    void abort();
}
