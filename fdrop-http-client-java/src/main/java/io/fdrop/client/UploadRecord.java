package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One row of the caller's upload history ({@code /api/v1/me/uploads}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UploadRecord(String id, String fileName, long size, String wentTo, String folderId, String folderName,
                           String destinationName, String pathAtUpload, String currentPath, String completedAt,
                           String expiresAt, String via, String clientIp, String status, String jobId) {
}
