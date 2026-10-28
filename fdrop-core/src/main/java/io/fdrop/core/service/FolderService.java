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

    /**
     * A folder as one user sees it. A forward folder lists its {@code destinations}; {@code destinationId} (the
     * first) and {@code destinationName} (all names, comma-separated) are kept for clients written for one.
     */
    public record FolderView(String id, FolderType type, String name, String path, String owner, String role,
                             String destinationId, String destinationName,
                             List<ForwardService.DestinationRef> destinations, Integer keepDays, boolean paused,
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
    private final ForwardService forwarding;

    public FolderService(Db db, FolderRepo folders, DestinationRepo destinations, UserRepo users, RecordRepo records,
                         StorageBackend storage, PathResolver resolver, ExpiryService expiry, SearchRepo search,
                         ForwardService forwarding) {
        this.search = search;
        this.forwarding = forwarding;
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
        List<ForwardService.DestinationRef> dests = null;
        Integer keep = null;
        boolean paused = false;
        if (f.type() == FolderType.FORWARD) {
            keep = folders.forward(f.id()).map(FolderForward::keepDays).orElse(0);
            dests = forwarding.destinationsOf(f);
            if (!dests.isEmpty()) {
                destId = dests.get(0).id();
                destName = String.join(", ", dests.stream().map(ForwardService.DestinationRef::name).toList());
            }
            paused = dests.isEmpty() || dests.stream().anyMatch(ForwardService.DestinationRef::paused);
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
        return new FolderView(f.id(), f.type(), f.name(), path, ownerName, role, destId, destName, dests, keep,
                paused, members, f.createdAt(), exp.defaultExpiry(), exp.maxExpiry());
    }

    /** Creates a shared folder, or a forward folder sending to {@code destinationIds} (one or more). */
    public FolderView create(Principal p, FolderType type, String rawName, List<String> destinationIds,
                             Integer keepDays) {
        requireUser(p);
        if (!p.isPerson()) {
            throw FdropException.forbidden("application accounts are members only and cannot create folders");
        }
        String name = Names.folderName(rawName);
        Folder f = new Folder(Hashing.newId("fld"), type, name, p.sub(), System.currentTimeMillis());
        List<String> dests = type == FolderType.FORWARD ? forwarding.validate(p.sub(), destinationIds, List.of())
                : List.of();
        int keep = keepDays(keepDays);
        try {
            db.inTransaction(() -> {
                folders.insert(f);
                if (type == FolderType.FORWARD) {
                    folders.putForward(new FolderForward(f.id(), keep));
                    folders.setDestinations(f.id(), dests, p.sub());
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
        Audit.log("folder.create", "user", p.username(), "type", type, "folder", name, "destinations", dests);
        return view(p, f);
    }

    private static int keepDays(Integer keepDays) {
        int k = keepDays == null ? 0 : keepDays;
        if (k < 0 || k > 3650) {
            throw FdropException.badRequest("keep days must be between 0 and 3650");
        }
        return k;
    }

    /**
     * Rename and, for forward folders, destinations and keep days. Owner only. {@code destinationIds} null leaves
     * the destinations alone; added ones get new files, and with {@code sendExisting} also the files still in the
     * folder. Jobs already queued to a removed destination keep running.
     */
    public FolderView update(Principal p, String id, String newName, List<String> destinationIds,
                             boolean sendExisting, Integer keepDays) {
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
        if (f.type() == FolderType.FORWARD && keepDays != null) {
            folders.putForward(new FolderForward(f.id(), keepDays(keepDays)));
        }
        if (f.type() == FolderType.FORWARD && destinationIds != null) {
            List<String> before = folders.destinations(f.id());
            List<String> after = forwarding.validate(f.ownerSub(), destinationIds, before);
            List<String> added = after.stream().filter(d -> !before.contains(d)).toList();
            List<String> removed = before.stream().filter(d -> !after.contains(d)).toList();
            if (!added.isEmpty() || !removed.isEmpty()) {
                folders.setDestinations(f.id(), after, p.sub());
                Audit.log("folder.destinations", "user", p.username(), "folder", f.name(), "added", added,
                        "removed", removed);
            }
            if (sendExisting && !added.isEmpty()) {
                forwarding.sendExisting(p, f, added);
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
        // the files are gone, so nothing is left to send; finished jobs keep their history
        records.cancelOpenJobs(f.id(), p.sub(), "folder deleted");
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
            for (String dest : folders.destinations(f.id())) {
                if (!destinations.isAssigned(dest, to.sub())) {
                    throw FdropException.badRequest(username + " is not assigned every one of this folder's"
                            + " destinations (missing '" + forwarding.destinationName(dest) + "')");
                }
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
