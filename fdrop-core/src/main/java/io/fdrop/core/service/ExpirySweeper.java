package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.Db;
import io.fdrop.core.db.ExpiryRepo;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.SearchRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.model.Records.FileExpiry;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deletes files past their expiry. Runs on every node: each sweep claims due rows with a conditional update, so two
 * nodes never delete the same file, and a claim left by a crashed node lapses after {@code expiry.claimTimeout}.
 * Expiries due before the next sweep also get a timer, so a short expiry is deleted about on time. Everything is
 * in the metadata DB, so restarts lose nothing; access stops at the expiry instant regardless (see
 * {@link FileService}).
 */
public final class ExpirySweeper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);
    private static final long MIN_BACKOFF = 60_000;
    private static final long MAX_BACKOFF = 3_600_000;
    private static final long OVERDUE_AFTER = 5 * 60_000;

    private final FdropConfig.FileExpiry cfg;
    private final Db db;
    private final ExpiryRepo repo;
    private final RecordRepo records;
    private final UserRepo users;
    private final FolderRepo folders;
    private final StorageBackend storage;
    private final ExpiryService expiry;
    private final SearchRepo search;
    private final FdropMetrics metrics;
    private final Clock clock;
    private final String node;
    private final long interval;
    private final long claimTimeout;
    private final AtomicLong nextWake = new AtomicLong(Long.MAX_VALUE);
    private volatile boolean started;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("expiry-sweeper").daemon().factory());

    public ExpirySweeper(FdropConfig cfg, Db db, ExpiryRepo repo, RecordRepo records, UserRepo users,
                         FolderRepo folders, StorageBackend storage, ExpiryService expiry, FdropMetrics metrics,
                         Clock clock, SearchRepo search) {
        this.search = search;
        this.cfg = cfg.expiry;
        this.db = db;
        this.repo = repo;
        this.records = records;
        this.users = users;
        this.folders = folders;
        this.storage = storage;
        this.expiry = expiry;
        this.metrics = metrics;
        this.clock = clock;
        this.interval = Durations.parse(this.cfg.sweepInterval).toMillis();
        this.claimTimeout = Durations.parse(this.cfg.claimTimeout).toMillis();
        this.node = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        expiry.setScheduler(this::schedule);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "node";
        }
    }

    public void start() {
        started = true;
        exec.scheduleWithFixedDelay(this::safeSweep, 0, interval, TimeUnit.MILLISECONDS);
    }

    /** Wakes this node at the expiry instant when that comes before the next regular sweep. */
    void schedule(FileExpiry e) {
        long delay = e.expiresAt() - clock.millis();
        if (!started || delay >= interval || exec.isShutdown()) {
            return;
        }
        long at = e.expiresAt();
        long current = nextWake.get();
        if (at < current && nextWake.compareAndSet(current, at)) {
            exec.schedule(() -> {
                nextWake.compareAndSet(at, Long.MAX_VALUE);
                safeSweep();
            }, Math.max(0, delay) + 50, TimeUnit.MILLISECONDS);
        }
    }

    private void safeSweep() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("expiry sweep failed: {}", e.toString());
        }
    }

    /** Deletes every due file this node can claim; returns how many were removed. */
    public int sweep() {
        int removed = 0;
        long minGapNanos = cfg.maxDeletesPerSecond > 0 ? 1_000_000_000L / cfg.maxDeletesPerSecond : 0;
        while (true) {
            long now = clock.millis();
            List<FileExpiry> due = repo.due(now, cfg.batchSize);
            for (FileExpiry e : due) {
                if (!repo.claim(e.id(), node, now + claimTimeout, now)) {
                    continue;
                }
                long started = System.nanoTime();
                if (process(e)) {
                    removed++;
                }
                long spare = minGapNanos - (System.nanoTime() - started);
                if (spare > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(spare);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return removed;
                    }
                }
            }
            if (due.size() < cfg.batchSize) {
                break;
            }
        }
        long now = clock.millis();
        metrics.expiryGauges(repo.count(), repo.overdue(now - OVERDUE_AFTER));
        expiry.deleteStalePending();
        return removed;
    }

    /** Deletes one claimed file; true when this node removed it. */
    private boolean process(FileExpiry e) {
        StoragePath key = StoragePath.of(e.storageKey());
        try {
            Optional<ObjectInfo> o = storage.stat(key);
            boolean present = o.isPresent() && !o.get().directory();
            if (present) {
                storage.delete(key);
            }
            long deletedAt = clock.millis();
            boolean ours = db.inTransaction(() -> {
                if (!repo.deleteClaimed(e.id(), node)) {
                    return false;
                }
                String owner = quotaOwner(e);
                if (present && owner != null) {
                    users.addUsage(owner, -o.get().size());
                }
                records.markExpired(e.storageKey());
                search.append(SearchRepo.Op.DELETE, e.storageKey(), null);
                return true;
            });
            if (!ours) {
                return false;
            }
            metrics.expiryDeleted(storage.type(), present ? "deleted" : "already_gone");
            metrics.expiryDeleteLag(storage.type(), deletedAt - e.expiresAt());
            Audit.log("file.expired", "user", "system", "storageKey", e.storageKey(), "folder", e.folderId(),
                    "expiresAt", Instant.ofEpochMilli(e.expiresAt()), "size", present ? o.get().size() : 0,
                    "backend", storage.type(), "uploadedBy", e.setBySub(),
                    "lagSeconds", (deletedAt - e.expiresAt()) / 1000, "outcome", present ? "deleted" : "already_gone");
            return true;
        } catch (Exception ex) {
            long backoff = Math.min(MAX_BACKOFF, MIN_BACKOFF << Math.min(6, e.attempts()));
            repo.fail(e.id(), node, ex.toString(), clock.millis() + backoff);
            metrics.expiryDeleted(storage.type(), "error");
            Audit.log("file.expiry.deleteFailed", "storageKey", e.storageKey(), "attempts", e.attempts() + 1,
                    "error", ex.toString());
            log.warn("could not delete expired file {}: {}", e.storageKey(), ex.toString());
            return false;
        }
    }

    private String quotaOwner(FileExpiry e) {
        if (e.userSub() != null) {
            return e.userSub();
        }
        return e.folderId() == null ? null : folders.find(e.folderId()).map(Folder::ownerSub).orElse(null);
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
