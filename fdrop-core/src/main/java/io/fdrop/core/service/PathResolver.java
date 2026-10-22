package io.fdrop.core.service;

import io.fdrop.core.auth.Principal;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.FolderMember;
import io.fdrop.core.service.VirtualPath.Kind;
import io.fdrop.core.storage.StoragePath;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the user-visible tree ({@code /home}, {@code /shared/<name>}, {@code /forward/<name>}) to storage keys,
 * and is the single place that decides who may reach what. Paths a user may not see resolve as "not found", not
 * "forbidden", so folder names never leak. Membership is read on every call, so removing a member takes effect on
 * their next request, including in an open SFTP session.
 */
public final class PathResolver {

    private final FolderRepo folders;

    public PathResolver(FolderRepo folders) {
        this.folders = folders;
    }

    public static StoragePath homeKey(String sub) {
        return StoragePath.of("users/" + sub);
    }

    public VirtualPath resolve(Principal p, String path) {
        if (!p.canUseFiles()) {
            throw FdropException.forbidden("the standard role is required to use files");
        }
        StoragePath parsed = StoragePath.of(path == null ? "/" : path);
        List<String> segs = parsed.segments();
        if (segs.isEmpty()) {
            return new VirtualPath(Kind.ROOT, "/", null, null, Permission.READ, null);
        }
        String area = segs.get(0);
        String virtual = "/" + parsed;
        switch (area) {
            case "home" -> {
                StoragePath key = homeKey(p.sub()).resolve(StoragePath.ofSegments(segs.subList(1, segs.size())));
                return new VirtualPath(Kind.HOME, virtual, null, key, Permission.WRITE, p.sub());
            }
            case "shared", "forward" -> {
                FolderType type = area.equals("shared") ? FolderType.SHARED : FolderType.FORWARD;
                if (segs.size() == 1) {
                    return new VirtualPath(type == FolderType.SHARED ? Kind.SHARED_ROOT : Kind.FORWARD_ROOT, virtual,
                            null, null, Permission.READ, null);
                }
                Folder f = folders.findByName(type, segs.get(1))
                        .orElseThrow(() -> FdropException.notFound("no such folder: " + virtual));
                Permission perm = permission(p, f)
                        .orElseThrow(() -> FdropException.notFound("no such folder: " + virtual));
                List<String> rest = segs.subList(2, segs.size());
                if (type == FolderType.FORWARD && rest.size() > 1) {
                    throw FdropException.notFound("forward folders have no sub-folders");
                }
                StoragePath key = StoragePath.of(f.storagePrefix()).resolve(StoragePath.ofSegments(rest));
                // Show the folder's canonical name even if the request used other letter case.
                String canonical = "/" + area + "/" + f.name() + (rest.isEmpty() ? "" : "/" + String.join("/", rest));
                return new VirtualPath(type == FolderType.SHARED ? Kind.SHARED : Kind.FORWARD, canonical, f, key,
                        perm, f.ownerSub());
            }
            default -> throw FdropException.notFound("no such path: " + virtual);
        }
    }

    /** The user's permission in a folder, or empty if they may not see it. Owners always have WRITE. */
    public Optional<Permission> permission(Principal p, Folder f) {
        if (f.ownerSub().equals(p.sub())) {
            return Optional.of(Permission.WRITE);
        }
        return folders.member(f.id(), p.sub()).map(FolderMember::permission);
    }

    /** The user-visible path for a storage key in one of the user's areas. */
    public static String virtualFor(VirtualPath area, StoragePath key) {
        StoragePath areaRoot = switch (area.kind()) {
            case HOME -> StoragePath.of(area.key().segments().subList(0, 2).stream().reduce((a, b) -> a + "/" + b)
                    .orElseThrow());
            case SHARED, FORWARD -> StoragePath.of(area.folder().storagePrefix());
            default -> throw new IllegalArgumentException("not a file area: " + area.kind());
        };
        String base = switch (area.kind()) {
            case HOME -> "/home";
            case SHARED -> "/shared/" + area.folder().name();
            default -> "/forward/" + area.folder().name();
        };
        StoragePath rel = key.relativize(areaRoot);
        return rel.isRoot() ? base : base + "/" + rel;
    }

    public List<Folder> visibleFolders(Principal p, FolderType type) {
        return new ArrayList<>(folders.visibleTo(p.sub(), type));
    }
}
