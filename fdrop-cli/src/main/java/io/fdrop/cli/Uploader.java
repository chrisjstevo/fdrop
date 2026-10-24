package io.fdrop.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One file through fdrop's resumable upload protocol: hash the file, find or create the server session, send the
 * chunks the server does not have yet (a few at a time), retry network errors with backoff, then complete. The
 * session id is saved locally so a later run resumes instead of starting again.
 */
class Uploader {

    /** Bytes the server has acknowledged, for the progress display. */
    interface Progress {
        void update(long acknowledged, long total);

        Progress NONE = (a, t) -> {
        };
    }

    record Options(Path file, String remotePath, boolean overwrite, int parallel, Duration retryFor, boolean resume,
                   String certFingerprint) {
    }

    /** What was uploaded; {@code resumed} when an earlier run's session was continued. */
    record Result(String path, long size, String sha256, String uploadId, boolean resumed, JsonNode file) {
    }

    private record Status(String state, NavigableSet<Integer> received) {
    }

    /** The server no longer knows the session (expired or aborted): start a new one. */
    private static final class SessionGone extends Exception {
        SessionGone() {
            super(null, null, false, false);
        }
    }

    private static final int MAX_CONFLICTS = 3;

    private final Api api;
    private final SessionStore store;
    private final Backoff.Clock clock;
    private final Random random;
    private final Progress progress;

    Uploader(Api api, SessionStore store, Backoff.Clock clock, Random random, Progress progress) {
        this.api = api;
        this.store = store;
        this.clock = clock;
        this.random = random;
        this.progress = progress;
    }

    Result upload(Options o) throws InterruptedException {
        Path file = o.file();
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new CliException(CliException.USAGE, "cannot read " + file + ": not a readable file");
        }
        String target = target(o.remotePath(), file);
        long size;
        long lastModified;
        try {
            size = Files.size(file);
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, "cannot read " + file + ": " + e.getMessage());
        }
        String sha256 = hash(file, 0, size);
        String key = SessionStore.key(api.base().toString(), o.certFingerprint(), target, size, lastModified, sha256);
        Backoff backoff = new Backoff(clock, o.retryFor(), random);

        SessionStore.Saved session = o.resume() ? store.load(key).orElse(null) : null;
        NavigableSet<Integer> received = new ConcurrentSkipListSet<>();
        boolean resumed = false;
        if (session != null) {
            Status st = statusOrNull(session.id(), backoff);
            if (st != null && st.state().equals("OPEN")) {
                received.addAll(st.received());
                resumed = true;
            } else {
                store.delete(key);
                session = null;
            }
        }
        Map<Integer, Integer> conflicts = new ConcurrentHashMap<>();
        while (true) {
            if (session == null) {
                session = create(target, size, lastModified, sha256, o.overwrite(), backoff);
                store.save(key, session, target, file);
                received.clear();
            }
            try {
                sendMissing(o, session, size, received, conflicts, backoff);
                JsonNode done = complete(session, sha256, received, backoff);
                if (done != null) {
                    store.delete(key);
                    progress.update(size, size);
                    return new Result(target, size, sha256, session.id(), resumed, done.path("file"));
                }
            } catch (SessionGone e) {
                store.delete(key);
                session = null;
                resumed = false;
            }
        }
    }

    /** {@code /dir/} keeps the local file name; anything else is the full target path. */
    static String target(String remote, Path file) {
        if (remote == null || !remote.startsWith("/")) {
            throw new CliException(CliException.USAGE, "the remote path must start with /, e.g. /home/ or"
                    + " /shared/<folder>/");
        }
        return remote.endsWith("/") ? remote + file.getFileName() : remote;
    }

    private SessionStore.Saved create(String target, long size, long lastModified, String sha256, boolean overwrite,
                                      Backoff backoff) throws InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", target);
        body.put("size", size);
        body.put("lastModified", lastModified);
        body.put("sha256", sha256);
        body.put("overwrite", overwrite);
        Api.Reply r = retrying(() -> api.call("POST", "/api/v1/uploads", body), backoff);
        switch (r.status()) {
            case 409 -> throw new CliException(CliException.EXISTS, target + " already exists; use --overwrite to"
                    + " replace it");
            case 404 -> throw new CliException(CliException.NOT_ALLOWED, "cannot upload to " + target + ": "
                    + r.detail());
            case 400 -> throw new CliException(CliException.USAGE, "cannot upload to " + target + ": " + r.detail());
            default -> {
            }
        }
        JsonNode n = Api.expect(r, "creating the upload");
        return new SessionStore.Saved(n.path("id").asText(), n.path("chunkSize").asLong(), n.path("chunkCount").asInt());
    }

    private Status statusOrNull(String id, Backoff backoff) throws InterruptedException {
        Api.Reply r = retrying(() -> api.call("GET", "/api/v1/uploads/" + id, null), backoff);
        if (r.status() == 404) {
            return null;
        }
        JsonNode n = Api.expect(r, "checking the upload");
        NavigableSet<Integer> got = new ConcurrentSkipListSet<>();
        for (JsonNode range : n.path("received")) {
            for (int i = range.get(0).asInt(); i <= range.get(1).asInt(); i++) {
                got.add(i);
            }
        }
        return new Status(n.path("state").asText(), got);
    }

    private Status status(String id, Backoff backoff) throws InterruptedException, SessionGone {
        Status st = statusOrNull(id, backoff);
        if (st == null) {
            throw new SessionGone();
        }
        return st;
    }

    /**
     * Sends every chunk the server lacks, {@code parallel} at a time. After a failed round it waits, asks the server
     * what it holds, and sends the rest; it gives up when nothing has got through for {@code --retry-for}.
     */
    private void sendMissing(Options o, SessionStore.Saved s, long size, NavigableSet<Integer> received,
                             Map<Integer, Integer> conflicts, Backoff backoff) throws InterruptedException, SessionGone {
        while (true) {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < s.chunkCount(); i++) {
                if (!received.contains(i)) {
                    missing.add(i);
                }
            }
            report(s, size, received);
            if (missing.isEmpty()) {
                return;
            }
            int before = received.size();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicReference<RuntimeException> fatal = new AtomicReference<>();
            AtomicBoolean gone = new AtomicBoolean();
            Semaphore slots = new Semaphore(o.parallel());
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> tasks = new ArrayList<>();
                for (int index : missing) {
                    slots.acquire();
                    if (fatal.get() != null || gone.get() || failure.get() != null) {
                        slots.release();
                        break;
                    }
                    tasks.add(pool.submit(() -> {
                        try {
                            sendChunk(o.file(), s, size, index, received, conflicts, failure, gone);
                            report(s, size, received);
                        } catch (RuntimeException e) {
                            fatal.compareAndSet(null, e);
                        } finally {
                            slots.release();
                        }
                    }));
                }
                for (Future<?> t : tasks) {
                    try {
                        t.get();
                    } catch (ExecutionException e) {
                        fatal.compareAndSet(null, new IllegalStateException(e.getCause()));
                    }
                }
            }
            if (fatal.get() != null) {
                throw fatal.get();
            }
            if (gone.get()) {
                throw new SessionGone();
            }
            // the deadline counts from the last time anything got through
            if (received.size() > before) {
                backoff.reset();
            }
            if (failure.get() == null) {
                continue;
            }
            if (!backoff.pause()) {
                throw giveUp(failure.get());
            }
            received.clear();
            received.addAll(status(s.id(), backoff).received());
        }
    }

    private void sendChunk(Path file, SessionStore.Saved s, long size, int index, NavigableSet<Integer> received,
                           Map<Integer, Integer> conflicts, AtomicReference<String> failure,
                           AtomicBoolean gone) {
        long offset = index * s.chunkSize();
        long length = Math.max(0, Math.min(s.chunkSize(), size - offset));
        String sha = hash(file, offset, length);
        Api.Reply r;
        try {
            r = api.putChunk(s.id(), index, file, offset, length, sha);
        } catch (Api.Retryable e) {
            failure.compareAndSet(null, "chunk " + index + ": " + e.getMessage());
            return;
        }
        switch (r.status()) {
            case 204, 200 -> received.add(index);
            case 409 -> {
                // another request has it, or it was stored differently: check with the server, a few times at most
                if (conflicts.merge(index, 1, Integer::sum) > MAX_CONFLICTS) {
                    throw new CliException(CliException.SERVER, "chunk " + index + " keeps conflicting: "
                            + r.detail());
                }
                failure.compareAndSet(null, "chunk " + index + ": " + r.detail());
            }
            case 404 -> gone.set(true);
            case 400 -> throw new CliException(CliException.SERVER, "the server rejected chunk " + index + " ("
                    + r.detail() + "); did the file change while uploading?");
            default -> Api.expect(r, "sending chunk " + index);
        }
    }

    /**
     * Asks the server to assemble the file and waits for it. Returns the final status JSON, or null when chunks
     * turned out to be missing and must be sent first.
     */
    private JsonNode complete(SessionStore.Saved s, String sha256, NavigableSet<Integer> received, Backoff backoff)
            throws InterruptedException, SessionGone {
        Api.Reply r = retrying(() -> api.call("POST", "/api/v1/uploads/" + s.id() + "/complete",
                Map.of("sha256", sha256)), backoff);
        if (r.status() == 404) {
            throw new SessionGone();
        }
        if (r.status() == 409) {
            received.clear();
            received.addAll(status(s.id(), backoff).received());
            return null;
        }
        if (r.status() == 400) {
            abort(s);
            throw new CliException(CliException.SERVER, "the server could not assemble the file: " + r.detail());
        }
        JsonNode n = Api.expect(r, "completing the upload");
        Duration wait = Duration.ofMillis(500);
        while (!"COMPLETED".equals(n.path("state").asText())) {
            if ("FAILED".equals(n.path("state").asText())) {
                abort(s);
                throw new CliException(CliException.SERVER, "assembly failed: " + n.path("error").asText("unknown"));
            }
            clock.sleep(wait);
            wait = wait.multipliedBy(2).compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5)
                    : wait.multipliedBy(2);
            n = Api.expect(retrying(() -> api.call("GET", "/api/v1/uploads/" + s.id(), null), backoff),
                    "checking the upload");
        }
        return n;
    }

    private void abort(SessionStore.Saved s) {
        try {
            api.call("DELETE", "/api/v1/uploads/" + s.id(), null);
        } catch (Api.Retryable | CliException e) {
            // the server discards it on expiry anyway
        }
    }

    private interface Call {
        Api.Reply run() throws Api.Retryable;
    }

    private Api.Reply retrying(Call call, Backoff backoff) throws InterruptedException {
        while (true) {
            try {
                return call.run();
            } catch (Api.Retryable e) {
                if (!backoff.pause()) {
                    throw giveUp(e.getMessage());
                }
            }
        }
    }

    private static CliException giveUp(String last) {
        return new CliException(CliException.GAVE_UP, "gave up: " + last + ". The upload session is kept; run the"
                + " same command again to resume.");
    }

    private void report(SessionStore.Saved s, long size, NavigableSet<Integer> received) {
        long bytes = 0;
        for (int i : received) {
            bytes += Math.max(0, Math.min(s.chunkSize(), size - i * s.chunkSize()));
        }
        progress.update(bytes, size);
    }

    static String hash(Path file, long offset, long length) {
        try (InputStream in = Api.region(file, offset, length)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                d.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (IOException | java.io.UncheckedIOException e) {
            throw new CliException(CliException.USAGE, "cannot read " + file + ": " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
