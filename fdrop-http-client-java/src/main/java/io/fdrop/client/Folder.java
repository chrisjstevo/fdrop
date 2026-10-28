package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A shared or forward folder as the caller sees it. {@code type} is {@code SHARED} or {@code FORWARD}; {@code role}
 * the caller's own ({@code OWNER}, {@code WRITE} or {@code READ}); {@code createdAt} epoch milliseconds. A forward
 * folder sends each file to every one of its {@code destinations}; {@code destinationId} is the first of them and
 * {@code destinationName} all their names, comma-separated, as servers that allowed one destination sent them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Folder(String id, String type, String name, String path, String owner, String role, String destinationId,
                     String destinationName, Integer keepDays, boolean paused, List<Member> members, long createdAt,
                     String defaultExpiry, String maxExpiry, List<Destination> destinations) {

    /** One of a forward folder's destinations; {@code paused} when disabled or no longer assigned to the owner. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Destination(String id, String name, boolean paused) {
    }

    /** {@code kind} is {@code STANDARD} or {@code APPLICATION}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(String username, Permission permission, String kind) {
    }
}
