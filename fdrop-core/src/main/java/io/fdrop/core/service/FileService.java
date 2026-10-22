package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.DestinationRepo;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.UploadRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.FolderForward;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.model.Records.User;
import io.fdrop.core.service.VirtualPath.Kind;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.storage.WriteHandle;
import io.fdrop.core.storage.WriteOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File operations over the virtual tree, shared by the HTTP API and the SFTP server so both enforce the same
 * rules: who can see and write what, quota, maximum file size, and what happens when an upload lands.
 */
public final class FileService {

    public enum EntryType { AREA, DIR, FILE, SHARED_FOLDER, FORWARD_FOLDER }

    /** One listing row as both protocols show it. */
    public record Entry(String name, String path, EntryType type, long size, long modified, boolean writable,
                        String folderId) {
        public boolean directory() {
            return type != EntryType.FILE;
        }
    }

    private final FdropConfig cfg;
    private final StorageBackend storage;
    private final PathResolver resolver;
    private final UserRepo users;
    private final FolderRepo folders;
    private final DestinationRepo destinations;
    private final UploadRepo uploads;
    private final RecordRepo records;
    private final UploadRecorder recorder;

    public FileService(FdropConfig cfg, StorageBackend storage, PathResolver resolver, UserRepo users,
                       FolderRepo folders, DestinationRepo destinations, UploadRepo uploads, RecordRepo records,
                       UploadRecorder recorder) {
        this.cfg = cfg;
        this.storage = storage;
        this.resolver = resolver;
        this.users = users;
        this.folders = folders;
        this.destinations = destinations;
        this.uploads = uploads;
        this.records = records;
        this.recorder = recorder;
    }

    public PathResolver resolver() {
        return resolver;
    }

    public StorageBackend storage() {
        return storage;
    }

    // ---- reading ----

    public List<Entry> list(Principal p, String path) {
        VirtualPath vp = resolver.resolve(p, path);
        try {
            return switch (vp.kind()) {
                case ROOT -> List.of(
                        new Entry("home", "/home", EntryType.AREA, 0, 0, true, null),
                        new Entry("shared", "/shared", EntryType.AREA, 0, 0, false, null),
                        new Entry("forward", "/forward", EntryType.AREA, 0, 0, false, null));
                case SHARED_ROOT, FORWARD_ROOT -> {
                    FolderType t = vp.kind() == Kind.SHARED_ROOT ? FolderType.SHARED : FolderType.FORWARD;
                    List<Entry> out = new ArrayList<>();
                    for (Folder f : resolver.visibleFolders(p, t)) {
                        boolean w = resolver.permission(p, f).map(x -> x.name().equals("WRITE")).orElse(false);
                        out.add(new Entry(f.name(), vp.virtual() + "/" + f.name(),
                                t == FolderType.SHARED ? EntryType.SHARED_FOLDER : EntryType.FORWARD_FOLDER, 0,
                                f.createdAt(), w, f.id()));
                    }
                    yield out;
                }
                default -> {
                    Optional<ObjectInfo> self = storage.stat(vp.key());
                    if (self.isEmpty() && !vp.isAreaRoot()) {
                        throw FdropException.notFound("no such folder: " + vp.virtual());
                    }
                    if (self.isPresent() && !self.get().directory()) {
                        throw FdropException.badRequest("not a folder: " + vp.virtual());
                    }
                    List<Entry> out = new ArrayList<>();
                    if (self.isEmpty()) {
                        yield out;
                    }
                    try (Stream<ObjectInfo> s = storage.list(vp.key(), false)) {
                        s.forEach(o -> out.add(entry(vp, o)));
                    }
                    out.sort(Comparator.comparing((Entry e) -> !e.directory()).thenComparing(e -> e.name().toLowerCase()));
                    yield out;
                }
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Entry stat(Principal p, String path) {
        VirtualPath vp = resolver.resolve(p, path);
        try {
            return switch (vp.kind()) {
                case ROOT -> new Entry("", "/", EntryType.AREA, 0, 0, false, null);
                case SHARED_ROOT -> new Entry("shared", "/shared", EntryType.AREA, 0, 0, false, null);
                case FORWARD_ROOT -> new Entry("forward", "/forward", EntryType.AREA, 0, 0, false, null);
                default -> {
                    if (vp.isAreaRoot()) {
                        EntryType t = switch (vp.kind()) {
                            case SHARED -> EntryType.SHARED_FOLDER;
                            case FORWARD -> EntryType.FORWARD_FOLDER;
                            default -> EntryType.AREA;
                        };
                        long mtime = vp.folder() == null ? 0 : vp.folder().createdAt();
                        yield new Entry(vp.name(), vp.virtual(), t, 0, mtime, vp.writable(),
                                vp.folder() == null ? null : vp.folder().id());
                    }
                    ObjectInfo o = storage.stat(vp.key())
                            .orElseThrow(() -> FdropException.notFound("no such file: " + vp.virtual()));
                    yield entry(vp, o);
                }
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Entry> statIfExists(Principal p, String path) {
        try {
            return Optional.of(stat(p, path));
        } catch (FdropException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private Entry entry(VirtualPath area, ObjectInfo o) {
        String vpath = PathResolver.virtualFor(area, o.path());
        long mtime = o.lastModified() == null ? 0 : o.lastModified().toEpochMilli();
        return new Entry(o.name(), vpath, o.directory() ? EntryType.DIR : EntryType.FILE, o.size(), mtime,
                area.writable(), null);
    }

    /** A file to read: its metadata plus the resolved path. */
    public record ReadTarget(VirtualPath path, ObjectInfo info) {
    }

    public ReadTarget readTarget(Principal p, String path) {
        VirtualPath vp = resolver.resolve(p, path);
        if (vp.isSynthetic() || vp.isAreaRoot()) {
            throw FdropException.badRequest("not a file: " + vp.virtual());
        }
        try {
            ObjectInfo o = storage.stat(vp.key()).orElseThrow(() -> FdropException.notFound("no such file: "
                    + vp.virtual()));
            if (o.directory()) {
                throw FdropException.badRequest("not a file: " + vp.virtual());
            }
            return new ReadTarget(vp, o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ReadableByteChannel openRead(ReadTarget t, long offset, long length) throws IOException {
        return storage.openRead(t.path().key(), offset, length);
    }

    // ---- writing ----

    /** Checks that a file may be written at {@code vp}, returning the existing object if any. */
    public Optional<ObjectInfo> checkWritable(Principal p, VirtualPath vp, boolean overwrite) {
        if (vp.isSynthetic() || vp.isAreaRoot()) {
            throw FdropException.forbidden("files cannot be created here: " + vp.virtual());
        }
        if (!vp.writable()) {
            throw FdropException.forbidden("you have read-only access to " + vp.parentVirtual());
        }
        if (vp.kind() == Kind.FORWARD) {
            requireForwardOpen(vp.folder());
        }
        try {
            StoragePath parent = vp.key().parent();
            if (!vp.parentVirtual().equals("/home") && !isAreaRootPath(vp)) {
                Optional<ObjectInfo> dir = storage.stat(parent);
                if (dir.isEmpty() || !dir.get().directory()) {
                    throw FdropException.notFound("no such folder: " + vp.parentVirtual());
                }
            }
            Optional<ObjectInfo> existing = storage.stat(vp.key());
            if (existing.isPresent()) {
                if (existing.get().directory()) {
                    throw FdropException.conflict("a folder with that name exists: " + vp.virtual());
                }
                if (!overwrite) {
                    throw FdropException.conflict("file exists: " + vp.virtual());
                }
            }
            return existing;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isAreaRootPath(VirtualPath vp) {
        String parent = vp.parentVirtual();
        long slashes = parent.chars().filter(c -> c == '/').count();
        return parent.equals("/home") || ((vp.kind() == Kind.SHARED || vp.kind() == Kind.FORWARD) && slashes == 2);
    }

    /** A forward folder only accepts files while its destination is enabled and assigned to its owner. */
    public void requireForwardOpen(Folder f) {
        FolderForward ff = folders.forward(f.id())
                .orElseThrow(() -> FdropException.conflict("forward folder has no destination"));
        ForwardDestination d = destinations.find(ff.destinationId())
                .orElseThrow(() -> FdropException.conflict("forward destination missing"));
        if (!d.enabled()) {
            throw FdropException.forbidden("forwarding to this destination is disabled; uploads are paused");
        }
        if (!destinations.isAssigned(d.id(), f.ownerSub())) {
            throw FdropException.forbidden("this forward folder's destination is no longer assigned to its owner;"
                    + " uploads are paused");
        }
    }

    /** Bytes the quota owner may still add, counting open upload sessions as used. */
    public long remainingQuota(String ownerSub) {
        User u = users.find(ownerSub).orElseThrow(() -> FdropException.notFound("unknown user"));
        return u.quotaBytes() - u.usedBytes() - uploads.reservedBytes(ownerSub);
    }

    public void checkSize(String ownerSub, long size, long replacing) {
        if (size > cfg.limits.maxFileSize.bytes()) {
            throw FdropException.tooLarge("file is larger than the " + cfg.limits.maxFileSize.bytes() + "-byte limit");
        }
        if (size - replacing > remainingQuota(ownerSub)) {
            throw FdropException.quota("not enough quota left for this file");
        }
    }

    /**
     * Opens a streaming write. {@code expectedSize} may be -1 when unknown (SFTP); quota and the size limit are
     * then enforced as bytes arrive.
     */
    public FileWrite openWrite(Principal p, String path, long expectedSize, boolean overwrite, Via via,
                               String clientIp) {
        VirtualPath vp = resolver.resolve(p, path);
        Optional<ObjectInfo> existing = checkWritable(p, vp, overwrite);
        long replacing = existing.map(ObjectInfo::size).orElse(0L);
        if (expectedSize >= 0) {
            checkSize(vp.quotaOwner(), expectedSize, replacing);
        }
        long limit = Math.min(cfg.limits.maxFileSize.bytes(), remainingQuota(vp.quotaOwner()) + replacing);
        try {
            WriteHandle h = storage.openWrite(vp.key(), new WriteOptions(null, overwrite, expectedSize));
            return new FileWrite(p, vp, h, limit, replacing, via, clientIp);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A write in progress; commit makes the file visible and records the upload. */
    public final class FileWrite implements AutoCloseable {
        private final Principal principal;
        private final VirtualPath vp;
        private final WriteHandle handle;
        private final long limit;
        private final long replacing;
        private final Via via;
        private final String clientIp;
        private String startIp;
        private boolean done;

        private FileWrite(Principal principal, VirtualPath vp, WriteHandle handle, long limit, long replacing, Via via,
                          String clientIp) {
            this.principal = principal;
            this.vp = vp;
            this.handle = handle;
            this.limit = limit;
            this.replacing = replacing;
            this.via = via;
            this.clientIp = clientIp;
        }

        public VirtualPath path() {
            return vp;
        }

        public void setStartIp(String ip) {
            this.startIp = ip;
        }

        public long written() {
            return handle.written();
        }

        public int write(ByteBuffer src) throws IOException {
            if (handle.written() + src.remaining() > limit) {
                abort();
                if (handle.written() + src.remaining() > cfg.limits.maxFileSize.bytes()) {
                    throw FdropException.tooLarge("file exceeds the maximum file size");
                }
                throw FdropException.quota("quota exceeded");
            }
            return handle.write(src);
        }

        public ObjectInfo commit() throws IOException {
            ObjectInfo info = handle.commit();
            done = true;
            users.addUsage(vp.quotaOwner(), info.size() - replacing);
            if (replacing > 0) {
                records.markDeleted(info.path().toString());
            }
            recorder.record(principal, vp, info, via, clientIp, startIp);
            return info;
        }

        public void abort() {
            done = true;
            handle.abort();
        }

        @Override
        public void close() {
            if (!done) {
                handle.abort();
            }
        }
    }

    // ---- folders, moves and deletes ----

    public Entry mkdir(Principal p, String path) {
        VirtualPath vp = resolver.resolve(p, path);
        if (vp.isSynthetic() || vp.isAreaRoot()) {
            throw FdropException.forbidden("folders cannot be created here; use the folders page for shared and"
                    + " forward folders");
        }
        if (vp.kind() == Kind.FORWARD) {
            throw FdropException.forbidden("forward folders accept files only");
        }
        if (!vp.writable()) {
            throw FdropException.forbidden("you have read-only access to " + vp.parentVirtual());
        }
        try {
            if (storage.stat(vp.key()).isPresent()) {
                throw FdropException.conflict("already exists: " + vp.virtual());
            }
            if (!isAreaRootPath(vp)) {
                Optional<ObjectInfo> parent = storage.stat(vp.key().parent());
                if (parent.isEmpty() || !parent.get().directory()) {
                    throw FdropException.notFound("no such folder: " + vp.parentVirtual());
                }
            } else {
                storage.mkdirs(vp.key().parent());
            }
            storage.mkdirs(vp.key());
            return new Entry(vp.name(), vp.virtual(), EntryType.DIR, 0, Instant.now().toEpochMilli(), true, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(Principal p, String path, boolean recursive) {
        VirtualPath vp = resolver.resolve(p, path);
        if (vp.isSynthetic() || vp.isAreaRoot()) {
            throw FdropException.forbidden("cannot delete " + vp.virtual());
        }
        if (!vp.writable()) {
            throw FdropException.forbidden("you have read-only access to " + vp.parentVirtual());
        }
        try {
            ObjectInfo o = storage.stat(vp.key()).orElseThrow(() -> FdropException.notFound("no such file: "
                    + vp.virtual()));
            long bytes = o.size();
            if (o.directory()) {
                try (Stream<ObjectInfo> s = storage.list(vp.key(), false)) {
                    if (!recursive && s.findAny().isPresent()) {
                        throw FdropException.conflict("folder is not empty: " + vp.virtual());
                    }
                }
                bytes = storage.usage(vp.key());
            }
            storage.delete(vp.key());
            users.addUsage(vp.quotaOwner(), -bytes);
            records.markDeleted(vp.key().toString());
            Audit.log("delete", "user", p.username(), "path", vp.virtual(), "bytes", bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Entry move(Principal p, String from, String to) {
        VirtualPath src = resolver.resolve(p, from);
        VirtualPath dst = resolver.resolve(p, to);
        for (VirtualPath v : List.of(src, dst)) {
            if (v.isSynthetic() || v.isAreaRoot()) {
                throw FdropException.forbidden("cannot move " + v.virtual() + "; rename folders on the folders page");
            }
            if (!v.writable()) {
                throw FdropException.forbidden("you have read-only access to " + v.parentVirtual());
            }
        }
        if (src.kind() == Kind.FORWARD || dst.kind() == Kind.FORWARD) {
            throw FdropException.forbidden("files cannot be moved into or out of forward folders");
        }
        if (dst.key().startsWith(src.key())) {
            throw FdropException.badRequest("cannot move a folder into itself");
        }
        try {
            ObjectInfo o = storage.stat(src.key()).orElseThrow(() -> FdropException.notFound("no such file: "
                    + src.virtual()));
            if (storage.stat(dst.key()).isPresent()) {
                throw FdropException.conflict("already exists: " + dst.virtual());
            }
            if (!isAreaRootPath(dst)) {
                Optional<ObjectInfo> parent = storage.stat(dst.key().parent());
                if (parent.isEmpty() || !parent.get().directory()) {
                    throw FdropException.notFound("no such folder: " + dst.parentVirtual());
                }
            }
            long bytes = o.directory() ? storage.usage(src.key()) : o.size();
            if (!src.quotaOwner().equals(dst.quotaOwner())) {
                checkSize(dst.quotaOwner(), bytes, 0);
            }
            storage.move(src.key(), dst.key());
            if (!src.quotaOwner().equals(dst.quotaOwner())) {
                users.addUsage(src.quotaOwner(), -bytes);
                users.addUsage(dst.quotaOwner(), bytes);
            }
            records.markMoved(src.key().toString(), dst.key().toString(), src.virtual(), dst.virtual());
            Audit.log("move", "user", p.username(), "from", src.virtual(), "to", dst.virtual());
            ObjectInfo moved = storage.stat(dst.key()).orElse(o);
            return entry(dst, moved);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
