package io.fdrop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One upload through fdrop's resumable protocol (see "Resumable chunked uploads" in spec/resumable-uploads.md), hidden behind
 * {@link FdropClient#upload}: create a session, read the source one chunk at a time in the server's chunk size, send
 * up to {@code parallelChunks} at once (each retried on its own), then complete with the whole file's SHA-256 so the
 * server checks every byte arrived. Any failure discards the session, so nothing partial is left behind.
 */
final class ChunkedUpload {

    /** How long to wait for the server to assemble a large file after {@code complete} answers 202. */
    private static final Duration ASSEMBLY_LIMIT = Duration.ofHours(1);
    /** {@code 409} on a chunk means another request for it is running or it differs; checked this many times. */
    private static final int MAX_CONFLICTS = 3;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Session(String id, long chunkSize, int chunkCount, String state, String error, FileEntry file) {
    }

    private final HttpFdropClient client;
    private final String target;
    private final long size;
    private final Long lastModified;
    private final UploadOptions options;
    private final AtomicLong acknowledged = new AtomicLong();

    ChunkedUpload(HttpFdropClient client, String target, long size, Long lastModified, UploadOptions options) {
        this.client = client;
        this.target = target;
        this.size = size;
        this.lastModified = lastModified;
        this.options = options == null ? UploadOptions.DEFAULTS : options;
    }

    FileEntry run(InputStream in) {
        Session s = create();
        try {
            String sha256 = sendAll(s, in);
            FileEntry done = complete(s, sha256);
            options.progress().update(size, size);
            return done;
        } catch (RuntimeException e) {
            abort(s);
            throw e;
        }
    }

    private String op(String what) {
        return what + " (uploading " + target + ")";
    }

    private Session create() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", target);
        body.put("size", size);
        if (lastModified != null) {
            body.put("lastModified", lastModified);
        }
        body.put("overwrite", options.overwrite());
        if (options.expiresIn() != null) {
            body.put("expiresIn", options.expiresIn());
        }
        // safe to repeat: a session created by a try whose answer was lost is never used and expires on the server
        String op = "starting the upload of " + target;
        HttpResponse<byte[]> r = client.send(op, true, () -> client.request("/api/v1/uploads")
                .header("Content-Type", "application/json").POST(HttpFdropClient.json(body)).build());
        return HttpFdropClient.expect(op, r, Session.class);
    }

    /** Reads and sends every chunk; returns the whole file's SHA-256, computed on the way. */
    private String sendAll(Session s, InputStream in) {
        MessageDigest whole = sha256();
        Semaphore slots = new Semaphore(client.parallelChunks);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        List<Future<?>> sent = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < s.chunkCount(); i++) {
                acquire(slots);
                if (failure.get() != null) {
                    slots.release();
                    break;
                }
                long offset = i * s.chunkSize();
                byte[] chunk = read(in, (int) Math.min(s.chunkSize(), size - offset), offset);
                whole.update(chunk);
                int index = i;
                sent.add(pool.submit(() -> {
                    try {
                        sendChunk(s, index, chunk);
                        options.progress().update(acknowledged.addAndGet(chunk.length), size);
                    } catch (RuntimeException e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        slots.release();
                    }
                }));
            }
            for (Future<?> f : sent) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    failure.compareAndSet(null, new FdropClientException(op("sending chunks"), e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pool.shutdownNow();
                    throw new FdropClientException(op("waiting for chunks") + ": interrupted", e);
                }
            }
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return HexFormat.of().formatHex(whole.digest());
    }

    private void acquire(Semaphore slots) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FdropClientException(op("sending chunks") + ": interrupted", e);
        }
    }

    private byte[] read(InputStream in, int length, long offset) {
        try {
            byte[] chunk = in.readNBytes(length);
            if (chunk.length < length) {
                throw new FdropClientException(op("reading the source") + ": it ended after " + (offset
                        + chunk.length) + " of the " + size + " bytes given as its size");
            }
            return chunk;
        } catch (IOException e) {
            throw new FdropClientException(op("reading the source") + ": " + e.getMessage(), e);
        }
    }

    private void sendChunk(Session s, int index, byte[] chunk) {
        String op = op("sending chunk " + (index + 1) + " of " + s.chunkCount());
        String sha = HexFormat.of().formatHex(sha256().digest(chunk));
        for (int conflicts = 0; ; conflicts++) {
            HttpResponse<byte[]> r = client.send(op, true, () -> client.request("/api/v1/uploads/" + s.id()
                            + "/chunks/" + index).header("Content-Type", "application/octet-stream")
                    .header("X-Chunk-SHA256", sha).PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build());
            int status = r.statusCode();
            if (status == 204 || status == 200) {
                return;
            }
            if (status == 404) {
                throw new FdropClientException(op + ": the server no longer has the upload session (it expired or"
                        + " was discarded)");
            }
            if (status == 409 && conflicts < MAX_CONFLICTS) {
                // another try of this chunk may have got through after all: if the server has it, it is done
                if (received(s, index)) {
                    return;
                }
                continue;
            }
            throw HttpFdropClient.problem(op, status, r.body());
        }
    }

    private boolean received(Session s, int index) {
        JsonNode st = status(s);
        for (JsonNode range : st.path("received")) {
            if (index >= range.get(0).asInt() && index <= range.get(1).asInt()) {
                return true;
            }
        }
        return false;
    }

    private JsonNode status(Session s) {
        String op = op("checking the upload");
        return HttpFdropClient.expect(op, client.send(op, true, () -> client.request("/api/v1/uploads/" + s.id())
                .GET().build()), JsonNode.class);
    }

    /** Asks the server to assemble the file, waiting while it does; complete is idempotent on the server. */
    private FileEntry complete(Session s, String sha256) {
        String op = op("completing the upload");
        HttpResponse<byte[]> r = client.send(op, true, () -> client.request("/api/v1/uploads/" + s.id()
                + "/complete").header("Content-Type", "application/json")
                .POST(HttpFdropClient.json(Map.of("sha256", sha256))).build());
        Session done = HttpFdropClient.expect(op, r, Session.class);
        long deadline = System.nanoTime() + ASSEMBLY_LIMIT.toNanos();
        Duration wait = Duration.ofMillis(250);
        while (!"COMPLETED".equals(done.state())) {
            if ("FAILED".equals(done.state())) {
                throw new FdropClientException(op + ": the server could not assemble the file: "
                        + (done.error() == null ? "unknown error" : done.error()));
            }
            if (System.nanoTime() > deadline) {
                throw new FdropClientException(op + ": the server was still assembling the file after "
                        + ASSEMBLY_LIMIT.toMinutes() + " minutes");
            }
            sleep(wait);
            wait = wait.multipliedBy(2).compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5)
                    : wait.multipliedBy(2);
            done = HttpFdropClient.JSON.convertValue(status(s), Session.class);
        }
        return done.file() != null ? done.file() : stat();
    }

    /** The stored file, when completion was reported by the status call (which carries no file entry). */
    private FileEntry stat() {
        int slash = target.lastIndexOf('/');
        String parent = slash <= 0 ? "/" : target.substring(0, slash);
        return client.list(parent).entries().stream().filter(e -> e.path().equals(target)).findFirst()
                .orElseThrow(() -> new FdropClientException(op("reading the uploaded file") + ": it is not in "
                        + parent));
    }

    private void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FdropClientException(op("waiting for assembly") + ": interrupted", e);
        }
    }

    /** One try to discard the session; if it fails, the server discards it when it expires. */
    private void abort(Session s) {
        try {
            client.transport.send(client.request("/api/v1/uploads/" + s.id()).DELETE().build());
        } catch (IOException | RuntimeException e) {
            // the server's janitor removes it
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
