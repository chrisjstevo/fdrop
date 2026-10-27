package io.fdrop.client;

import java.util.Objects;

/**
 * How to upload: replace an existing file ({@code overwrite}), the file's expiry in fdrop's expiry syntax
 * ({@code "30 days"}, {@code "never"}; null for the folder's default), and a progress callback.
 */
public record UploadOptions(boolean overwrite, String expiresIn, UploadProgress progress) {

    public static final UploadOptions DEFAULTS = new UploadOptions(false, null, UploadProgress.NONE);

    public UploadOptions {
        progress = Objects.requireNonNullElse(progress, UploadProgress.NONE);
    }

    public UploadOptions withOverwrite(boolean overwrite) {
        return new UploadOptions(overwrite, expiresIn, progress);
    }

    public UploadOptions withExpiresIn(String expiresIn) {
        return new UploadOptions(overwrite, expiresIn, progress);
    }

    public UploadOptions withProgress(UploadProgress progress) {
        return new UploadOptions(overwrite, expiresIn, progress);
    }
}
