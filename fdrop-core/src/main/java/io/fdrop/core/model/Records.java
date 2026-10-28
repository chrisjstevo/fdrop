package io.fdrop.core.model;

import io.fdrop.core.model.Enums.AppMethod;
import io.fdrop.core.model.Enums.CertMatch;
import io.fdrop.core.model.Enums.CertState;
import io.fdrop.core.model.Enums.ExpirySource;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.KeyState;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.RecordStatus;
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.model.Enums.UploadState;
import io.fdrop.core.model.Enums.UserKind;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Enums.WentTo;

import java.util.List;
import java.util.Set;

/** Rows of the metadata DB, one record type per table. */
public final class Records {

    private Records() {
    }

    public record User(String sub, String username, String email, long quotaBytes, long usedBytes, long createdAt,
                       Long lastLoginAt, UserKind kind) {
    }

    /**
     * An application account's fdrop settings; Keycloak holds its username, password and enabled flag.
     *
     * @param allowedSources CIDRs logins may come from; empty means anywhere
     */
    public record AppAccount(String sub, String description, String responsibleSub, Set<AppMethod> allowedMethods,
                             List<String> allowedSources, Long passwordSetAt, String createdBy,
                             long createdAt, Long lastLoginAt) {
    }

    /**
     * A client certificate registered on an application account. PINNED matches {@code sha256} (of the DER
     * certificate); SUBJECT matches {@code subject} (an RFC 2253 DN) or {@code san} ({@code DNS:}, {@code URI:} or
     * {@code EMAIL:} prefixed) on a certificate that chains to a trusted CA.
     */
    public record AppClientCert(String id, String sub, CertMatch match, String sha256, String subject, String san,
                                Long notAfter, CertState state, long createdAt) {
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

    /** A forward folder's settings; its destinations are in {@code folder_destination}. */
    public record FolderForward(String folderId, int keepDays) {
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
                                long expiresAt, String expiresIn) {
    }

    public record UploadChunk(String sessionId, int idx, long size, String sha256, String etag, long receivedAt) {
    }

    public record UploadRecord(String id, String userSub, String fileName, long size, WentTo wentTo,
                               String folderId, String pathAtUpload, String currentPath, String storageKey,
                               RecordStatus status, Via via, String clientIp, String startIp, long completedAt,
                               Long expiresAt) {
    }

    /**
     * A file's expiry. {@code userSub} is the home's owner for files under {@code /home}, {@code folderId} the
     * managed folder otherwise; {@code expiresIn} is the canonical text it was set with, or null for a date.
     */
    public record FileExpiry(String id, String storageKey, String parentKey, String userSub, String folderId,
                             long expiresAt, String expiresIn, ExpirySource source, String setBySub, Via setVia,
                             long setAt, String uploadRecordId, int attempts, String lastError,
                             long nextAttemptAt) {
    }

    public record ForwardJob(String id, String folderId, String destinationId, String uploadRecordId,
                             String storageKey, String fileName, long size, JobState state, int attempts,
                             String lastError, long nextAttemptAt, Long firstFailedAt, long createdAt,
                             long updatedAt, Long sentAt, String cancelledBy, String cancelReason) {

        /** Sent, or given up on by the owner: nothing more will happen to this job. */
        public boolean finished() {
            return state == JobState.SENT || state == JobState.CANCELLED;
        }
    }
}
