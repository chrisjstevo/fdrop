package io.fdrop.client;

/** Bytes the server has acknowledged so far; called from the upload's threads, so implementations must be thread safe. */
@FunctionalInterface
public interface UploadProgress {

    UploadProgress NONE = (acknowledged, total) -> {
    };

    void update(long acknowledged, long total);
}
