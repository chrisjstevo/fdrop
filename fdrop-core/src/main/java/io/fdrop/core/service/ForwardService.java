package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.DestinationRepo;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.SearchRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.FolderForward;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.model.Records.UploadRecord;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * A forward folder's destinations, and what happens to a file once every destination has it. Each file gets one
 * forward job per destination; the file stays in fdrop until all of them are sent (or the owner gave up on some),
 * then it is deleted or kept for the folder's keep days, counted from the last send.
 */
public final class ForwardService {

    private static final Logger log = LoggerFactory.getLogger(ForwardService.class);

    /** One destination of a folder as its members see it. */
    /**
     * One of a forward folder's destinations: {@code paused} when it is disabled or no longer assigned to the owner,
     * and {@code browsable} when its remote directory can be listed from the folder (see RemoteBrowseService).
     */
    public record DestinationRef(String id, String name, boolean paused, boolean browsable) {
    }

    private final FdropConfig cfg;
    private final FolderRepo folders;
    private final DestinationRepo destinations;
    private final RecordRepo records;
    private final UserRepo users;
    private final StorageBackend storage;
    private final PathResolver resolver;
    private final ExpiryService expiry;
    private final UploadRecorder recorder;
    private final SearchRepo search;

    public ForwardService(FdropConfig cfg, FolderRepo folders, DestinationRepo destinations, RecordRepo records,
                          UserRepo users, StorageBackend storage, PathResolver resolver, ExpiryService expiry,
                          UploadRecorder recorder, SearchRepo search) {
        this.cfg = cfg;
        this.folders = folders;
        this.destinations = destinations;
        this.records = records;
        this.users = users;
        this.storage = storage;
        this.resolver = resolver;
        this.expiry = expiry;
        this.recorder = recorder;
        this.search = search;
    }

    // ---- choosing destinations ----

    /**
     * Checks a folder's chosen destinations: one to {@code forward.maxDestinationsPerFolder}, no duplicates, each
     * enabled and assigned to {@code ownerSub}. Destinations the folder already has ({@code keep}) are let through
     * even if disabled or unassigned since, so an owner can still change other settings. Returns them in order.
     */
    public List<String> validate(String ownerSub, List<String> ids, List<String> keep) {
        if (ids == null || ids.isEmpty() || ids.stream().allMatch(i -> i == null || i.isBlank())) {
            throw FdropException.badRequest("choose at least one destination");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw FdropException.badRequest("destination ids cannot be blank");
            }
            if (!unique.add(id)) {
                throw FdropException.badRequest("a destination can only be chosen once");
            }
        }
        int max = cfg.forward.maxDestinationsPerFolder;
        if (unique.size() > max) {
            throw FdropException.badRequest("a forward folder can send to at most " + max + " destinations");
        }
        for (String id : unique) {
            if (keep.contains(id)) {
                continue;
            }
            ForwardDestination d = destinations.find(id)
                    .orElseThrow(() -> FdropException.badRequest("unknown destination " + id));
            if (!destinations.isAssigned(d.id(), ownerSub)) {
                throw FdropException.forbidden("destination '" + d.name() + "' is not assigned to you");
            }
            if (!d.enabled()) {
                throw FdropException.badRequest("destination '" + d.name() + "' is disabled");
            }
        }
        return List.copyOf(unique);
    }

    /** A forward folder's destinations with names, and whether each is paused (disabled or unassigned). */
    public List<DestinationRef> destinationsOf(Folder f) {
        List<DestinationRef> out = new ArrayList<>();
        for (String id : folders.destinations(f.id())) {
            Optional<ForwardDestination> d = destinations.find(id);
            boolean paused = d.isEmpty() || !d.get().enabled() || !destinations.isAssigned(id, f.ownerSub());
            out.add(new DestinationRef(id, d.map(ForwardDestination::name).orElse("(deleted destination)"), paused,
                    d.map(ForwardDestination::browsable).orElse(false)));
        }
        return out;
    }

    /** Destination names by id, for job lists and My uploads. */
    public String destinationName(String id) {
        return destinations.find(id).map(ForwardDestination::name).orElse("(deleted destination)");
    }

    /**
     * Sends the files still in a forward folder to destinations just added to it: one new job per file and added
     * destination. A file kept after sending loses its keep period until the new destination has it too. Returns
     * how many files were queued.
     */
    public int sendExisting(Principal owner, Folder f, List<String> added) {
        int files = 0;
        long now = System.currentTimeMillis();
        for (UploadRecord r : records.forwardRecordsInFolder(f.id())) {
            if (r.storageKey() == null || !exists(r.storageKey())) {
                continue;
            }
            List<String> has = records.jobsForRecord(r.id()).stream().map(ForwardJob::destinationId).toList();
            boolean queued = false;
            for (String dest : added) {
                if (has.contains(dest)) {
                    continue;
                }
                records.insertJob(new ForwardJob(Hashing.newId("job"), f.id(), dest, r.id(), r.storageKey(),
                        r.fileName(), r.size(), JobState.QUEUED, 0, null, 0, null, now++, now, null, null, null));
                queued = true;
            }
            if (queued) {
                expiry.clearForwardKeep(r.storageKey(), r.id());
                records.refreshStatus(r.id());
                files++;
            }
        }
        if (files > 0) {
            Audit.log("forward.send_existing", "user", owner.username(), "folder", f.name(), "files", files,
                    "destinations", added);
            recorder.jobsQueued();
        }
        return files;
    }

    private boolean exists(String key) {
        try {
            return storage.stat(StoragePath.of(key)).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- finishing a file ----

    /**
     * Called when a job's change finished its file: every job is now sent or cancelled. Deletes the file from fdrop,
     * or keeps it for the folder's keep days from the last send. A file no destination received (all cancelled)
     * stays in fdrop for its owner to deal with.
     */
    public void finished(ForwardJob any) {
        List<ForwardJob> jobs = any.uploadRecordId() == null ? List.of(any)
                : records.jobsForRecord(any.uploadRecordId());
        Optional<ForwardJob> lastSent = jobs.stream().filter(j -> j.state() == JobState.SENT && j.sentAt() != null)
                .max(Comparator.comparingLong(ForwardJob::sentAt));
        if (lastSent.isEmpty()) {
            log.info("no destination received {}; it stays in fdrop", any.fileName());
            return;
        }
        Optional<Folder> f = folders.find(any.folderId());
        if (f.isEmpty()) {
            return;
        }
        Optional<FolderForward> ff = folders.forward(f.get().id());
        if (ff.isPresent() && ff.get().keepDays() > 0) {
            expiry.setForwardKeep(lastSent.get(), ff.get().keepDays(), lastSent.get().sentAt());
            return;
        }
        delete(f.get(), lastSent.get());
    }

    /** Removes a forwarded file from fdrop and gives the quota back. */
    private void delete(Folder f, ForwardJob j) {
        try {
            StoragePath key = StoragePath.of(j.storageKey());
            Optional<ObjectInfo> o = storage.stat(key);
            if (o.isPresent()) {
                storage.delete(key);
                users.addUsage(f.ownerSub(), -o.get().size());
                search.append(SearchRepo.Op.DELETE, key.toString(), null);
            }
        } catch (Exception e) {
            log.warn("could not delete forwarded file {}: {}", j.storageKey(), e.toString());
        }
    }

    // ---- giving up on a destination ----

    /** The owner stops sending one file to one destination whose job failed. */
    public void cancel(Principal p, String folderId, String jobId, String reason) {
        Folder f = folders.find(folderId).filter(x -> resolver.permission(p, x).isPresent())
                .orElseThrow(() -> FdropException.notFound("no such folder"));
        if (!f.ownerSub().equals(p.sub())) {
            throw FdropException.forbidden("only the folder's owner can do that");
        }
        ForwardJob j = records.job(jobId).filter(x -> x.folderId().equals(f.id()))
                .orElseThrow(() -> FdropException.notFound("no such job"));
        if (j.state() != JobState.FAILED) {
            throw FdropException.conflict("only failed jobs can be cancelled; retry or wait for the others");
        }
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty()) {
            throw FdropException.badRequest("say why you are giving up on this destination");
        }
        if (why.length() > 500) {
            why = why.substring(0, 500);
        }
        boolean finished = records.cancelJob(j.id(), p.sub(), why);
        Audit.log("forward.cancel", "user", p.username(), "folder", f.name(), "file", j.fileName(),
                "destination", destinationName(j.destinationId()), "reason", why);
        if (finished) {
            finished(j);
        }
    }
}
