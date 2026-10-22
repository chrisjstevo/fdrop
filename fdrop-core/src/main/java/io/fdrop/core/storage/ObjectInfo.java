package io.fdrop.core.storage;

import java.time.Instant;

/** What a backend knows about one object or directory. */
public record ObjectInfo(StoragePath path, long size, Instant lastModified, String etag, String contentType,
                         boolean directory) {

    public static ObjectInfo directory(StoragePath path, Instant lastModified) {
        return new ObjectInfo(path, 0, lastModified, null, null, true);
    }

    public String name() {
        return path.name();
    }
}
