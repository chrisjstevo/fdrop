package io.fdrop.sftp.fs;

import io.fdrop.core.auth.Principal;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.service.FileService;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.NotLinkException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * NIO provider for {@link FdropFileSystem}. Every call goes through {@link FileService} as the session's user, so
 * SFTP enforces exactly the same rules as the HTTP API, and fdrop errors map to the NIO exceptions MINA turns into
 * SFTP status codes.
 */
public final class FdropFileSystemProvider extends FileSystemProvider {

    private final FileService files;
    private final FdropMetrics metrics;

    public FdropFileSystemProvider(FileService files, FdropMetrics metrics) {
        this.files = files;
        this.metrics = metrics;
    }

    public FdropFileSystem newFileSystem(Principal p, String clientIp) {
        return new FdropFileSystem(this, p, files, clientIp);
    }

    static IOException toIo(FdropException e, String path) {
        FileSystemException fse = switch (e.status()) {
            case 404 -> new NoSuchFileException(path, null, e.getMessage());
            case 403, 401 -> new AccessDeniedException(path, null, e.getMessage());
            case 409 -> e.getMessage() != null && e.getMessage().contains("not empty")
                    ? new DirectoryNotEmptyException(path)
                    : new FileAlreadyExistsException(path, null, e.getMessage());
            default -> new FileSystemException(path, null, e.getMessage());
        };
        fse.initCause(e);
        return fse;
    }

    private static FdropPath p(Path path) {
        if (!(path instanceof FdropPath fp)) {
            throw new java.nio.file.ProviderMismatchException();
        }
        return fp;
    }

    private static FdropFileSystem fs(Path path) {
        return p(path).getFileSystem();
    }

    @Override
    public String getScheme() {
        return "fdrop";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        throw new UnsupportedOperationException("file systems are created per SFTP session");
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Path getPath(URI uri) {
        throw new UnsupportedOperationException();
    }

    FileService.Entry entry(Path path) throws IOException {
        FdropPath fp = p(path);
        if (fp.listed != null) {
            return fp.listed;
        }
        try {
            return files.stat(fs(path).principal, fp.virtual());
        } catch (FdropException e) {
            throw toIo(e, fp.virtual());
        }
    }

    // ---- channels ----

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        FdropPath fp = p(path);
        FdropFileSystem fs = fs(path);
        String v = fp.virtual();
        boolean write = options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND);
        try {
            if (!write) {
                return new ReadChannel(files, files.readTarget(fs.principal, v), metrics);
            }
            if (options.contains(StandardOpenOption.READ)) {
                throw new UnsupportedOperationException("files open for both reading and writing are not supported");
            }
            boolean createNew = options.contains(StandardOpenOption.CREATE_NEW);
            boolean create = createNew || options.contains(StandardOpenOption.CREATE);
            boolean truncate = options.contains(StandardOpenOption.TRUNCATE_EXISTING);
            boolean append = options.contains(StandardOpenOption.APPEND);
            Optional<FileService.Entry> existing = files.statIfExists(fs.principal, v);
            if (existing.isPresent() && existing.get().directory()) {
                throw new FileAlreadyExistsException(v, null, "a folder with that name exists");
            }
            if (existing.isEmpty() && !create) {
                throw new NoSuchFileException(v);
            }
            if (existing.isPresent() && createNew) {
                throw new FileAlreadyExistsException(v);
            }
            FileService.ReadTarget resume = null;
            if (existing.isPresent() && !truncate) {
                resume = files.readTarget(fs.principal, v);
            }
            FileService.FileWrite w = files.openWrite(fs.principal, v, -1, existing.isPresent(), Via.SFTP,
                    fs.clientIp);
            return new WriteChannel(files, w, resume, append, metrics);
        } catch (FdropException e) {
            throw toIo(e, v);
        }
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        FdropPath fp = p(dir);
        List<FileService.Entry> entries;
        try {
            FileService.Entry self = entry(dir);
            if (!self.directory()) {
                throw new NotDirectoryException(fp.virtual());
            }
            entries = files.list(fs(dir).principal, fp.virtual());
        } catch (FdropException e) {
            throw toIo(e, fp.virtual());
        }
        FdropPath base = (FdropPath) fp.toAbsolutePath().normalize();
        return new DirectoryStream<>() {
            private boolean used;

            @Override
            public Iterator<Path> iterator() {
                if (used) {
                    throw new IllegalStateException("iterator already obtained");
                }
                used = true;
                return entries.stream().<Path>map(e -> ((FdropPath) base.resolve(e.name())).withListed(e))
                        .filter(pth -> {
                            try {
                                return filter == null || filter.accept(pth);
                            } catch (IOException ex) {
                                return false;
                            }
                        }).iterator();
            }

            @Override
            public void close() {
                used = true;
            }
        };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        FdropPath fp = p(dir);
        try {
            files.mkdir(fs(dir).principal, fp.virtual());
        } catch (FdropException e) {
            throw toIo(e, fp.virtual());
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        FdropPath fp = p(path);
        try {
            files.delete(fs(path).principal, fp.virtual(), false);
        } catch (FdropException e) {
            throw toIo(e, fp.virtual());
        }
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        FdropFileSystem fs = fs(source);
        String from = p(source).virtual();
        String to = p(target).virtual();
        boolean replace = List.of(options).contains(StandardCopyOption.REPLACE_EXISTING);
        try {
            FileService.ReadTarget src = files.readTarget(fs.principal, from);
            try (FileService.FileWrite w = files.openWrite(fs.principal, to, src.info().size(), replace, Via.SFTP,
                    fs.clientIp); ReadableByteChannel in = files.openRead(src, 0, -1)) {
                ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
                while (in.read(buf) >= 0) {
                    buf.flip();
                    while (buf.hasRemaining()) {
                        w.write(buf);
                    }
                    buf.clear();
                }
                w.commit();
            }
        } catch (FdropException e) {
            throw toIo(e, to);
        }
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        FdropFileSystem fs = fs(source);
        String from = p(source).virtual();
        String to = p(target).virtual();
        try {
            if (List.of(options).contains(StandardCopyOption.REPLACE_EXISTING)) {
                Optional<FileService.Entry> existing = files.statIfExists(fs.principal, to);
                if (existing.isPresent() && !existing.get().directory()) {
                    files.delete(fs.principal, to, false);
                }
            }
            files.move(fs.principal, from, to);
        } catch (FdropException e) {
            throw toIo(e, from);
        }
    }

    @Override
    public boolean isSameFile(Path path, Path path2) {
        return p(path).virtual().equals(p(path2).virtual());
    }

    @Override
    public boolean isHidden(Path path) {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) {
        return fs(path).store();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        FileService.Entry e = entry(path);
        for (AccessMode m : modes) {
            if (m == AccessMode.WRITE && !e.writable()) {
                throw new AccessDeniedException(p(path).virtual());
            }
        }
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        throw new NotLinkException(link.toString());
    }

    // ---- attributes ----

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        if (type != BasicFileAttributeView.class && type != PosixFileAttributeView.class) {
            return null;
        }
        return (V) new PosixFileAttributeView() {
            @Override
            public String name() {
                return "posix";
            }

            @Override
            public PosixFileAttributes readAttributes() throws IOException {
                return FdropFileSystemProvider.this.readAttributes(path, PosixFileAttributes.class);
            }

            @Override
            public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) {
                // modification times are set by the server; accepted and ignored
            }

            @Override
            public void setPermissions(Set<PosixFilePermission> perms) {
                // permissions are derived from folder access; accepted and ignored
            }

            @Override
            public void setGroup(GroupPrincipal group) {
                // ignored
            }

            @Override
            public UserPrincipal getOwner() throws IOException {
                return readAttributes().owner();
            }

            @Override
            public void setOwner(UserPrincipal owner) {
                // ignored
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        if (type != BasicFileAttributes.class && type != PosixFileAttributes.class) {
            throw new UnsupportedOperationException("unsupported attributes " + type.getName());
        }
        return (A) new FdropAttributes(entry(path), fs(path).principal.username());
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        int colon = attributes.indexOf(':');
        String view = colon < 0 ? "basic" : attributes.substring(0, colon);
        String names = colon < 0 ? attributes : attributes.substring(colon + 1);
        if (!view.equals("basic") && !view.equals("posix") && !view.equals("owner")) {
            throw new UnsupportedOperationException("unsupported attribute view " + view);
        }
        FdropAttributes a = new FdropAttributes(entry(path), fs(path).principal.username());
        Map<String, Object> all = new HashMap<>();
        all.put("lastModifiedTime", a.lastModifiedTime());
        all.put("lastAccessTime", a.lastAccessTime());
        all.put("creationTime", a.creationTime());
        all.put("size", a.size());
        all.put("isRegularFile", a.isRegularFile());
        all.put("isDirectory", a.isDirectory());
        all.put("isSymbolicLink", false);
        all.put("isOther", false);
        all.put("fileKey", a.fileKey());
        if (!view.equals("basic")) {
            all.put("owner", a.owner());
        }
        if (view.equals("posix")) {
            all.put("permissions", a.permissions());
            all.put("group", a.group());
        }
        if (names.equals("*")) {
            return all;
        }
        Map<String, Object> out = new HashMap<>();
        for (String n : names.split(",")) {
            if (all.containsKey(n)) {
                out.put(n, all.get(n));
            }
        }
        return out;
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        // times, permissions and ownership are not settable; accepted and ignored so clients do not fail
    }
}
