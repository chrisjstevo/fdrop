package io.fdrop.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * {@code fdrop watch}: listens to one local folder and uploads each new file, once it is ready, into one server folder
 * (see "Watching a directory" in SPEC.md). {@link WatchService} events say where to look; scans at start, after an
 * overflow and every {@code --rescan} catch what events miss; with {@code --poll} only scans are used. The ledger stops
 * a file going up twice.
 */
final class Watcher {

    /** Sends one file; the real one is {@link Uploader} over the API. */
    interface Uploads {
        Uploader.Result upload(Path file, String remotePath, BooleanSupplier unchanged) throws InterruptedException;

        /** Creates a server folder; one that exists already is fine. */
        void mkdir(String remotePath);
    }

    /** A file's size and last-modified time: when neither changes, it is settling. */
    record Stat(long size, long modified) {
    }

    private static final class Candidate {
        final Path path;
        final String rel;
        volatile Stat stat;
        volatile long stableSince;
        volatile long notBefore;
        volatile boolean inFlight;

        Candidate(Path path, String rel, Stat stat, long now) {
            this.path = path;
            this.rel = rel;
            this.stat = stat;
            this.stableSince = now;
        }
    }

    private static final Duration TICK = Duration.ofMillis(250);
    private static final Duration STATUS_EVERY = Duration.ofSeconds(30);

    private final WatchConfig cfg;
    private final Uploads uploads;
    private final WatchLedger ledger;
    private final WatchLog log;
    private final Backoff.Clock clock;
    private final List<PathMatcher> include;
    private final List<PathMatcher> ignore;
    private final Map<String, Candidate> candidates = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private final Set<String> remoteDirs = ConcurrentHashMap.newKeySet();
    private final Set<Path> skippedLinks = ConcurrentHashMap.newKeySet();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong uploaded = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final ExecutorService pool;
    private final Instant started = Instant.now();
    private volatile String lastUpload;
    private volatile Instant lastUploadAt;
    private volatile String lastError;
    private volatile Instant lastErrorAt;
    private volatile boolean stopping;
    private volatile boolean rescanRequested;
    private WatchService watchService;
    private long lastStatus;

    Watcher(WatchConfig cfg, Uploads uploads, WatchLedger ledger, WatchLog log, Backoff.Clock clock) {
        this.cfg = cfg;
        this.uploads = uploads;
        this.ledger = ledger;
        this.log = log;
        this.clock = clock;
        this.include = cfg.include().stream().map(WatchConfig::matcher).toList();
        this.ignore = cfg.ignore().stream().map(WatchConfig::matcher).toList();
        this.pool = Executors.newFixedThreadPool(cfg.maxFiles(), Thread.ofPlatform().name("fdrop-watch-upload-", 0)
                .daemon().factory());
    }

    // ---- running ----

    /** Watches until {@link #stop()}; throws {@link CliException} with code 7 when the folder goes away. */
    void run() throws InterruptedException {
        try {
            if (cfg.poll() == null) {
                watchService = FileSystems.getDefault().newWatchService();
                if (cfg.onNetworkFileSystem()) {
                    log.event("hint", cfg.localDir() + " is on a network file system, which may not report new files;"
                            + " use --poll 30s if files are missed", "localDir", cfg.localDir());
                }
            }
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, "cannot watch " + cfg.localDir() + ": " + e.getMessage());
        }
        log.event("started", "watching " + cfg.localDir() + " and uploading to " + cfg.remoteDir()
                        + (cfg.poll() != null ? " (polling every " + cfg.poll() + ")" : ""),
                "localDir", cfg.localDir(), "remoteDir", cfg.remoteDir());
        scan();
        long lastScan = clock.nanos();
        try {
            while (!stopping) {
                checkLocalDir();
                if (watchService != null) {
                    WatchKey k;
                    try {
                        k = watchService.poll(TICK.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (ClosedWatchServiceException e) {
                        break;
                    }
                    while (k != null) {
                        Path dir = keys.get(k);
                        if (dir != null) {
                            handle(dir, k.pollEvents());
                        }
                        if (!k.reset()) {
                            keys.remove(k);
                        }
                        k = watchService.poll();
                    }
                } else {
                    clock.sleep(TICK);
                }
                long now = clock.nanos();
                Duration every = cfg.poll() != null ? cfg.poll() : cfg.rescan();
                if (rescanRequested || now - lastScan >= every.toNanos()) {
                    rescanRequested = false;
                    scan();
                    lastScan = now;
                }
                tick();
                if (now - lastStatus >= STATUS_EVERY.toNanos()) {
                    writeStatus();
                }
            }
        } finally {
            shutdown();
        }
    }

    /** Stops taking new files; in-flight uploads get {@code --shutdown-timeout} to finish. */
    void stop() {
        stopping = true;
    }

    private void shutdown() throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(cfg.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
            // interrupted uploads keep their sessions for the next start
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        writeStatus();
        log.event("stopped", "stopped; " + uploaded.get() + " uploaded, " + refused.get() + " refused",
                "uploaded", uploaded.get(), "refused", refused.get());
    }

    private void checkLocalDir() {
        if (!Files.isDirectory(cfg.localDir()) || !Files.isReadable(cfg.localDir())) {
            throw new CliException(CliException.WATCH, "the watched folder " + cfg.localDir()
                    + " disappeared or became unreadable");
        }
    }

    /** Waits until nothing is uploading; for tests. */
    void awaitIdle(Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        while (running.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    // ---- finding files ----

    /** Handles the events of one watched folder. */
    void handle(Path dir, List<WatchEvent<?>> events) {
        for (WatchEvent<?> e : events) {
            if (e.kind() == StandardWatchEventKinds.OVERFLOW) {
                rescanRequested = true;
                log.event("overflow", "too many changes at once; scanning " + cfg.localDir() + " again");
                continue;
            }
            Path p = dir.resolve((Path) e.context());
            if (cfg.recursive() && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                if (!ignoredDir(p)) {
                    scanTree(p);
                }
            } else {
                observe(p);
            }
        }
    }

    /** Looks at every file in the watched tree, registering folders with the watch service. */
    void scan() {
        checkLocalDir();
        scanTree(cfg.localDir());
    }

    private void scanTree(Path start) {
        try {
            Files.walkFileTree(start, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    cfg.recursive() ? Integer.MAX_VALUE : 1, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(cfg.localDir()) && (!cfg.recursive() || ignoredDir(dir))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            register(dir);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!attrs.isDirectory()) {
                                observe(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.event("error", "cannot scan " + start + ": " + e.getMessage());
        }
    }

    private void register(Path dir) {
        if (watchService == null || keys.containsValue(dir)) {
            return;
        }
        try {
            keys.put(dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.OVERFLOW), dir);
        } catch (IOException e) {
            log.event("error", "cannot watch " + dir + ": " + e.getMessage());
        }
    }

    String relative(Path p) {
        return cfg.localDir().relativize(p).toString().replace('\\', '/');
    }

    /** Ignored names, names outside {@code --include}, and marker files are never uploaded. */
    boolean accepted(String rel) {
        Path relPath = Path.of(rel);
        Path name = relPath.getFileName();
        for (PathMatcher m : ignore) {
            if (m.matches(name) || m.matches(relPath)) {
                return false;
            }
        }
        if (cfg.readyMarker() != null && rel.endsWith(cfg.readyMarker())) {
            return false;
        }
        if (include.isEmpty()) {
            return true;
        }
        for (PathMatcher m : include) {
            if (m.matches(name) || m.matches(relPath)) {
                return true;
            }
        }
        return false;
    }

    private boolean ignoredDir(Path dir) {
        Path name = dir.getFileName();
        for (PathMatcher m : ignore) {
            if (m.matches(name)) {
                return true;
            }
        }
        return false;
    }

    /** Notes a file that may be new or changed; it is uploaded by {@link #tick()} once it is ready. */
    void observe(Path p) {
        if (!p.startsWith(cfg.localDir())) {
            return;
        }
        String rel = relative(p);
        if (rel.isEmpty() || !accepted(rel)) {
            return;
        }
        if (Files.isSymbolicLink(p)) {
            if (skippedLinks.add(p)) {
                log.event("skipped", rel + " is a symbolic link, which is not uploaded", "path", rel);
            }
            return;
        }
        if (!WatchConfig.regularFile(p)) {
            return;
        }
        Stat st = stat(p);
        if (st == null) {
            candidates.remove(rel);
            return;
        }
        if (ledger.settled(rel, st.size(), st.modified())) {
            candidates.remove(rel);
            return;
        }
        long now = clock.nanos();
        candidates.compute(rel, (k, c) -> {
            if (c == null) {
                return new Candidate(p, rel, st, now);
            }
            if (!c.inFlight && !st.equals(c.stat)) {
                c.stat = st;
                c.stableSince = now;
            }
            return c;
        });
    }

    static Stat stat(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new Stat(a.size(), a.lastModifiedTime().toMillis());
        } catch (IOException e) {
            return null;
        }
    }

    // ---- uploading ----

    /** Starts uploads of the files that are ready, oldest first, up to {@code --max-files} at once. */
    void tick() {
        long now = clock.nanos();
        List<Candidate> ready = new ArrayList<>();
        for (Candidate c : candidates.values()) {
            if (c.inFlight || now < c.notBefore) {
                continue;
            }
            Stat st = stat(c.path);
            if (st == null) {
                candidates.remove(c.rel);
                continue;
            }
            if (ledger.settled(c.rel, st.size(), st.modified())) {
                candidates.remove(c.rel);
                continue;
            }
            if (!st.equals(c.stat)) {
                c.stat = st;
                c.stableSince = now;
                continue;
            }
            boolean isReady = cfg.readyMarker() != null
                    ? Files.exists(marker(c.path))
                    : now - c.stableSince >= cfg.settle().toNanos();
            if (isReady) {
                ready.add(c);
            }
        }
        ready.sort(Comparator.comparingLong((Candidate c) -> c.stat.modified()).thenComparing(c -> c.rel));
        for (Candidate c : ready) {
            if (stopping || running.get() >= cfg.maxFiles()) {
                break;
            }
            c.inFlight = true;
            running.incrementAndGet();
            log.event("ready", c.rel + " is ready (" + FdropCli.bytes(c.stat.size()) + ")", "path", c.rel,
                    "size", c.stat.size());
            pool.execute(() -> upload(c));
        }
    }

    int queued() {
        return (int) candidates.values().stream().filter(c -> !c.inFlight).count();
    }

    private Path marker(Path file) {
        return file.resolveSibling(file.getFileName() + cfg.readyMarker());
    }

    private void upload(Candidate c) {
        Stat sent = c.stat;
        String remote = cfg.remoteDir() + c.rel;
        long t0 = System.nanoTime();
        try {
            remoteParents(c.rel);
            Uploader.Result r = uploads.upload(c.path, remote, () -> sent.equals(stat(c.path)));
            ledger.record(c.rel, sent.size(), sent.modified(), r.sha256(), r.path(), "uploaded", null);
            candidates.remove(c.rel);
            uploaded.incrementAndGet();
            lastUpload = r.path();
            lastUploadAt = Instant.now();
            log.event("uploaded", c.rel + " -> " + r.path() + " (" + FdropCli.bytes(r.size()) + " in "
                            + Duration.ofNanos(System.nanoTime() - t0).toMillis() + " ms" + (r.resumed() ? ", resumed" : "")
                            + ")", "path", c.rel, "remote", r.path(), "size", r.size(), "sha256", r.sha256(),
                    "resumed", r.resumed(), "ms", Duration.ofNanos(System.nanoTime() - t0).toMillis());
            afterUpload(c);
        } catch (Uploader.FileChanged e) {
            changed(c, sent, remote);
        } catch (CliException e) {
            if (e.code() == CliException.GAVE_UP || e.code() == CliException.AUTH) {
                // the server or the certificate: not this file's fault, so it stays queued
                error(c.rel + " not uploaded yet: " + e.getMessage() + "; trying again in " + cfg.rescan());
                c.notBefore = clock.nanos() + cfg.rescan().toNanos();
            } else if (!sent.equals(stat(c.path))) {
                changed(c, sent, remote);
            } else {
                refuse(c, sent, remote, e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.event("interrupted", c.rel + " stopped mid-upload; it resumes at the next start", "path", c.rel);
        } catch (RuntimeException e) {
            error(c.rel + " failed: " + e);
            c.notBefore = clock.nanos() + cfg.rescan().toNanos();
        } finally {
            c.inFlight = false;
            running.decrementAndGet();
            writeStatus();
        }
    }

    private void changed(Candidate c, Stat sent, String remote) {
        ledger.record(c.rel, sent.size(), sent.modified(), null, remote, "changed", null);
        log.event("changed", c.rel + " changed while it was being uploaded; it goes up again once it settles",
                "path", c.rel);
        Stat now = stat(c.path);
        if (now != null) {
            c.stat = now;
        }
        c.stableSince = clock.nanos();
    }

    private void refuse(Candidate c, Stat sent, String remote, String problem) {
        ledger.record(c.rel, sent.size(), sent.modified(), null, remote, "refused", problem);
        candidates.remove(c.rel);
        refused.incrementAndGet();
        error(c.rel + " refused: " + problem);
        if (cfg.onFailure().kind() == WatchConfig.Action.Kind.MOVE) {
            try {
                Path to = moveInto(cfg.onFailure().dir(), c);
                Files.writeString(to.resolveSibling(to.getFileName() + ".error.txt"), problem + "\n",
                        StandardCharsets.UTF_8);
                log.event("set-aside", c.rel + " moved to " + to, "path", c.rel, "to", to);
            } catch (IOException e) {
                error("cannot move " + c.rel + " to " + cfg.onFailure().dir() + ": " + e.getMessage());
            }
        }
    }

    private void afterUpload(Candidate c) {
        try {
            switch (cfg.afterUpload().kind()) {
                case DELETE -> {
                    Files.deleteIfExists(c.path);
                    if (cfg.readyMarker() != null) {
                        Files.deleteIfExists(marker(c.path));
                    }
                }
                case MOVE -> moveInto(cfg.afterUpload().dir(), c);
                case KEEP -> {
                    // the ledger stops it going up again
                }
            }
        } catch (IOException e) {
            error("uploaded " + c.rel + " but cannot " + cfg.afterUpload().kind().name().toLowerCase(java.util.Locale.ROOT)
                    + " it: " + e.getMessage());
        }
    }

    /** Moves the file (and its marker) into {@code dir}, keeping its relative path; returns where it went. */
    private Path moveInto(Path dir, Candidate c) throws IOException {
        Path to = dir.resolve(c.rel);
        Files.createDirectories(to.getParent());
        Files.move(c.path, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (cfg.readyMarker() != null && Files.exists(marker(c.path))) {
            Files.move(marker(c.path), to.resolveSibling(to.getFileName() + cfg.readyMarker()),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return to;
    }

    /** Creates the server folders above a nested file, once each. */
    private void remoteParents(String rel) {
        int slash = rel.indexOf('/');
        while (slash > 0) {
            String dir = cfg.remoteDir() + rel.substring(0, slash);
            if (remoteDirs.add(dir)) {
                try {
                    uploads.mkdir(dir);
                } catch (RuntimeException e) {
                    remoteDirs.remove(dir);
                    throw e;
                }
            }
            slash = rel.indexOf('/', slash + 1);
        }
    }

    private void error(String message) {
        lastError = message;
        lastErrorAt = Instant.now();
        log.event("error", message);
    }

    // ---- status ----

    /** Writes the status JSON, replacing the old one atomically. */
    synchronized void writeStatus() {
        lastStatus = clock.nanos();
        if (cfg.statusFile() == null) {
            return;
        }
        ObjectNode n = Api.JSON.createObjectNode();
        n.put("localDir", cfg.localDir().toString());
        n.put("remoteDir", cfg.remoteDir());
        n.put("started", started.toString());
        n.put("updated", Instant.now().toString());
        n.put("queued", queued());
        n.put("inProgress", running.get());
        n.put("uploaded", uploaded.get());
        n.put("refused", refused.get());
        if (lastUpload != null) {
            n.put("lastUpload", lastUpload);
            n.put("lastUploadAt", lastUploadAt.toString());
        }
        if (lastError != null) {
            n.put("lastError", lastError);
            n.put("lastErrorAt", lastErrorAt.toString());
        }
        Path tmp = cfg.statusFile().resolveSibling(cfg.statusFile().getFileName() + ".tmp");
        try {
            Files.writeString(tmp, n.toPrettyString() + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, cfg.statusFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.event("error", "cannot write the status file " + cfg.statusFile() + ": " + e.getMessage());
        }
    }
}
