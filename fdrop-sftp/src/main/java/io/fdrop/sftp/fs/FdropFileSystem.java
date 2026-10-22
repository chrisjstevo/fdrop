package io.fdrop.sftp.fs;

import io.fdrop.core.auth.Principal;
import io.fdrop.core.service.FileService;

import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One SFTP session's view of fdrop: {@code /home}, {@code /shared} and {@code /forward}, resolved through
 * {@link FileService} as the session's user. Nothing outside that tree exists here, so there is nothing to escape
 * to.
 */
public final class FdropFileSystem extends FileSystem {

    private final FdropFileSystemProvider provider;
    final Principal principal;
    final FileService files;
    final String clientIp;
    private volatile boolean open = true;

    FdropFileSystem(FdropFileSystemProvider provider, Principal principal, FileService files, String clientIp) {
        this.provider = provider;
        this.principal = principal;
        this.files = files;
        this.clientIp = clientIp;
    }

    public Principal principal() {
        return principal;
    }

    @Override
    public FdropFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return List.of(getPath("/"));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return List.of(store());
    }

    FileStore store() {
        return new FileStore() {
            @Override
            public String name() {
                return "fdrop";
            }

            @Override
            public String type() {
                return "fdrop";
            }

            @Override
            public boolean isReadOnly() {
                return false;
            }

            @Override
            public long getTotalSpace() {
                return Long.MAX_VALUE;
            }

            @Override
            public long getUsableSpace() {
                return Math.max(0, files.remainingQuota(principal.sub()));
            }

            @Override
            public long getUnallocatedSpace() {
                return getUsableSpace();
            }

            @Override
            public boolean supportsFileAttributeView(Class<? extends java.nio.file.attribute.FileAttributeView> type) {
                return type == java.nio.file.attribute.BasicFileAttributeView.class
                        || type == java.nio.file.attribute.PosixFileAttributeView.class;
            }

            @Override
            public boolean supportsFileAttributeView(String name) {
                return supportedFileAttributeViews().contains(name);
            }

            @Override
            public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
                return null;
            }

            @Override
            public Object getAttribute(String attribute) {
                return null;
            }
        };
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic", "posix", "owner");
    }

    @Override
    public FdropPath getPath(String first, String... more) {
        return FdropPath.parse(this, first, more);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        int colon = syntaxAndPattern.indexOf(':');
        String syntax = syntaxAndPattern.substring(0, colon);
        String pattern = syntaxAndPattern.substring(colon + 1);
        Pattern p = syntax.equals("regex") ? Pattern.compile(pattern)
                : Pattern.compile(pattern.replace(".", "\\.").replace("**", "\u0000").replace("*", "[^/]*")
                .replace("\u0000", ".*").replace("?", "."));
        return path -> p.matcher(path.toString()).matches();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return new UserPrincipalLookupService() {
            @Override
            public java.nio.file.attribute.UserPrincipal lookupPrincipalByName(String name) {
                return new FdropAttributes.Owner(name);
            }

            @Override
            public java.nio.file.attribute.GroupPrincipal lookupPrincipalByGroupName(String group) {
                return new FdropAttributes.Owner(group);
            }
        };
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("watching is not supported");
    }
}
