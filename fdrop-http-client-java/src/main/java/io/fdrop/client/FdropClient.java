package io.fdrop.client;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * fdrop's REST API ({@code /api/v1}) for standard users and application accounts. Admin endpoints are not included.
 *
 * <p>{@link #upload(Path, String, UploadOptions) upload} looks like one request, but uses fdrop's resumable upload
 * protocol underneath: it creates an upload session, sends the file in the chunk size the server chooses (several
 * chunks at a time), retries failed chunks, and completes the session, which checks the whole file's SHA-256.
 *
 * <p>Failures that retrying can fix (network errors, timeouts, {@code 429}, {@code 5xx}) are retried with exponential
 * backoff, up to {@link Builder#maxAttempts(int) maxAttempts} tries per request (per chunk, for uploads); after that
 * the call throws {@link FdropRetriesExhaustedException}. Other errors throw {@link FdropApiException} at once.
 * Requests that are not safe to repeat ({@code mkdir}, {@code move}, creating folders) are retried only when they
 * never reached the server.
 *
 * <p>Instances are thread safe; build one per server and account and share it.
 *
 * <pre>{@code
 * try (FdropClient fdrop = FdropClient.builder()
 *         .baseUrl("https://fdrop.example.com")
 *         .bearerToken(() -> tokens.current())
 *         .maxAttempts(5)
 *         .build()) {
 *     FileEntry f = fdrop.upload(Path.of("report.pdf"), "/shared/Finance/report.pdf",
 *             UploadOptions.DEFAULTS.withExpiresIn("30 days"));
 * }
 * }</pre>
 */
public interface FdropClient extends AutoCloseable {

    static Builder builder() {
        return new Builder();
    }

    // ---- account ----

    /** The signed-in account. */
    Me me();

    /** The caller's upload history, newest first; {@code cursor} from the previous page or null. */
    UploadHistory uploadHistory(String cursor, int limit);

    // ---- files ----

    /** A directory's entries ({@code /} for the top level: Home, shared and forward folders). */
    Listing list(String path);

    /**
     * Uploads a local file to {@code remotePath} (a full path such as {@code /home/docs/a.pdf}, or a directory
     * ending in {@code /}, which keeps the local file name). Returns the stored file.
     *
     * @throws FdropApiException             refused: no permission, quota, file exists without overwrite, …
     * @throws FdropRetriesExhaustedException a request kept failing after {@code maxAttempts} tries
     */
    FileEntry upload(Path file, String remotePath, UploadOptions options);

    default FileEntry upload(Path file, String remotePath) {
        return upload(file, remotePath, UploadOptions.DEFAULTS);
    }

    /**
     * Uploads {@code size} bytes read from {@code in} (which this call does not close). Each chunk is held in memory
     * while it is sent, so it can be sent again after a failure; the server picks the chunk size (8 MiB by
     * default), and up to {@code parallelChunks} chunks are held at once.
     */
    FileEntry upload(InputStream in, long size, String remotePath, UploadOptions options);

    /** Downloads a file to {@code target}, replacing it; retried from the start on a failure worth retrying. */
    void download(String remotePath, Path target);

    /**
     * Opens a file for reading. The stream is not retried: a failure while reading is an {@link java.io.IOException}
     * from the stream. Close it when done.
     */
    InputStream openDownload(String remotePath);

    FileEntry mkdir(String path);

    /** Renames or moves a file or directory; the expiry moves with it. */
    FileEntry move(String from, String to);

    /** Deletes a file, or a directory ({@code recursive} for one that is not empty). */
    void delete(String path, boolean recursive);

    /** Sets a file's expiry relative to now, in fdrop's expiry syntax ({@code "30 days"}, {@code "never"}). */
    FileEntry setExpiry(String path, String expiresIn);

    /** Sets a file's expiry to an instant. */
    FileEntry setExpiryAt(String path, Instant expiresAt);

    /** Clears a file's own expiry, so the folder's rules apply. */
    FileEntry clearExpiry(String path);

    /** Searches names and contents of every file the caller can see; {@code cursor} from the previous page. */
    SearchResult search(String query, String cursor, int limit);

    // ---- folders ----

    List<Folder> folders();

    Folder folder(String id);

    Folder createSharedFolder(String name);

    /**
     * A forward folder sending each file to every one of {@code destinationIds}, chosen from
     * {@link #forwardDestinations()}; {@code keepDays} may be null.
     */
    Folder createForwardFolder(String name, List<String> destinationIds, Integer keepDays);

    /** A forward folder sending files to one destination; {@code keepDays} may be null. */
    default Folder createForwardFolder(String name, String destinationId, Integer keepDays) {
        return createForwardFolder(name, List.of(destinationId), keepDays);
    }

    /**
     * Changes a forward folder's destinations (owner only). Added ones get new files, and with {@code sendExisting}
     * also the files still in the folder; files already queued to a removed one are still sent to it.
     */
    Folder setForwardDestinations(String folderId, List<String> destinationIds, boolean sendExisting);

    void deleteFolder(String id);

    /** Adds a member or changes their permission (owner only). */
    Folder putMember(String folderId, String username, Permission permission);

    /** Removes a member (owner), or the caller themselves. */
    void removeMember(String folderId, String username);

    List<ForwardJob> forwardJobs(String folderId);

    void retryForwardJob(String folderId, String jobId);

    /** Stops sending one file to one destination whose job failed (owner only); {@code reason} is audited. */
    void cancelForwardJob(String folderId, String jobId, String reason);

    List<ForwardDestination> forwardDestinations();

    @Override
    void close();

    /** Settings for a client; {@link #baseUrl} and one way to authenticate are required. */
    final class Builder {
        java.net.URI baseUrl;
        java.util.function.Supplier<String> bearerToken;
        String basicUser;
        char[] basicPassword;
        javax.net.ssl.SSLContext sslContext;
        int maxAttempts = 5;
        java.time.Duration initialBackoff = java.time.Duration.ofSeconds(1);
        java.time.Duration maxBackoff = java.time.Duration.ofSeconds(60);
        java.time.Duration connectTimeout = java.time.Duration.ofSeconds(30);
        java.time.Duration requestTimeout = java.time.Duration.ofMinutes(5);
        int parallelChunks = 3;
        String userAgent = "fdrop-http-client-java";

        Builder() {
        }

        /** The server, e.g. {@code https://fdrop.example.com} (or the application mTLS port, {@code :8444}). */
        public Builder baseUrl(String url) {
            return baseUrl(java.net.URI.create(url));
        }

        public Builder baseUrl(java.net.URI url) {
            this.baseUrl = url;
            return this;
        }

        /**
         * An OAuth access token from Keycloak with the {@code fdrop-api} audience, asked for before every request so
         * the caller can refresh it.
         */
        public Builder bearerToken(java.util.function.Supplier<String> token) {
            this.bearerToken = token;
            return this;
        }

        /** An application account's username and password (HTTP Basic; HTTPS only). */
        public Builder basicAuth(String username, char[] password) {
            this.basicUser = username;
            this.basicPassword = password.clone();
            return this;
        }

        /**
         * The TLS context: the CA to trust, and for an application account's certificate login (on fdrop's mTLS
         * port) the client key and certificate. {@link #clientCertificate} builds one from PEM files.
         */
        public Builder sslContext(javax.net.ssl.SSLContext context) {
            this.sslContext = context;
            return this;
        }

        /**
         * Builds the TLS context from PEM files: the client certificate and its PKCS#8 key (both may be null when
         * only a CA is needed), and the CA that signed the server's certificate (null for the JDK's trust store).
         */
        public Builder clientCertificate(Path cert, Path key, Path ca) {
            this.sslContext = Pem.sslContext(cert, key, ca);
            return this;
        }

        /** Tries per request (per chunk, for uploads) before giving up; at least 1. Default 5. */
        public Builder maxAttempts(int attempts) {
            if (attempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
            this.maxAttempts = attempts;
            return this;
        }

        /** The first wait after a failure, doubling up to {@code max}, each with random jitter. Default 1 s to 60 s. */
        public Builder backoff(java.time.Duration initial, java.time.Duration max) {
            this.initialBackoff = initial;
            this.maxBackoff = max;
            return this;
        }

        public Builder connectTimeout(java.time.Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        /** How long one request may take, including a whole chunk. Default 5 minutes. */
        public Builder requestTimeout(java.time.Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        /** Chunks sent at once; capped by the server's {@code upload.maxParallelChunks}. Default 3. */
        public Builder parallelChunks(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("parallelChunks must be at least 1");
            }
            this.parallelChunks = n;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public FdropClient build() {
            return new HttpFdropClient(this, null);
        }
    }
}
