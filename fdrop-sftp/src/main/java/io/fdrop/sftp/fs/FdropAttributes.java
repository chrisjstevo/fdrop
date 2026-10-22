package io.fdrop.sftp.fs;

import io.fdrop.core.service.FileService;

import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

/**
 * Attributes for an entry, with synthesised POSIX permissions: folders 0755 (0555 if read-only), files 0644
 * (0444 if read-only), owned by the session's user.
 */
final class FdropAttributes implements PosixFileAttributes {

    record Owner(String name) implements UserPrincipal, GroupPrincipal {
        @Override
        public String getName() {
            return name;
        }

        // SFTP v3 long listings print the principal's toString.
        @Override
        public String toString() {
            return name;
        }
    }

    private final FileService.Entry entry;
    private final Owner owner;

    FdropAttributes(FileService.Entry entry, String username) {
        this.entry = entry;
        this.owner = new Owner(username);
    }

    @Override
    public UserPrincipal owner() {
        return owner;
    }

    @Override
    public GroupPrincipal group() {
        return owner;
    }

    @Override
    public Set<PosixFilePermission> permissions() {
        String perms;
        if (entry.directory()) {
            perms = entry.writable() ? "rwxr-xr-x" : "r-xr-xr-x";
        } else {
            perms = entry.writable() ? "rw-r--r--" : "r--r--r--";
        }
        return PosixFilePermissions.fromString(perms);
    }

    @Override
    public FileTime lastModifiedTime() {
        return FileTime.fromMillis(entry.modified());
    }

    @Override
    public FileTime lastAccessTime() {
        return lastModifiedTime();
    }

    @Override
    public FileTime creationTime() {
        return lastModifiedTime();
    }

    @Override
    public boolean isRegularFile() {
        return !entry.directory();
    }

    @Override
    public boolean isDirectory() {
        return entry.directory();
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return entry.size();
    }

    @Override
    public Object fileKey() {
        return entry.path();
    }
}
