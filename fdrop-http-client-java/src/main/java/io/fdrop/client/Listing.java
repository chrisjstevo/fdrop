package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** A directory's contents, and what an upload into it gets for expiry ({@code folderExpiry}, null for files). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Listing(String path, FileEntry self, List<FileEntry> entries, FolderExpiry folderExpiry) {

    /** The expiry rules of a directory: {@code default} and {@code max} are canonical expiries or null. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FolderExpiry(boolean enabled, boolean allowed,
                               @com.fasterxml.jackson.annotation.JsonProperty("default") String defaultExpiry,
                               String max, String serverMax) {
    }
}
