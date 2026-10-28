package io.fdrop.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** {@link FdropClient} over the JDK's {@link HttpClient}. */
final class HttpFdropClient implements FdropClient {

    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final URI base;
    private final HttpClient http;
    /** The HTTP client's own transport, and the one requests go through (the same, except in tests). */
    final Transport real;
    final Transport transport;
    final Retrier retrier;
    private final Supplier<String> authorization;
    private final java.time.Duration requestTimeout;
    private final String userAgent;
    final int parallelChunks;

    /** {@code transport} replaces the HTTP client's own, for tests; null in normal use. */
    HttpFdropClient(Builder b, Transport transport) {
        this(b, transport, Retrier.Sleeper.SYSTEM);
    }

    HttpFdropClient(Builder b, Transport transport, Retrier.Sleeper sleeper) {
        if (b.baseUrl == null) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        String scheme = b.baseUrl.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("baseUrl must be https://: fdrop serves HTTPS only");
        }
        this.base = URI.create(b.baseUrl.toString().replaceAll("/+$", ""));
        if (b.bearerToken != null && b.basicUser != null) {
            throw new IllegalArgumentException("give a bearer token or a username and password, not both");
        }
        if (b.basicUser != null) {
            String pair = b.basicUser + ":" + new String(b.basicPassword);
            String value = "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
            this.authorization = () -> value;
        } else if (b.bearerToken != null) {
            Supplier<String> token = b.bearerToken;
            this.authorization = () -> "Bearer " + token.get();
        } else {
            // a client certificate alone, on fdrop's mTLS port
            this.authorization = null;
        }
        HttpClient.Builder hb = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(b.connectTimeout).followRedirects(HttpClient.Redirect.NEVER);
        if (b.sslContext != null) {
            hb.sslContext(b.sslContext);
        }
        this.http = hb.build();
        this.real = Transport.of(http);
        this.transport = transport != null ? transport : real;
        this.retrier = new Retrier(b.maxAttempts, b.initialBackoff, b.maxBackoff, sleeper);
        this.requestTimeout = b.requestTimeout;
        this.userAgent = b.userAgent;
        this.parallelChunks = b.parallelChunks;
    }

    // ---- requests ----

    HttpRequest.Builder request(String pathAndQuery) {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(requestTimeout)
                .header("User-Agent", userAgent).header("Accept", "application/json");
        if (authorization != null) {
            r.header("Authorization", authorization.get());
        }
        return r;
    }

    static String q(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static HttpRequest.BodyPublisher json(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sends with retries; the request is rebuilt for every try, so tokens are fresh. */
    HttpResponse<byte[]> send(String operation, boolean safeToRepeat, Supplier<HttpRequest> request) {
        return retrier.run(operation, safeToRepeat, () -> transport.send(request.get()));
    }

    /** The body as {@code type} on a 2xx answer, otherwise the problem as an exception. */
    static <T> T expect(String operation, HttpResponse<byte[]> r, Class<T> type) {
        check(operation, r);
        if (type == Void.class) {
            return null;
        }
        try {
            return JSON.readValue(r.body(), type);
        } catch (IOException e) {
            throw new FdropClientException(operation + ": unexpected response: " + e.getMessage(), e);
        }
    }

    static <T> T expect(String operation, HttpResponse<byte[]> r, TypeReference<T> type) {
        check(operation, r);
        try {
            return JSON.readValue(r.body(), type);
        } catch (IOException e) {
            throw new FdropClientException(operation + ": unexpected response: " + e.getMessage(), e);
        }
    }

    static void check(String operation, HttpResponse<byte[]> r) {
        int s = r.statusCode();
        if (s >= 200 && s < 300) {
            return;
        }
        throw problem(operation, s, r.body());
    }

    /** fdrop's RFC 9457 problem body, or just the status. */
    static FdropApiException problem(String operation, int status, byte[] body) {
        String type = null;
        String title = null;
        String detail = null;
        try {
            JsonNode n = body == null || body.length == 0 ? null : JSON.readTree(body);
            if (n != null && n.isObject()) {
                type = text(n, "type");
                title = text(n, "title");
                detail = text(n, "detail");
            }
        } catch (IOException ignored) {
            // not JSON: the status says enough
        }
        return new FdropApiException(operation, status, type, title, detail);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private <T> T get(String operation, String pathAndQuery, Class<T> type) {
        return expect(operation, send(operation, true, () -> request(pathAndQuery).GET().build()), type);
    }

    private <T> T get(String operation, String pathAndQuery, TypeReference<T> type) {
        return expect(operation, send(operation, true, () -> request(pathAndQuery).GET().build()), type);
    }

    private <T> T call(String operation, String method, String pathAndQuery, Object body, boolean safeToRepeat,
                       Class<T> type) {
        HttpResponse<byte[]> r = send(operation, safeToRepeat, () -> {
            HttpRequest.Builder b = request(pathAndQuery);
            if (body == null) {
                return b.method(method, HttpRequest.BodyPublishers.noBody()).build();
            }
            return b.header("Content-Type", "application/json").method(method, json(body)).build();
        });
        return expect(operation, r, type);
    }

    // ---- account ----

    @Override
    public Me me() {
        return get("reading the account", "/api/v1/me", Me.class);
    }

    @Override
    public UploadHistory uploadHistory(String cursor, int limit) {
        return get("reading the upload history", "/api/v1/me/uploads?limit=" + limit
                + (cursor == null ? "" : "&cursor=" + q(cursor)), UploadHistory.class);
    }

    // ---- files ----

    @Override
    public Listing list(String path) {
        return get("listing " + path, "/api/v1/files?path=" + q(path), Listing.class);
    }

    @Override
    public FileEntry upload(Path file, String remotePath, UploadOptions options) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new FdropClientException("cannot read " + file + ": not a readable file");
        }
        String target = remotePath.endsWith("/") ? remotePath + file.getFileName() : remotePath;
        long size;
        long modified;
        try {
            size = Files.size(file);
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new FdropClientException("cannot read " + file + ": " + e.getMessage(), e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            return new ChunkedUpload(this, target, size, modified, options).run(in);
        } catch (IOException e) {
            throw new FdropClientException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    @Override
    public FileEntry upload(InputStream in, long size, String remotePath, UploadOptions options) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be known: fdrop reserves quota for it up front");
        }
        if (remotePath.endsWith("/")) {
            throw new IllegalArgumentException("give the full remote path, including the file name");
        }
        return new ChunkedUpload(this, remotePath, size, null, options).run(in);
    }

    @Override
    public void download(String remotePath, Path target) {
        String op = "downloading " + remotePath;
        Path dir = target.toAbsolutePath().getParent();
        // a failure part-way through is an IOException from the copy, so the whole download is tried again
        HttpResponse<InputStream> r = retrier.run(op, true, () -> {
            HttpResponse<InputStream> resp = transport.stream(request("/api/v1/files/content?path="
                    + q(remotePath)).header("Accept", "application/octet-stream").GET().build());
            if (resp.statusCode() == 200) {
                Path tmp = Files.createTempFile(dir, ".fdrop-download-", ".part");
                try (InputStream body = resp.body()) {
                    Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            return resp;
        });
        if (r.statusCode() != 200) {
            throw problem(op, r.statusCode(), drain(r.body()));
        }
    }

    private static byte[] drain(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @Override
    public InputStream openDownload(String remotePath) {
        String op = "downloading " + remotePath;
        HttpResponse<InputStream> r = retrier.run(op, true, () -> transport.stream(
                request("/api/v1/files/content?path=" + q(remotePath)).header("Accept", "application/octet-stream")
                        .GET().build()));
        if (r.statusCode() != 200) {
            throw problem(op, r.statusCode(), drain(r.body()));
        }
        return r.body();
    }

    @Override
    public FileEntry mkdir(String path) {
        return call("creating " + path, "POST", "/api/v1/files/mkdir", Map.of("path", path), false, FileEntry.class);
    }

    @Override
    public FileEntry move(String from, String to) {
        return call("moving " + from + " to " + to, "POST", "/api/v1/files/move", Map.of("from", from, "to", to),
                false, FileEntry.class);
    }

    @Override
    public void delete(String path, boolean recursive) {
        String op = "deleting " + path;
        check(op, send(op, true, () -> request("/api/v1/files?path=" + q(path)
                + (recursive ? "&recursive=true" : "")).DELETE().build()));
    }

    @Override
    public FileEntry setExpiry(String path, String expiresIn) {
        return call("setting the expiry of " + path, "PATCH", "/api/v1/files?path=" + q(path),
                Map.of("expiresIn", expiresIn), true, FileEntry.class);
    }

    @Override
    public FileEntry setExpiryAt(String path, Instant expiresAt) {
        return call("setting the expiry of " + path, "PATCH", "/api/v1/files?path=" + q(path),
                Map.of("expiresAt", expiresAt.toString()), true, FileEntry.class);
    }

    @Override
    public FileEntry clearExpiry(String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expiresIn", null);
        return call("clearing the expiry of " + path, "PATCH", "/api/v1/files?path=" + q(path), body, true,
                FileEntry.class);
    }

    @Override
    public SearchResult search(String query, String cursor, int limit) {
        return get("searching", "/api/v1/search?q=" + q(query) + "&limit=" + limit
                + (cursor == null ? "" : "&cursor=" + q(cursor)), SearchResult.class);
    }

    // ---- folders ----

    @Override
    public List<Folder> folders() {
        return get("listing folders", "/api/v1/folders", new TypeReference<>() {
        });
    }

    @Override
    public Folder folder(String id) {
        return get("reading folder " + id, "/api/v1/folders/" + q(id), Folder.class);
    }

    @Override
    public Folder createSharedFolder(String name) {
        return call("creating folder " + name, "POST", "/api/v1/folders", Map.of("type", "shared", "name", name),
                false, Folder.class);
    }

    @Override
    public Folder createForwardFolder(String name, List<String> destinationIds, Integer keepDays) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "forward");
        body.put("name", name);
        body.put("destinationIds", destinationIds);
        if (keepDays != null) {
            body.put("keepDays", keepDays);
        }
        return call("creating folder " + name, "POST", "/api/v1/folders", body, false, Folder.class);
    }

    @Override
    public Folder setForwardDestinations(String folderId, List<String> destinationIds, boolean sendExisting) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destinationIds", destinationIds);
        body.put("sendExisting", sendExisting);
        return call("changing the destinations of folder " + folderId, "PATCH", "/api/v1/folders/" + q(folderId),
                body, true, Folder.class);
    }

    @Override
    public void deleteFolder(String id) {
        call("deleting folder " + id, "DELETE", "/api/v1/folders/" + q(id), null, true, Void.class);
    }

    @Override
    public Folder putMember(String folderId, String username, Permission permission) {
        return call("adding " + username + " to folder " + folderId, "PUT", "/api/v1/folders/" + q(folderId)
                + "/members/" + q(username), Map.of("permission", permission.name()), true, Folder.class);
    }

    @Override
    public void removeMember(String folderId, String username) {
        call("removing " + username + " from folder " + folderId, "DELETE", "/api/v1/folders/" + q(folderId)
                + "/members/" + q(username), null, true, Void.class);
    }

    @Override
    public List<ForwardJob> forwardJobs(String folderId) {
        return get("reading forward jobs", "/api/v1/folders/" + q(folderId) + "/forward-jobs",
                new TypeReference<>() {
                });
    }

    @Override
    public void retryForwardJob(String folderId, String jobId) {
        call("retrying forward job " + jobId, "POST", "/api/v1/folders/" + q(folderId) + "/forward-jobs/" + q(jobId)
                + "/retry", null, true, Void.class);
    }

    @Override
    public void cancelForwardJob(String folderId, String jobId, String reason) {
        // not repeated: a second cancel of the same job is refused, which would hide that the first worked
        call("stopping forward job " + jobId, "POST", "/api/v1/folders/" + q(folderId) + "/forward-jobs/" + q(jobId)
                + "/cancel", Map.of("reason", reason), false, Void.class);
    }

    @Override
    public List<ForwardDestination> forwardDestinations() {
        return get("listing forward destinations", "/api/v1/forward-destinations", new TypeReference<>() {
        });
    }

    @Override
    public void close() {
        http.close();
    }
}
