package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** The signed-in account: who it is, its quota, and the server's upload settings. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Me(String sub, String username, String email, String kind, Map<String, Boolean> roles, long quotaBytes,
                 long usedBytes, long reservedBytes, Settings settings) {

    public boolean isApplication() {
        return "application".equals(kind);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Settings(long simpleUploadMax, long maxFileSize, int maxParallelChunks, long clientHashMax) {
    }
}
