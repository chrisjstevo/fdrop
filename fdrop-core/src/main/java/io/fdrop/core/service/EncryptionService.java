package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.crypto.EncryptingStorage;
import io.fdrop.core.db.EncryptionRepo;
import io.fdrop.core.db.EncryptionRepo.Counts;
import io.fdrop.core.db.EncryptionRepo.Job;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.storage.WriteHandle;
import io.fdrop.core.storage.WriteOptions;
import io.fdrop.core.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Encryption at rest, the admin side: the inventory of stored objects per KEK id, and re-encryption jobs that
 * rewrite objects under the primary KEK (to retire a key, or to encrypt files stored before encryption was on).
 * Jobs are claimed item by item through the metadata DB, so every node works on them and a restart resumes them.
 */
public final class EncryptionService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);
    private static final long CLAIM = 10 * 60_000;

    /** What the admin screen shows. */
    public record Status(boolean enabled, String provider, String primaryKeyId, boolean allowPlaintextReads,
                         Map<String, Counts> inventory, Optional<Job> job, Map<String, Long> jobItems,
                         List<EncryptingStorage.Failure> recentFailures) {
    }

    private final FdropConfig.Encryption cfg;
    private final EncryptingStorage storage;
    private final EncryptionRepo repo;
    private final FdropMetrics metrics;
    private final String node;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("encryption-jobs").daemon().factory());
    private volatile boolean started;

    public EncryptionService(FdropConfig cfg, EncryptingStorage storage, EncryptionRepo repo, FdropMetrics metrics) {
        this.cfg = cfg.storage.encryption;
        this.storage = storage;
        this.repo = repo;
        this.metrics = metrics;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            host = "node";
        }
        this.node = host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Daily inventory and a job worker every 10 s. */
    public void start() {
        started = true;
        exec.scheduleWithFixedDelay(this::safeWork, 5, 10, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(this::safeInventory, 60, 24 * 3600, TimeUnit.SECONDS);
    }

    private static void requireAdmin(Principal p) {
        DestinationService.requireAdmin(p);
    }

    public Status status(Principal admin) {
        requireAdmin(admin);
        Optional<Job> job = repo.latest();
        return new Status(cfg.enabled, storage.keyProvider() == null ? null : storage.keyProvider().name(),
                storage.keyProvider() == null ? null : storage.keyProvider().primaryKeyId(), cfg.allowPlaintextReads,
                repo.inventory(), job, job.map(j -> repo.itemStates(j.id())).orElse(Map.of()),
                storage.recentFailures());
    }

    // ---- inventory ----

    private void safeInventory() {
        try {
            inventory();
        } catch (RuntimeException | IOException e) {
            log.warn("encryption inventory failed: {}", e.toString());
        }
    }

    /** Counts stored objects per KEK id by reading each header (one small ranged read per object). */
    public Map<String, Counts> inventory() throws IOException {
        Map<String, long[]> counts = new LinkedHashMap<>();
        try (Stream<ObjectInfo> s = storage.inner().list(StoragePath.ROOT, true)) {
            for (ObjectInfo o : (Iterable<ObjectInfo>) s::iterator) {
                if (o.directory()) {
                    continue;
                }
                long[] c = counts.computeIfAbsent(storage.keyIdOf(o), k -> new long[2]);
                c[0]++;
                c[1] += o.size();
            }
        }
        repo.replaceInventory(counts, System.currentTimeMillis());
        Map<String, Long> gauge = new LinkedHashMap<>();
        counts.forEach((k, v) -> gauge.put(k, v[0]));
        metrics.encryptionObjects(gauge);
        return repo.inventory();
    }

    public Map<String, Counts> refreshInventory(Principal admin) {
        requireAdmin(admin);
        try {
            return inventory();
        } catch (IOException e) {
            throw FdropException.unavailable("could not read the storage: " + e.getMessage());
        }
    }

    // ---- jobs ----

    /** Starts re-encrypting every object under {@code fromKeyId}, or every plaintext object. */
    public Job start(Principal admin, String fromKeyId, boolean plaintext) {
        requireAdmin(admin);
        if (!cfg.enabled || storage.keyProvider() == null) {
            throw FdropException.conflict("encryption at rest is off: turn it on before re-encrypting");
        }
        if (plaintext == (fromKeyId != null && !fromKeyId.isBlank())) {
            throw FdropException.badRequest("give either fromKeyId or plaintext: true");
        }
        if (!plaintext && fromKeyId.equals(storage.keyProvider().primaryKeyId())) {
            throw FdropException.badRequest(fromKeyId + " is the primary key; make another key primary first");
        }
        if (repo.active().isPresent()) {
            throw FdropException.conflict("a re-encryption job is already running");
        }
        Job j = new Job(Hashing.newId("rej"), plaintext ? "PLAINTEXT" : "KEY", plaintext ? null : fromKeyId,
                "SCANNING", 0, 0, 0, 0, admin.sub(), System.currentTimeMillis(), null);
        repo.insertJob(j);
        Audit.log("storage.encryption.reencrypt.started", "user", admin.username(), "job", j.id(), "scope", j.scope(),
                "fromKeyId", fromKeyId);
        Thread.ofVirtual().name("reencrypt-scan-" + j.id()).start(() -> scan(j));
        return repo.find(j.id()).orElseThrow();
    }

    /** Lists the objects the job covers, then lets the workers at them. */
    private void scan(Job j) {
        String want = "PLAINTEXT".equals(j.scope()) ? "plaintext" : j.fromKeyId();
        try (Stream<ObjectInfo> s = storage.inner().list(StoragePath.ROOT, true)) {
            for (ObjectInfo o : (Iterable<ObjectInfo>) s::iterator) {
                if (!o.directory() && want.equals(storage.keyIdOf(o))) {
                    repo.addItem(j.id(), o.path().toString());
                }
            }
            repo.setState(j.id(), "SCANNING", "RUNNING", null);
            if (started) {
                exec.execute(this::safeWork);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("re-encryption scan failed: {}", e.toString());
            repo.setState(j.id(), "SCANNING", "FAILED", System.currentTimeMillis());
        }
    }

    public void cancel(Principal admin) {
        requireAdmin(admin);
        Job j = repo.active().orElseThrow(() -> FdropException.notFound("no re-encryption job is running"));
        if (repo.setState(j.id(), j.state(), "CANCELLED", System.currentTimeMillis())) {
            Audit.log("storage.encryption.reencrypt.cancelled", "user", admin.username(), "job", j.id(),
                    "objectsDone", j.objectsDone());
        }
    }

    private void safeWork() {
        try {
            work();
        } catch (RuntimeException e) {
            log.warn("re-encryption worker failed: {}", e.toString());
        }
    }

    /** Re-encrypts claimable items of the running job; returns how many this call handled. */
    public int work() {
        Optional<Job> active = repo.active().filter(j -> j.state().equals("RUNNING"));
        if (active.isEmpty()) {
            return 0;
        }
        Job j = active.get();
        int handled = 0;
        long budgetStart = System.nanoTime();
        long bytes = 0;
        while (true) {
            long now = System.currentTimeMillis();
            List<String> keys = repo.claimable(j.id(), now, 50);
            if (keys.isEmpty()) {
                break;
            }
            for (String key : keys) {
                if (!repo.find(j.id()).map(x -> x.state().equals("RUNNING")).orElse(false)) {
                    return handled;
                }
                if (!repo.claim(j.id(), key, node, now + CLAIM, now)) {
                    continue;
                }
                try {
                    long n = reencrypt(StoragePath.of(key), j);
                    repo.finishItem(j.id(), key, true, n, null);
                    bytes += n;
                } catch (IOException | RuntimeException e) {
                    repo.finishItem(j.id(), key, false, 0, e.toString().length() > 900
                            ? e.toString().substring(0, 900) : e.toString());
                    log.warn("could not re-encrypt {}: {}", key, e.toString());
                }
                handled++;
                throttle(budgetStart, bytes);
            }
            metrics.reencryptObjects(repo.itemStates(j.id()));
        }
        metrics.reencryptObjects(repo.itemStates(j.id()));
        if (repo.open(j.id()) == 0 && repo.setState(j.id(), "RUNNING", "FINISHED", System.currentTimeMillis())) {
            Job done = repo.find(j.id()).orElse(j);
            Audit.log("storage.encryption.reencrypt.finished", "job", j.id(), "scope", j.scope(), "fromKeyId",
                    j.fromKeyId(), "objectsDone", done.objectsDone(), "objectsFailed", done.objectsFailed());
            safeInventory();
        }
        return handled;
    }

    private void throttle(long startNanos, long bytes) {
        long perSecond = cfg.reencrypt.bytesPerSecond.bytes();
        if (perSecond <= 0) {
            return;
        }
        long dueNanos = bytes * 1_000_000_000L / perSecond;
        long ahead = dueNanos - (System.nanoTime() - startNanos);
        if (ahead > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(ahead);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Rewrites one object through the encryption layer under the primary KEK, replacing it atomically. If the object
     * changed meanwhile (someone uploaded over it, which already encrypts under the primary KEK), the rewrite is
     * dropped. Returns the plaintext bytes rewritten.
     */
    long reencrypt(StoragePath path, Job j) throws IOException {
        StorageBackend raw = storage.inner();
        Optional<ObjectInfo> before = raw.stat(path);
        if (before.isEmpty() || before.get().directory()) {
            return 0;
        }
        String want = "PLAINTEXT".equals(j.scope()) ? "plaintext" : j.fromKeyId();
        if (!want.equals(storage.keyIdOf(before.get()))) {
            return 0;
        }
        long size = storage.stat(path).map(ObjectInfo::size).orElse(0L);
        try (ReadableByteChannel in = storage.openRead(path, 0, -1);
             WriteHandle out = storage.openWrite(path, new WriteOptions(null, true, size))) {
            ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
            while (in.read(buf) >= 0) {
                buf.flip();
                while (buf.hasRemaining()) {
                    out.write(buf);
                }
                buf.clear();
            }
            Optional<ObjectInfo> now = raw.stat(path);
            if (now.isEmpty() || !same(before.get(), now.get())) {
                out.abort();
                return 0;
            }
            out.commit();
        }
        return size;
    }

    private static boolean same(ObjectInfo a, ObjectInfo b) {
        return a.size() == b.size() && Objects.equals(a.etag(), b.etag())
                && Objects.equals(a.lastModified(), b.lastModified());
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
