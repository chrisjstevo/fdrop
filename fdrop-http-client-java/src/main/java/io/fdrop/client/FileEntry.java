package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A file or directory in fdrop's virtual tree. {@code type} is {@code FILE}, or for a directory {@code DIR},
 * {@code AREA} (such as {@code /home}), {@code SHARED_FOLDER} or {@code FORWARD_FOLDER}; times are RFC 3339
 * strings as the server sends them ({@code null} when unknown). The expiry fields are only set for files.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileEntry(String name, String path, String type, long size, String modified, boolean writable,
                        String folderId, String expiresAt, String expiresIn, String expirySource) {

    public boolean isDirectory() {
        return !"FILE".equals(type);
    }
}
