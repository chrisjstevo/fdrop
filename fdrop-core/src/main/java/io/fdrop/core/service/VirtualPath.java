package io.fdrop.core.service;

import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.WentTo;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.storage.StoragePath;

/**
 * A user-visible path resolved against what the user may see.
 *
 * @param kind       which part of the tree the path is in
 * @param virtual    the normalised user-visible path, e.g. {@code /shared/Invoices/a.pdf}
 * @param folder     the shared or forward folder, or null
 * @param key        the storage key for files and folders, or null for the synthetic roots
 * @param permission what the user may do here
 * @param quotaOwner whose quota bytes written here count against
 */
public record VirtualPath(Kind kind, String virtual, Folder folder, StoragePath key, Permission permission,
                          String quotaOwner) {

    public enum Kind {
        /** {@code /} */
        ROOT,
        /** {@code /shared}: lists shared folders */
        SHARED_ROOT,
        /** {@code /forward}: lists forward folders */
        FORWARD_ROOT,
        /** {@code /home} and below */
        HOME,
        /** a shared folder and below */
        SHARED,
        /** a forward folder and below */
        FORWARD
    }

    public boolean isSynthetic() {
        return kind == Kind.ROOT || kind == Kind.SHARED_ROOT || kind == Kind.FORWARD_ROOT;
    }

    /** True for {@code /home}, {@code /shared/X} or {@code /forward/X} themselves. */
    public boolean isAreaRoot() {
        return switch (kind) {
            case HOME -> virtual.equals("/home");
            case SHARED, FORWARD -> virtual.chars().filter(c -> c == '/').count() == 2;
            default -> false;
        };
    }

    public boolean writable() {
        return !isSynthetic() && permission == Permission.WRITE;
    }

    public WentTo wentTo() {
        return switch (kind) {
            case SHARED -> WentTo.SHARED;
            case FORWARD -> WentTo.FORWARD;
            default -> WentTo.HOME;
        };
    }

    public String name() {
        int i = virtual.lastIndexOf('/');
        return virtual.substring(i + 1);
    }

    public String parentVirtual() {
        int i = virtual.lastIndexOf('/');
        return i <= 0 ? "/" : virtual.substring(0, i);
    }
}
