package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.db.Db;
import io.fdrop.core.db.DestinationRepo;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.SearchRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.UserKind;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.FolderForward;
import io.fdrop.core.model.Records.FolderMember;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.model.Records.User;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.util.Hashing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shared and forward folders: creation, members, ownership, forward settings and forward job status. */
public final class FolderService {

    /** A member as the owner sees them; {@code kind} tells people from application accounts. */
    public record MemberView(String username, Permission permission, UserKind kind) {
    }

    /** A folder as one user sees it. */
    public record FolderView(String id, FolderType type, String name, String path, String owner, String role,
                             String destinationId, String destinationName, Integer keepDays, boolean paused,
                             List<MemberView> members, long createdAt, String defaultExpiry, String maxExpiry) {
    }

    private final Db db;
    private final FolderRepo folders;
    private final DestinationRepo destinations;
    private final UserRepo users;
    private final RecordRepo records;
    private final StorageBackend storage;
    private final PathResolver resolver;
    private final ExpiryService expiry;
    private final SearchRepo search;

    public FolderService(Db db, FolderRepo folders, DestinationRepo destinations, UserRepo users, RecordRepo records,
                         StorageBackend storage, PathResolver resolver, ExpiryService expiry, SearchRepo search) {
        this.search = search;
        this.db = db;
        this.folders = folders;
        this.destinations = destinations;
        this.users = users;
        this.records = records;
        this.storage = storage;
        this.resolver = resolver;
        this.expiry = expiry;
    }

    public List<FolderView> list(Principal p) {
        requireUser(p);
        List<FolderView> out = new ArrayList<>();
        for (Folder f : folders.visibleTo(p.sub())) {
            out.add(view(p, f));
        }
        return out;
    }

    public FolderView get(Principal p, String id) {
        return view(p, visible(p, id));
    }

    private FolderView view(Principal p, Folder f) {
        Permission perm = resolver.permission(p, f).orElseThrow();
        boolean owner = f.ownerSub().equals(p.sub());
        String role = owner ? "OWNER" : perm.name();
        String ownerName = users.find(f.ownerSub()).map(User::username).orElse("(deleted user)");
        String destId = null;
        String destName = null;
        Integer keep = null;
        boolean paused = false;
        if (f.type() == FolderType.FORWARD) {
            Optional<FolderForward> ff = folders.forward(f.id());
            if (ff.isPresent()) {
                destId = ff.get().destinationId();
                keep = ff.get().keepDays();
                Optional<ForwardDestination> d = destinations.find(destId);
                destName = d.map(ForwardDestination::name).orElse(null);
                paused = d.isEmpty() || !d.get().enabled() || !destinations.isAssigned(destId, f.ownerSub());
            }
        }
        List<MemberView> members = new ArrayList<>();
        if (owner) {
            for (FolderMember m : folders.members(f.id())) {
                Optional<User> mu = users.find(m.userSub());
                members.add(new MemberView(mu.map(User::username).orElse(m.userSub()), m.permission(),
                        mu.map(User::kind).orElse(UserKind.USER)));
            }
        }
        String path = (f.type() == FolderType.SHARED ? "/shared/" : "/forward/") + f.name();
        var exp = expiry.folderSettings(f);
        return new FolderView(f.id(), f.type(), f.name(), path, ownerName, role, destId, destName, keep, paused,
                members, f.createdAt(), exp.defaultExpiry(), exp.maxExpiry());
    }

    public FolderView create(Principal p, FolderType type, String rawName, String destinationId, Integer keepDays) {
        requireUser(p);
        if (!p.isPerson()) {
            throw FdropException.forbidden("application accounts are members only and cannot create folders");
        }
        String name = Names.folderName(rawName);
        Folder f = new Folder(Hashing.newId("fld"), type, name, p.sub(), System.currentTimeMillis());
        if (type == FolderType.FORWARD) {
            requireAssignedDestination(p.sub(), destinationId);
        }
        try {
            db.inTransaction(() -> {
                folders.insert(f);
                if (type == FolderType.FORWARD) {
                    folders.putForward(new FolderForward(f.id(), destinationId, keepDays(keepDays)));
                }
                return null;
            });
        } catch (Db.DbException e) {
            if (e.isUniqueViolation()) {
                throw FdropException.conflict("a folder named '" + name + "' already exists");
            }
            throw e;
        }
        try {
            storage.mkdirs(StoragePath.of(f.storagePrefix()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Audit.log("folder.create", "user", p.username(), "type", type, "folder", name);
        return view(p, f);
    }

    private static int keepDays(Integer keepDays) {
        int k = keepDays == null ? 0 : keepDays;
        if (k < 0 || k > 3650) {
            throw FdropException.badRequest("keep days must be between 0 and 3650");
        }
        return k;
    }

    private ForwardDestination requireAssignedDestination(String sub, String destinationId) {
        if (destinationId == null || destinationId.isBlank()) {
            throw FdropException.badRequest("choose a destination");
        }
        ForwardDestination d = destinations.find(destinationId)
                .orElseThrow(() -> FdropException.badRequest("unknown destination"));
        if (!destinations.isAssigned(d.id(), sub)) {
            throw FdropException.forbidden("that destination is not assigned to you");
        }
        if (!d.enabled()) {
            throw FdropException.badRequest("that destination is disabled");
        }
        return d;
    }

    /** Rename and, for forward folders, destination and keep days. Owner only. */
    public FolderView update(Principal p, String id, String newName, String destinationId, Integer keepDays) {
        Folder f = owned(p, id);
        if (newName != null && !newName.equals(f.name())) {
            String name = Names.folderName(newName);
            try {
                folders.rename(f.id(), name);
            } catch (Db.DbException e) {
                if (e.isUniqueViolation()) {
                    throw FdropException.conflict("a folder named '" + name + "' already exists");
                }
                throw e;
            }
        }
        if (f.type() == FolderType.FORWARD && (destinationId != null || keepDays != null)) {
            FolderForward ff = folders.forward(f.id()).orElseThrow();
            String dest = destinationId == null ? ff.destinationId() : destinationId;
            if (destinationId != null && !destinationId.equals(ff.destinationId())) {
                requireAssignedDestination(p.sub(), destinationId);
            }
            folders.putForward(new FolderForward(f.id(), dest, keepDays == null ? ff.keepDays() : keepDays(keepDays)));
            // Pending jobs keep their original destination; a paused folder may resume on a new one.
            if (destinations.isAssigned(dest, f.ownerSub())) {
                records.pauseFolder(f.id(), false);
            }
        }
        return view(p, folders.find(f.id()).orElseThrow());
    }

    /**
     * Changes a shared folder's default and maximum expiry (owner only). A null argument leaves that setting alone;
     * blank or {@code never} clears it. Returns how many existing files were shortened.
     */
    public int updateExpiry(Principal p, String id, String defaultExpiry, String maxExpiry, boolean applyToExisting) {
        Folder f = owned(p, id);
        return expiry.updateFolder(p, f, defaultExpiry, maxExpiry, applyToExisting);
    }

    public void delete(Principal p, String id) {
        Folder f = owned(p, id);
        StoragePath prefix = StoragePath.of(f.storagePrefix());
        try {
            long bytes = storage.usage(prefix);
            storage.delete(prefix);
            users.addUsage(f.ownerSub(), -bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        records.markDeleted(prefix.toString());
        expiry.onDeleted(prefix);
        search.append(SearchRepo.Op.DELETE, prefix.toString(), null);
        for (ForwardJob j : records.jobsForFolder(f.id(), 100_000)) {
            if (j.state() != JobState.SENT && j.state() != JobState.FAILED) {
                records.updateJob(j.id(), JobState.FAILED, "folder deleted", 0, j.firstFailedAt(), null);
            }
        }
        folders.delete(f.id());
        Audit.log("folder.delete", "user", p.username(), "folder", f.name());
    }

    public FolderView putMember(Principal p, String id, String username, Permission perm) {
        Folder f = owned(p, id);
        User u = users.findByUsername(username)
                .orElseThrow(() -> FdropException.notFound("no user '" + username + "' (they must have logged in"
                        + " to fdrop once)"));
        if (u.sub().equals(f.ownerSub())) {
            throw FdropException.badRequest("the owner is already in the folder");
        }
        folders.putMember(f.id(), u.sub(), perm == null ? Permission.WRITE : perm);
        Audit.log("folder.member", "user", p.username(), "folder", f.name(), "member", username, "permission", perm);
        return view(p, f);
    }

    /** The owner removes a member, or a member leaves. */
    public void removeMember(Principal p, String id, String username) {
        Folder f = visible(p, id);
        User u = users.findByUsername(username).orElseThrow(() -> FdropException.notFound("no such member"));
        boolean self = u.sub().equals(p.sub());
        if (!self && !f.ownerSub().equals(p.sub())) {
            throw FdropException.forbidden("only the owner can remove other members");
        }
        if (self && f.ownerSub().equals(p.sub())) {
            throw FdropException.badRequest("transfer ownership before leaving your own folder");
        }
        if (!folders.removeMember(f.id(), u.sub())) {
            throw FdropException.notFound("not a member");
        }
        Audit.log("folder.member.remove", "user", p.username(), "folder", f.name(), "member", username);
    }

    public FolderView transferOwnership(Principal p, String id, String username) {
        Folder f = owned(p, id);
        User to = users.findByUsername(username).orElseThrow(() -> FdropException.notFound("no such user"));
        if (to.kind() == UserKind.APPLICATION) {
            throw FdropException.badRequest("application accounts cannot own folders");
        }
        if (folders.member(f.id(), to.sub()).isEmpty()) {
            throw FdropException.badRequest("ownership can only pass to a member");
        }
        if (f.type() == FolderType.FORWARD) {
            String dest = folders.forward(f.id()).orElseThrow().destinationId();
            if (!destinations.isAssigned(dest, to.sub())) {
                throw FdropException.badRequest(username + " is not assigned this folder's destination");
            }
        }
        transfer(f, to.sub());
        Audit.log("folder.transfer", "user", p.username(), "folder", f.name(), "to", username);
        return view(p, folders.find(f.id()).orElseThrow());
    }

    /** Moves ownership and the stored bytes' quota; the old owner stays as a read-write member. */
    public void transfer(Folder f, String newOwner) {
        long bytes;
        try {
            bytes = storage.usage(StoragePath.of(f.storagePrefix()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        db.inTransaction(() -> {
            folders.removeMember(f.id(), newOwner);
            folders.setOwner(f.id(), newOwner);
            folders.putMember(f.id(), f.ownerSub(), Permission.WRITE);
            users.addUsage(f.ownerSub(), -bytes);
            users.addUsage(newOwner, bytes);
            return null;
        });
    }

    public List<ForwardJob> jobs(Principal p, String id, int limit) {
        Folder f = visible(p, id);
        if (f.type() != FolderType.FORWARD) {
            throw FdropException.badRequest("not a forward folder");
        }
        return records.jobsForFolder(f.id(), limit);
    }

    public void retry(Principal p, String folderId, String jobId) {
        Folder f = owned(p, folderId);
        ForwardJob j = records.job(jobId).filter(x -> x.folderId().equals(f.id()))
                .orElseThrow(() -> FdropException.notFound("no such job"));
        if (j.state() != JobState.FAILED) {
            throw FdropException.conflict("only failed jobs can be retried");
        }
        records.updateJob(j.id(), JobState.QUEUED, null, 0, null, null);
        Audit.log("forward.retry", "user", p.username(), "folder", f.name(), "job", j.id());
    }

    private Folder visible(Principal p, String id) {
        requireUser(p);
        Folder f = folders.find(id).orElseThrow(() -> FdropException.notFound("no such folder"));
        if (resolver.permission(p, f).isEmpty()) {
            throw FdropException.notFound("no such folder");
        }
        return f;
    }

    private Folder owned(Principal p, String id) {
        Folder f = visible(p, id);
        if (!f.ownerSub().equals(p.sub())) {
            throw FdropException.forbidden("only the folder's owner can do that");
        }
        return f;
    }

    private static void requireUser(Principal p) {
        if (!p.canUseFiles()) {
            throw FdropException.forbidden("the standard role is required");
        }
    }
}
