package io.fdrop.core.model;

import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.KeyState;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.RecordStatus;
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.model.Enums.UploadState;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Enums.WentTo;

/** Rows of the metadata DB, one record type per table. */
public final class Records {

    private Records() {
    }

    public record User(String sub, String username, String email, long quotaBytes, long usedBytes, long createdAt,
                       Long lastLoginAt) {
    }

    public record SshKey(String id, String userSub, String name, String keyType, String publicKey,
                         String fingerprint, long createdAt, Long lastUsedAt) {
    }

    public record Folder(String id, FolderType type, String name, String ownerSub, long createdAt) {
        public String storagePrefix() {
            return "folders/" + id;
        }
    }

    public record FolderMember(String folderId, String userSub, Permission permission) {
    }

    public record FolderForward(String folderId, String destinationId, int keepDays) {
    }

    public record ForwardDestination(String id, String name, String description, String host, int port,
                                     String remoteDir, String username, ServerAuth serverAuth,
                                     String hostKeySha256, String hostCaPublicKey, boolean overwrite,
                                     boolean enabled, String createdBy, long createdAt, long updatedAt) {
    }

    public record ForwardClientKey(String id, String destinationId, String publicKey, byte[] privateKeyEnc,
                                   String userCert, KeyState state, long createdAt) {
    }

    public record UploadSession(String id, String userSub, String virtualPath, String storageKey, String folderId,
                                WentTo wentTo, String fileName, long size, long chunkSize, int chunkCount,
                                String sha256, UploadState state, String backendUploadId, boolean overwrite,
                                String startIp, Long lastModified, String error, long createdAt, long updatedAt,
                                long expiresAt) {
    }

    public record UploadChunk(String sessionId, int idx, long size, String sha256, String etag, long receivedAt) {
    }

    public record UploadRecord(String id, String userSub, String fileName, long size, WentTo wentTo,
                               String folderId, String pathAtUpload, String currentPath, String storageKey,
                               RecordStatus status, Via via, String clientIp, String startIp, long completedAt) {
    }

    public record ForwardJob(String id, String folderId, String destinationId, String uploadRecordId,
                             String storageKey, String fileName, long size, JobState state, int attempts,
                             String lastError, long nextAttemptAt, Long firstFailedAt, long createdAt,
                             long updatedAt, Long sentAt) {
    }
}
