package io.fdrop.core.storage;

/**
 * Options for a write.
 *
 * @param contentType MIME type to record, or null
 * @param overwrite   whether an existing object at the target may be replaced
 * @param expectedSize the final size when known up front (lets S3 pick single PUT vs multipart), or -1
 */
public record WriteOptions(String contentType, boolean overwrite, long expectedSize) {

    public static final WriteOptions DEFAULT = new WriteOptions(null, false, -1);
    public static final WriteOptions OVERWRITE = new WriteOptions(null, true, -1);

    public WriteOptions withExpectedSize(long size) {
        return new WriteOptions(contentType, overwrite, size);
    }
}
