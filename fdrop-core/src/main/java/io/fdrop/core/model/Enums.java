package io.fdrop.core.model;

/** The closed sets of values stored in the metadata DB. */
public final class Enums {

    private Enums() {
    }

    public enum FolderType { SHARED, FORWARD }

    public enum Permission { READ, WRITE }

    /** Where an upload landed. */
    public enum WentTo { HOME, SHARED, FORWARD }

    public enum Via { HTTP, SFTP }

    public enum UploadState { OPEN, ASSEMBLING, COMPLETED, ABORTED, EXPIRED, FAILED }

    /**
     * Status of an {@code upload_record}. Folder uploads use STORED, MOVED, DELETED; forward uploads mirror their
     * forward job's state.
     */
    public enum RecordStatus { STORED, MOVED, DELETED, EXPIRED, QUEUED, SENDING, RETRY_WAIT, SENT, FAILED, PAUSED, CANCELLED }

    public enum JobState { QUEUED, SENDING, RETRY_WAIT, SENT, FAILED, PAUSED, CANCELLED }

    /** A remote file being copied into fdrop from a forward destination. */
    public enum RetrievalState { QUEUED, RETRIEVING, READY, FAILED }

    public enum ServerAuth { HOST_KEY, HOST_CA }

    public enum KeyState { ACTIVE, PENDING, RETIRED }

    /** A person, or an application account for another system. */
    public enum UserKind { USER, APPLICATION }

    /** How an application account may sign in. */
    public enum AppMethod { MTLS, PASSWORD, PUBLICKEY }

    /** How a registered client certificate is matched: its fingerprint, or its subject or SAN from a trusted CA. */
    public enum CertMatch { PINNED, SUBJECT }

    public enum CertState { ACTIVE, RETIRED }

    /** Where a file's expiry came from. */
    public enum ExpirySource { UPLOAD, FOLDER_DEFAULT, HOME_DEFAULT, CHANGED, FORWARD_KEEP }
}
