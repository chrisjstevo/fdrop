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
    public enum RecordStatus { STORED, MOVED, DELETED, QUEUED, SENDING, RETRY_WAIT, SENT, FAILED, PAUSED }

    public enum JobState { QUEUED, SENDING, RETRY_WAIT, SENT, FAILED, PAUSED }

    public enum ServerAuth { HOST_KEY, HOST_CA }

    public enum KeyState { ACTIVE, PENDING, RETIRED }
}
