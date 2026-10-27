package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A shared or forward folder as the caller sees it. {@code type} is {@code SHARED} or {@code FORWARD}; {@code role}
 * the caller's own ({@code OWNER}, {@code WRITE} or {@code READ}); {@code createdAt} epoch milliseconds.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Folder(String id, String type, String name, String path, String owner, String role, String destinationId,
                     String destinationName, Integer keepDays, boolean paused, List<Member> members, long createdAt,
                     String defaultExpiry, String maxExpiry) {

    /** {@code kind} is {@code STANDARD} or {@code APPLICATION}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(String username, Permission permission, String kind) {
    }
}
