package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.DestinationRepo;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.RetrievalRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.RecordStatus;
import io.fdrop.core.model.Enums.RetrievalState;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.ForwardClientKey;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.model.Records.RemoteRetrieval;
import io.fdrop.core.model.Records.UploadRecord;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.storage.WriteHandle;
import io.fdrop.core.storage.WriteOptions;
import io.fdrop.core.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Destination tabs in forward folders (see "Browsing destinations" in spec/folders.md): listing a browsable
 * destination's remote directory, and retrieving its files into fdrop so they can be downloaded. Retrieved copies
 * live under {@code retrieved/<folderId>/}, apart from the folder's own files, so they are never forwarded or seen
 * over SFTP, and are deleted {@code forward.browse.keepFor} after their last download. Nothing on the destination is
 * ever changed.
 */
public final class RemoteBrowseService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteBrowseService.class);
    /** How many sent files a listing compares against, newest first. */
    private static final int SENT_LIMIT = 5000;
    private static final long STALE_AFTER = TimeUnit.MINUTES.toMillis(10);

    /** Where a remote file came from, as its row's "Came from" column says. */
    public enum CameFrom { SENT_BY_FDROP, CHANGED_SINCE_SENT, NOT_FROM_FDROP }

    /** One entry of a listing; {@code path} is relative to the destination's remote directory. */
    public record EntryView(String name, String path, boolean directory, long size, long modifiedAt,
                            CameFrom cameFrom, RemoteRetrieval retrieval) {
    }

    /** A remote directory as a destination tab shows it. */
    public record Listing(String destinationId, String destinationName, String remoteDir, String path,
                          long listedAt, boolean truncated, int maxEntries, List<EntryView> entries) {
    }

    /** A ready copy to download. */
    public record Content(RemoteRetrieval retrieval, String name, ObjectInfo info) {
    }

    private record Target(Folder folder, ForwardDestination destination, RemoteFiles.Login login) {
        String root() {
            String dir = destination.remoteDir();
            return dir.length() > 1 && dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
        }

        String full(String rel) {
            return rel.isEmpty() ? root() : root().equals("/") ? "/" + rel : root() + "/" + rel;
        }
    }

    private record Cached(long at, List<RemoteFiles.RemoteEntry> entries) {
    }

    private final FdropConfig.Browse cfg;
    private final long maxFileSize;
    private final FolderRepo folders;
    private final DestinationRepo destinations;
    private final DestinationService destinationService;
    private final RecordRepo records;
    private final RetrievalRepo repo;
    private final UserRepo users;
    private final StorageBackend storage;
    private final FileService files;
    private final PathResolver resolver;
    private final HostPolicy hosts;
    private final FdropMetrics metrics;
    private final long cacheFor;
    private final long keepFor;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /** When each directory was last listed successfully, for the error a failed listing shows. */
    private final Map<String, Long> lastGood = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> perDestination = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("remote-retrieval-sweep").daemon().factory());
    private volatile RemoteFiles remote;

    public RemoteBrowseService(FdropConfig config, FolderRepo folders, DestinationRepo destinations,
                               DestinationService destinationService, RecordRepo records, RetrievalRepo repo,
                               UserRepo users, StorageBackend storage, FileService files, PathResolver resolver,
                               HostPolicy hosts, FdropMetrics metrics) {
        this.cfg = config.forward.browse;
        this.maxFileSize = config.limits.maxFileSize.bytes();
        this.folders = folders;
        this.destinations = destinations;
        this.destinationService = destinationService;
        this.records = records;
        this.repo = repo;
        this.users = users;
        this.storage = storage;
        this.files = files;
        this.resolver = resolver;
        this.hosts = hosts;
        this.metrics = metrics;
        this.cacheFor = Durations.parse(cfg.cacheFor).toMillis();
        this.keepFor = Durations.parse(cfg.keepFor).toMillis();
    }

    /** The SFTP side, set by the server once its SSH client exists. Without it, browsing answers 503. */
    public void setRemote(RemoteFiles remote) {
        this.remote = remote;
    }

    public RemoteBrowseService start() {
        sweeper.scheduleWithFixedDelay(this::safeSweep, 1, 5 * 60, TimeUnit.SECONDS);
        return this;
    }

    // ---- listing ----

    /** Lists {@code path} (relative to the destination's remote directory; blank for the directory itself). */
    public Listing list(Principal p, String folderId, String destinationId, String path, boolean refresh) {
        Target t = target(p, folderId, destinationId);
        String rel = clean(path);
        String key = t.destination().id() + "|" + rel;
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (refresh || c == null || now - c.at() > cacheFor) {
            try {
                c = new Cached(now, remote().list(t.destination(), t.login(), t.root(), t.full(rel),
                        Math.max(1, cfg.maxEntries)));
            } catch (FdropException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                metrics.remoteList(t.destination().id(), false);
                Audit.log("forward.browse.list", "user", p.username(), "folder", t.folder().name(), "destination",
                        t.destination().name(), "path", "/" + rel, "outcome", "failed", "error", message(e));
                Long last = lastGood.get(key);
                throw FdropException.unavailable("couldn't list " + t.destination().name() + ": " + message(e)
                        + (last == null ? "" : ". Last listed " + Instant.ofEpochMilli(last)));
            }
            metrics.remoteList(t.destination().id(), true);
            cache.put(key, c);
            lastGood.put(key, now);
        }
        Audit.log("forward.browse.list", "user", p.username(), "folder", t.folder().name(), "destination",
                t.destination().name(), "path", "/" + rel, "results", c.entries().size(), "outcome", "ok");

        Map<String, Long> sent = new HashMap<>();
        if (rel.isEmpty()) {
            // fdrop sends into the remote directory itself, never a sub-folder; newest first, so the first wins
            for (ForwardJob j : records.sentTo(t.folder().id(), t.destination().id(), SENT_LIMIT)) {
                sent.putIfAbsent(j.fileName(), j.size());
            }
        }
        Map<String, RemoteRetrieval> newest = new HashMap<>();
        for (RemoteRetrieval r : repo.forDestination(t.folder().id(), t.destination().id())) {
            newest.putIfAbsent(r.remotePath(), r);
        }
        List<RemoteFiles.RemoteEntry> shown = c.entries().stream().filter(e -> !partial(e.name()))
                .sorted(Comparator.comparing((RemoteFiles.RemoteEntry e) -> e.name().toLowerCase())
                        .thenComparing(RemoteFiles.RemoteEntry::name))
                .toList();
        boolean truncated = shown.size() > cfg.maxEntries;
        List<EntryView> out = new ArrayList<>();
        for (RemoteFiles.RemoteEntry e : truncated ? shown.subList(0, cfg.maxEntries) : shown) {
            String entryPath = rel.isEmpty() ? e.name() : rel + "/" + e.name();
            if (e.directory()) {
                out.add(new EntryView(e.name(), entryPath, true, 0, e.modifiedAt(), null, null));
                continue;
            }
            Long sentSize = sent.get(e.name());
            CameFrom from = sentSize == null ? CameFrom.NOT_FROM_FDROP
                    : sentSize == e.size() ? CameFrom.SENT_BY_FDROP : CameFrom.CHANGED_SINCE_SENT;
            RemoteRetrieval r = newest.get(entryPath);
            if (r != null && !sameVersion(r, e)) {
                r = null; // a copy of an older version: the sweep removes it in time
            }
            out.add(new EntryView(e.name(), entryPath, false, e.size(), e.modifiedAt(), from, r));
        }
        return new Listing(t.destination().id(), t.destination().name(), t.root(), rel, c.at(), truncated,
                cfg.maxEntries, out);
    }

    // ---- retrieving ----

    /**
     * Starts copying the remote file at {@code path} into fdrop, or returns the copy already present or on its way:
     * an earlier retrieval of the same version, or the folder's own file that was sent there and is still in fdrop.
     */
    public RemoteRetrieval retrieve(Principal p, String folderId, String destinationId, String path) {
        Target t = target(p, folderId, destinationId);
        String rel = clean(path);
        if (rel.isEmpty()) {
            throw FdropException.badRequest("give the path of a file in the destination's folder");
        }
        RemoteFiles.RemoteEntry e;
        try {
            e = remote().stat(t.destination(), t.login(), t.root(), t.full(rel));
        } catch (FdropException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw FdropException.unavailable("couldn't reach " + t.destination().name() + ": " + message(ex));
        }
        if (e.directory()) {
            throw FdropException.badRequest("that is a folder; open it instead");
        }
        if (e.size() > maxFileSize) {
            throw FdropException.tooLarge("the file is larger than the " + maxFileSize + "-byte limit");
        }
        long now = System.currentTimeMillis();
        RemoteRetrieval r;
        synchronized (this) {
            Optional<RemoteRetrieval> existing = present(t, rel, e);
            if (existing.isPresent()) {
                return existing.get();
            }
            Optional<RemoteRetrieval> own = ownCopy(t, rel, e, p, now);
            if (own.isPresent()) {
                metrics.remoteRetrieval(t.destination().id(), "present");
                Audit.log("forward.browse.retrieve", "user", p.username(), "folder", t.folder().name(),
                        "destination", t.destination().name(), "path", "/" + rel, "size", e.size(), "outcome",
                        "present");
                return own.get();
            }
            files.checkSize(t.folder().ownerSub(), e.size(), 0);
            String id = Hashing.newId("ret");
            r = new RemoteRetrieval(id, t.folder().id(), t.destination().id(), rel, e.size(), e.modifiedAt(),
                    RetrievalState.QUEUED, 0, "retrieved/" + t.folder().id() + "/" + id, false, null, null,
                    p.sub(), now, now, null, null, null);
            repo.insert(r);
        }
        RemoteRetrieval queued = r;
        String user = p.username();
        Thread.ofVirtual().name("retrieve-" + r.id()).start(() -> run(queued, t, user));
        return r;
    }

    /** A copy of this version that is ready or on its way; a ready one only while its storage object exists. */
    private Optional<RemoteRetrieval> present(Target t, String rel, RemoteFiles.RemoteEntry e) {
        for (RemoteRetrieval r : repo.forPath(t.folder().id(), t.destination().id(), rel)) {
            if (!sameVersion(r, e) || r.state() == RetrievalState.FAILED) {
                continue;
            }
            if (r.state() == RetrievalState.READY && !exists(r)) {
                discard(r);
                continue;
            }
            return Optional.of(r);
        }
        return Optional.empty();
    }

    /** The folder's own file, sent to this destination under this name and size and not yet deleted from fdrop. */
    private Optional<RemoteRetrieval> ownCopy(Target t, String rel, RemoteFiles.RemoteEntry e, Principal p, long now) {
        if (rel.contains("/")) {
            return Optional.empty();
        }
        for (ForwardJob j : records.sentTo(t.folder().id(), t.destination().id(), SENT_LIMIT)) {
            if (!j.fileName().equals(rel)) {
                continue;
            }
            Optional<UploadRecord> rec = records.currentFor(j.storageKey());
            boolean stillThere = j.size() == e.size() && rec.isPresent() && rec.get().id().equals(j.uploadRecordId())
                    && !Set.of(RecordStatus.DELETED, RecordStatus.EXPIRED, RecordStatus.MOVED)
                    .contains(rec.get().status())
                    && stat(j.storageKey()).filter(o -> o.size() == e.size()).isPresent();
            if (!stillThere) {
                return Optional.empty();
            }
            RemoteRetrieval r = new RemoteRetrieval(Hashing.newId("ret"), t.folder().id(), t.destination().id(), rel,
                    e.size(), e.modifiedAt(), RetrievalState.READY, e.size(), j.storageKey(), true, null, null,
                    p.sub(), now, now, now, null, now + keepFor);
            repo.insert(r);
            return Optional.of(r);
        }
        return Optional.empty();
    }

    private void run(RemoteRetrieval r, Target t, String user) {
        Semaphore slots = perDestination.computeIfAbsent(t.destination().id(),
                k -> new Semaphore(Math.max(1, cfg.maxConcurrent)));
        try {
            // waiting for a slot; touch the row now and then so the stale sweep leaves it alone
            while (!slots.tryAcquire(60, TimeUnit.SECONDS)) {
                repo.progress(r.id(), 0, System.currentTimeMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repo.fail(r.id(), "interrupted", System.currentTimeMillis());
            return;
        }
        try {
            if (!repo.start(r.id(), System.currentTimeMillis())) {
                return;
            }
            copy(r, t);
            long now = System.currentTimeMillis();
            users.addUsage(t.folder().ownerSub(), r.remoteSize());
            repo.ready(r.id(), r.remoteSize(), t.folder().ownerSub(), now, now + keepFor);
            metrics.remoteRetrieval(t.destination().id(), "ok");
            metrics.remoteBytes(t.destination().id(), r.remoteSize());
            Audit.log("forward.browse.retrieve", "user", user, "folder", t.folder().name(), "destination",
                    t.destination().name(), "path", "/" + r.remotePath(), "size", r.remoteSize(), "outcome", "ok");
        } catch (IOException | RuntimeException e) {
            repo.fail(r.id(), message(e), System.currentTimeMillis());
            metrics.remoteRetrieval(t.destination().id(), "error");
            Audit.log("forward.browse.retrieve", "user", user, "folder", t.folder().name(), "destination",
                    t.destination().name(), "path", "/" + r.remotePath(), "size", r.remoteSize(), "outcome",
                    "failed", "error", message(e));
            log.info("retrieval of {} from {} failed: {}", r.remotePath(), t.destination().name(), message(e));
        } finally {
            slots.release();
        }
    }

    private void copy(RemoteRetrieval r, Target t) throws IOException {
        WriteHandle w = storage.openWrite(StoragePath.of(r.storageKey()),
                new WriteOptions(null, true, r.remoteSize()));
        try {
            // not closed: closing the stream would close, and so abort, the write before it is committed
            OutputStream out = new Progress(Channels.newOutputStream(w), r);
            remote().copy(t.destination(), t.login(), t.root(), t.full(r.remotePath()), out);
            out.flush();
            if (w.written() != r.remoteSize()) {
                throw new IOException("the destination sent " + w.written() + " bytes, expected " + r.remoteSize()
                        + "; the file may be changing, try again");
            }
            w.commit();
        } finally {
            w.close();
        }
    }

    /** Counts bytes into the row about once a second, for the progress the tab shows. */
    private final class Progress extends FilterOutputStream {
        private final String id;
        private final long limit;
        private long bytes;
        private long reported = System.currentTimeMillis();

        Progress(OutputStream out, RemoteRetrieval r) {
            super(out);
            this.id = r.id();
            this.limit = r.remoteSize();
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            bytes += len;
            if (bytes > limit) {
                throw new IOException("the destination sent more than the " + limit + " bytes listed; the file may be"
                        + " changing, try again");
            }
            out.write(b, off, len);
            long now = System.currentTimeMillis();
            if (now - reported >= 1000) {
                reported = now;
                repo.progress(id, bytes, now);
            }
        }
    }

    public RemoteRetrieval retrieval(Principal p, String folderId, String retrievalId) {
        Folder f = folder(p, folderId);
        return repo.find(retrievalId).filter(r -> r.folderId().equals(f.id()))
                .orElseThrow(() -> FdropException.notFound("no such retrieval"));
    }

    /** A ready copy for download; downloading it restarts its keep time. */
    public Content content(Principal p, String folderId, String retrievalId) {
        RemoteRetrieval r = retrieval(p, folderId, retrievalId);
        ForwardDestination d = destinations.find(r.destinationId())
                .orElseThrow(() -> FdropException.notFound("no such destination"));
        if (!d.browsable()) {
            throw FdropException.forbidden("browsing is turned off for this destination");
        }
        if (r.state() != RetrievalState.READY) {
            throw FdropException.conflict("the file is not in fdrop yet: " + r.state().name().toLowerCase());
        }
        Optional<ObjectInfo> info = stat(r.storageKey());
        if (info.isEmpty()) {
            discard(r);
            throw FdropException.conflict("fdrop's copy has gone; get the file again");
        }
        long now = System.currentTimeMillis();
        repo.downloaded(r.id(), now, now + keepFor);
        String name = r.remotePath().substring(r.remotePath().lastIndexOf('/') + 1);
        return new Content(r, name, info.get());
    }

    public ReadableByteChannel openRead(Content c, long offset, long length) throws IOException {
        return storage.openRead(StoragePath.of(c.retrieval().storageKey()), offset, length);
    }

    // ---- housekeeping ----

    private void safeSweep() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("remote retrieval sweep failed: {}", e.toString());
        }
    }

    /** Fails retrievals no node is working on, and deletes copies past their keep time or of deleted folders. */
    public void sweep() {
        long now = System.currentTimeMillis();
        repo.failStale(now - STALE_AFTER, now);
        for (RemoteRetrieval r : repo.due(now, 500)) {
            discard(r);
        }
        cache.entrySet().removeIf(e -> now - e.getValue().at() > cacheFor);
    }

    /** Deletes a row with its copy, giving back the quota it took; a folder's own file is left alone. */
    private void discard(RemoteRetrieval r) {
        if (!r.ownCopy() && r.storageKey() != null) {
            try {
                storage.delete(StoragePath.of(r.storageKey()));
            } catch (IOException e) {
                log.debug("deleting retrieved copy {}: {}", r.storageKey(), e.toString());
            }
            if (r.state() == RetrievalState.READY && r.chargedSub() != null) {
                users.addUsage(r.chargedSub(), -r.remoteSize());
            }
        }
        repo.delete(r.id());
    }

    // ---- access ----

    private RemoteFiles remote() {
        RemoteFiles r = remote;
        if (r == null) {
            throw FdropException.unavailable("browsing destinations is not available on this server");
        }
        return r;
    }

    private Folder folder(Principal p, String folderId) {
        if (!p.canUseFiles()) {
            throw FdropException.forbidden("the standard role is required");
        }
        Folder f = folders.find(folderId).filter(x -> resolver.permission(p, x).isPresent())
                .orElseThrow(() -> FdropException.notFound("no such folder"));
        if (f.type() != FolderType.FORWARD) {
            throw FdropException.badRequest("not a forward folder");
        }
        return f;
    }

    /** The folder and destination, checked: visible to the caller, browsable, available, and fdrop's login. */
    private Target target(Principal p, String folderId, String destinationId) {
        Folder f = folder(p, folderId);
        if (!folders.destinations(f.id()).contains(destinationId)) {
            throw FdropException.notFound("this folder does not send to that destination");
        }
        ForwardDestination d = destinations.find(destinationId)
                .orElseThrow(() -> FdropException.notFound("no such destination"));
        if (!d.browsable()) {
            throw FdropException.forbidden("browsing is turned off for this destination");
        }
        if (!d.enabled()) {
            throw FdropException.conflict("not available: an admin has disabled this destination");
        }
        if (!destinations.isAssigned(d.id(), f.ownerSub())) {
            throw FdropException.conflict("not available: this destination is no longer assigned to the folder's"
                    + " owner");
        }
        hosts.check(d.host());
        Optional<ForwardClientKey> key = destinationService.usableKey(d.id());
        if (key.isEmpty() || destinationService.secrets() == null) {
            throw FdropException.conflict("not available: the destination has no active client key");
        }
        return new Target(f, d, new RemoteFiles.Login(destinationService.secrets().open(key.get().privateKeyEnc()),
                key.get().userCert()));
    }

    /**
     * A path below the remote directory, without leading or trailing slashes. {@code .} and {@code ..} are refused,
     * so a path can never name anything above the remote directory; the SFTP side also checks symbolic links.
     */
    static String clean(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String s : path.split("/")) {
            if (s.isEmpty()) {
                continue;
            }
            if (s.equals(".") || s.equals("..") || s.indexOf('\0') >= 0 || s.indexOf('\\') >= 0) {
                throw FdropException.badRequest("paths may not contain '.', '..' or backslashes");
            }
            parts.add(s);
        }
        return String.join("/", parts);
    }

    /** fdrop's own partial files on a destination, hidden from listings. */
    static boolean partial(String name) {
        return name.startsWith(".") && name.endsWith(".fdrop-part");
    }

    private static boolean sameVersion(RemoteRetrieval r, RemoteFiles.RemoteEntry e) {
        return r.remoteSize() == e.size() && (r.ownCopy() || r.remoteMtime() == e.modifiedAt());
    }

    private boolean exists(RemoteRetrieval r) {
        return stat(r.storageKey()).isPresent();
    }

    private Optional<ObjectInfo> stat(String key) {
        try {
            return storage.stat(StoragePath.of(key));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
    }
}
