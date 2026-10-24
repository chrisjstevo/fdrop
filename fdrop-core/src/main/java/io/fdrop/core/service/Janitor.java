package io.fdrop.core.service;

import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.Db;
import io.fdrop.core.db.DestinationRepo;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.UploadRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.UploadState;
import io.fdrop.core.model.Enums.UserKind;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.FolderForward;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.model.Records.UploadSession;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background housekeeping: expiring abandoned uploads (every 15 min), pruning old sessions and My uploads rows
 * past {@code upload.historyRetention} (hourly), deleting forwarded files past their folder's keep days, and
 * refreshing the count gauges for {@code /metrics} (every 30 s) so scrapes never touch the DB.
 */
public final class Janitor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Janitor.class);

    private final FdropConfig cfg;
    private final Db db;
    private final UploadRepo uploads;
    private final UploadService uploadService;
    private final RecordRepo records;
    private final FolderRepo folders;
    private final DestinationRepo destinations;
    private final UserRepo users;
    private final StorageBackend storage;
    private final FdropMetrics metrics;
    private final AppAccountService appAccounts;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("janitor").daemon().factory());

    public Janitor(FdropConfig cfg, Db db, UploadRepo uploads, UploadService uploadService, RecordRepo records,
                   FolderRepo folders, DestinationRepo destinations, UserRepo users, StorageBackend storage,
                   FdropMetrics metrics, AppAccountService appAccounts) {
        this.cfg = cfg;
        this.db = db;
        this.uploads = uploads;
        this.uploadService = uploadService;
        this.records = records;
        this.folders = folders;
        this.destinations = destinations;
        this.users = users;
        this.storage = storage;
        this.metrics = metrics;
        this.appAccounts = appAccounts;
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::safeExpire, 1, 15 * 60, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(this::safeRetention, 60, 3600, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(this::safeGauges, 0, 30, TimeUnit.SECONDS);
    }

    private void safeExpire() {
        try {
            expireUploads();
        } catch (RuntimeException e) {
            log.warn("upload expiry failed: {}", e.toString());
        }
    }

    private void safeRetention() {
        try {
            retention();
        } catch (RuntimeException e) {
            log.warn("retention run failed: {}", e.toString());
        }
    }

    private void safeGauges() {
        try {
            refreshGauges();
        } catch (RuntimeException e) {
            log.debug("gauge refresh failed: {}", e.toString());
        }
    }

    public void expireUploads() {
        for (UploadSession s : uploads.expired(System.currentTimeMillis())) {
            uploadService.expire(s);
            log.info("expired upload {} ({})", s.id(), s.virtualPath());
        }
    }

    public void retention() {
        long now = System.currentTimeMillis();
        for (UploadSession s : uploads.finishedBefore(now - Duration.ofDays(7).toMillis())) {
            uploads.delete(s.id());
        }
        int pruned = records.deleteOlderThan(now - Durations.parse(cfg.upload.historyRetention).toMillis());
        if (pruned > 0) {
            log.info("pruned {} My uploads rows past retention", pruned);
        }
        for (Folder f : folders.visibleToAll(FolderType.FORWARD)) {
            Optional<FolderForward> ff = folders.forward(f.id());
            if (ff.isEmpty() || ff.get().keepDays() <= 0) {
                continue;
            }
            for (ForwardJob j : records.sentBefore(f.id(), now - Duration.ofDays(ff.get().keepDays()).toMillis())) {
                deleteForwarded(f, j);
            }
        }
    }

    /** Removes a forwarded file from fdrop and gives the quota back. */
    public void deleteForwarded(Folder f, ForwardJob j) {
        try {
            StoragePath key = StoragePath.of(j.storageKey());
            Optional<io.fdrop.core.storage.ObjectInfo> o = storage.stat(key);
            if (o.isPresent()) {
                storage.delete(key);
                users.addUsage(f.ownerSub(), -o.get().size());
            }
        } catch (Exception e) {
            log.warn("could not delete forwarded file {}: {}", j.storageKey(), e.toString());
        }
    }

    public void refreshGauges() {
        Map<String, Long> foldersByType = new HashMap<>();
        for (FolderType t : FolderType.values()) {
            foldersByType.put(t.name().toLowerCase(), folders.count(t));
        }
        Map<String, Long> sessions = new HashMap<>();
        for (UploadState s : List.of(UploadState.OPEN, UploadState.ASSEMBLING)) {
            sessions.put(s.name(), uploads.countByState(s));
        }
        Map<List<String>, Long> jobs = new HashMap<>();
        for (ForwardDestination d : destinations.all()) {
            for (JobState s : JobState.values()) {
                if (s == JobState.SENT) {
                    continue;
                }
                jobs.put(List.of(s.name(), d.id()), records.countJobs(s, d.id()));
            }
        }
        Map<String, Long> usersByKind = Map.of("user", users.count(UserKind.USER),
                "application", users.count(UserKind.APPLICATION));
        metrics.update(new FdropMetrics.Snapshot(usersByKind, users.totalUsed(), foldersByType, sessions, jobs));
        metrics.appCredentialsExpiring(appAccounts.expiring());
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
