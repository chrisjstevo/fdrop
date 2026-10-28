package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One row of the caller's upload history ({@code /api/v1/me/uploads}). A forward upload lists where it stands at
 * each of its {@code destinations}; {@code status} is the worst of them, and {@code SENT} once all are sent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UploadRecord(String id, String fileName, long size, String wentTo, String folderId, String folderName,
                           String destinationName, String pathAtUpload, String currentPath, String completedAt,
                           String expiresAt, String via, String clientIp, String status, String jobId,
                           List<Destination> destinations) {

    /** Where a forward upload stands at one destination; {@code state} is a {@link ForwardJob} state. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Destination(String jobId, String destinationId, String destinationName, String state, String error,
                              String sentAt) {
    }
}
