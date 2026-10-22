package io.fdrop.storage.local;

import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.storage.MultipartUpload;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.PartInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.storage.WriteHandle;
import io.fdrop.core.storage.WriteOptions;
import io.fdrop.core.util.Hashing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stores objects as plain files under {@code storage.local.root}. Writes go to {@code .fdrop-tmp/}, are fsynced and
 * moved into place atomically; multipart parts live in {@code .fdrop-parts/<uploadId>/} until assembly. Those two
 * directories sit at the root, beside {@code users/} and {@code folders/}, so no user path can reach them.
 */
public final class LocalStorageBackend implements StorageBackend {

    private static final String TMP = ".fdrop-tmp";
    private static final String PARTS = ".fdrop-parts";
    private static final Pattern UPLOAD_ID = Pattern.compile("[0-9a-f-]{36}");

    private Path root;
    private Path tmp;
    private Path parts;

    @Override
    public String type() {
        return "local";
    }

    @Override
    public void init(FdropConfig config) throws IOException {
        init(Path.of(config.storage.local.root));
    }

    public void init(Path rootDir) throws IOException {
        Files.createDirectories(rootDir);
        this.root = rootDir.toRealPath();
        this.tmp = Files.createDirectories(root.resolve(TMP));
        this.parts = Files.createDirectories(root.resolve(PARTS));
    }

    /** Maps a key to a file under the root, refusing anything that would leave it or pass through a symlink. */
    Path resolve(StoragePath key) {
        Path p = root;
        for (String seg : key.segments()) {
            p = p.resolve(seg);
            if (Files.isSymbolicLink(p)) {
                throw FdropException.forbidden("symbolic links are not followed");
            }
        }
        Path n = p.normalize();
        if (!n.startsWith(root)) {
            throw FdropException.forbidden("path escapes the storage root");
        }
        if (!key.isRoot() && (key.segments().get(0).equals(TMP) || key.segments().get(0).equals(PARTS))) {
            throw FdropException.forbidden("reserved path");
        }
        return n;
    }

    private StoragePath keyOf(Path file) {
        Path rel = root.relativize(file);
        List<String> segs = new ArrayList<>();
        rel.forEach(s -> segs.add(s.toString()));
        return StoragePath.ofSegments(segs);
    }

    private static ObjectInfo info(StoragePath key, Path file, BasicFileAttributes a) {
        if (a.isDirectory()) {
            return ObjectInfo.directory(key, a.lastModifiedTime().toInstant());
        }
        return new ObjectInfo(key, a.size(), a.lastModifiedTime().toInstant(),
                a.size() + "-" + a.lastModifiedTime().toMillis(), null, false);
    }

    @Override
    public Optional<ObjectInfo> stat(StoragePath path) throws IOException {
        Path f = resolve(path);
        try {
            BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || a.isOther()) {
                return Optional.empty();
            }
            return Optional.of(info(path, f, a));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public Stream<ObjectInfo> list(StoragePath dir, boolean recursive) throws IOException {
        Path d = resolve(dir);
        if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
            return Stream.empty();
        }
        Stream<Path> paths = recursive ? Files.walk(d).filter(p -> !p.equals(d)) : Files.list(d);
        return paths.filter(p -> !Files.isSymbolicLink(p))
                .filter(p -> !(dir.isRoot() && (p.getFileName().toString().equals(TMP)
                        || p.getFileName().toString().equals(PARTS))))
                .map(p -> {
                    try {
                        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        return info(keyOf(p), p, a);
                    } catch (NoSuchFileException e) {
                        return null;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .filter(o -> o != null);
    }

    @Override
    public ReadableByteChannel openRead(StoragePath path, long offset, long length) throws IOException {
        Path f = resolve(path);
        if (Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS)) {
            throw FdropException.badRequest("not a file");
        }
        FileChannel ch;
        try {
            ch = FileChannel.open(f, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw FdropException.notFound("no such file");
        }
        ch.position(offset);
        if (length < 0) {
            return ch;
        }
        return new LimitedChannel(ch, length);
    }

    /** The open file channel behind a read, for zero-copy sends; null when not a plain local file. */
    public static FileChannel fileChannel(ReadableByteChannel ch) {
        if (ch instanceof FileChannel fc) {
            return fc;
        }
        if (ch instanceof LimitedChannel lc) {
            return lc.inner;
        }
        return null;
    }

    @Override
    public WriteHandle openWrite(StoragePath path, WriteOptions opts) throws IOException {
        Path target = resolve(path);
        if (!opts.overwrite() && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw FdropException.conflict("file exists");
        }
        Path t = tmp.resolve(UUID.randomUUID() + ".tmp");
        FileChannel ch = FileChannel.open(t, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new LocalWrite(path, target, t, ch, opts.overwrite());
    }

    private final class LocalWrite implements WriteHandle {
        private final StoragePath key;
        private final Path target;
        private final Path temp;
        private final FileChannel ch;
        private final boolean overwrite;
        private long written;
        private boolean finished;

        LocalWrite(StoragePath key, Path target, Path temp, FileChannel ch, boolean overwrite) {
            this.key = key;
            this.target = target;
            this.temp = temp;
            this.ch = ch;
            this.overwrite = overwrite;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            int n = ch.write(src);
            written += n;
            return n;
        }

        @Override
        public long written() {
            return written;
        }

        @Override
        public boolean isOpen() {
            return !finished;
        }

        @Override
        public ObjectInfo commit() throws IOException {
            if (finished) {
                throw new IllegalStateException("already finished");
            }
            try {
                ch.force(true);
                ch.close();
                publish(temp, target, overwrite);
                finished = true;
                return stat(key).orElseThrow();
            } catch (IOException | RuntimeException e) {
                abort();
                throw e;
            }
        }

        @Override
        public void abort() {
            finished = true;
            try {
                ch.close();
            } catch (IOException ignored) {
                // closing a temp file we are about to delete
            }
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // the tmp directory is swept at startup
            }
        }

        @Override
        public void close() {
            if (!finished) {
                abort();
            }
        }
    }

    /** Moves a finished temp file into place atomically. */
    private void publish(Path temp, Path target, boolean overwrite) throws IOException {
        Files.createDirectories(target.getParent());
        if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw FdropException.conflict("file exists");
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw FdropException.conflict("a folder with that name exists");
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void delete(StoragePath path) throws IOException {
        if (path.isRoot()) {
            throw FdropException.forbidden("cannot delete the storage root");
        }
        Path f = resolve(path);
        if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        deleteTree(f);
    }

    private static void deleteTree(Path f) throws IOException {
        Files.walkFileTree(f, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                try {
                    Files.deleteIfExists(dir);
                } catch (DirectoryNotEmptyException e) {
                    deleteTree(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void move(StoragePath src, StoragePath dst) throws IOException {
        Path s = resolve(src);
        Path d = resolve(dst);
        if (!Files.exists(s, LinkOption.NOFOLLOW_LINKS)) {
            throw FdropException.notFound("no such file");
        }
        if (Files.exists(d, LinkOption.NOFOLLOW_LINKS)) {
            throw FdropException.conflict("target exists");
        }
        Files.createDirectories(d.getParent());
        try {
            Files.move(s, d, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            throw FdropException.conflict("target exists");
        }
    }

    @Override
    public void mkdirs(StoragePath dir) throws IOException {
        Path d = resolve(dir);
        if (Files.exists(d, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
            throw FdropException.conflict("a file with that name exists");
        }
        Files.createDirectories(d);
    }

    // ---- multipart ----

    @Override
    public MultipartUpload beginMultipart(StoragePath target, WriteOptions opts) throws IOException {
        resolve(target);
        String id = UUID.randomUUID().toString();
        Files.createDirectories(parts.resolve(id));
        return new LocalMultipart(id, target, opts.overwrite());
    }

    @Override
    public MultipartUpload resumeMultipart(StoragePath target, String uploadId) throws IOException {
        if (!UPLOAD_ID.matcher(uploadId).matches()) {
            throw FdropException.badRequest("bad upload id");
        }
        resolve(target);
        if (!Files.isDirectory(parts.resolve(uploadId))) {
            throw FdropException.notFound("upload parts are gone");
        }
        return new LocalMultipart(uploadId, target, true);
    }

    private final class LocalMultipart implements MultipartUpload {
        private final String id;
        private final StoragePath target;
        private final boolean overwrite;
        private final Path dir;

        LocalMultipart(String id, StoragePath target, boolean overwrite) {
            this.id = id;
            this.target = target;
            this.overwrite = overwrite;
            this.dir = parts.resolve(id);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public StoragePath target() {
            return target;
        }

        @Override
        public PartInfo putPart(int partNumber, ReadableByteChannel src, long length, String sha256Hex)
                throws IOException {
            Path temp = dir.resolve(partNumber + ".part.tmp-" + UUID.randomUUID());
            Hashing.DigestingChannel in = new Hashing.DigestingChannel(src, length);
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.allocateDirect(256 * 1024);
                while (in.count() < length) {
                    buf.clear();
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    buf.flip();
                    while (buf.hasRemaining()) {
                        out.write(buf);
                    }
                }
                if (in.count() != length) {
                    throw new IOException("part " + partNumber + " is short: " + in.count() + " of " + length);
                }
                String hex = in.hex();
                if (sha256Hex != null && !sha256Hex.equalsIgnoreCase(hex)) {
                    throw new IOException("part " + partNumber + " SHA-256 mismatch");
                }
                out.force(true);
                Path finalPath = dir.resolve(partNumber + ".part");
                Files.move(temp, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return new PartInfo(partNumber, length, hex, hex);
            } finally {
                Files.deleteIfExists(temp);
            }
        }

        @Override
        public List<PartInfo> listParts() throws IOException {
            try (Stream<Path> s = Files.list(dir)) {
                return s.filter(p -> p.getFileName().toString().endsWith(".part")).map(p -> {
                    String n = p.getFileName().toString();
                    try {
                        return new PartInfo(Integer.parseInt(n.substring(0, n.length() - 5)), Files.size(p), null,
                                null);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }).sorted(Comparator.comparingInt(PartInfo::partNumber)).toList();
            }
        }

        @Override
        public ObjectInfo complete(List<PartInfo> partList) throws IOException {
            Path target = resolve(this.target);
            Path temp = tmp.resolve(UUID.randomUUID() + ".assembly");
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                for (PartInfo p : partList) {
                    Path part = dir.resolve(p.partNumber() + ".part");
                    try (FileChannel in = FileChannel.open(part, StandardOpenOption.READ)) {
                        long size = in.size();
                        if (p.size() >= 0 && size != p.size()) {
                            throw new IOException("part " + p.partNumber() + " has " + size + " bytes, expected "
                                    + p.size());
                        }
                        long pos = 0;
                        while (pos < size) {
                            pos += in.transferTo(pos, size - pos, out);
                        }
                    } catch (NoSuchFileException e) {
                        throw new IOException("part " + p.partNumber() + " is missing", e);
                    }
                }
                out.force(true);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            try {
                publish(temp, target, overwrite);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            abort();
            return stat(this.target).orElseThrow();
        }

        @Override
        public void abort() {
            try {
                if (Files.exists(dir)) {
                    deleteTree(dir);
                }
            } catch (IOException ignored) {
                // left for the next startup sweep
            }
        }
    }

    @Override
    public void close() {
        // nothing held open
    }

    /** Stops reading after {@code limit} bytes. */
    private static final class LimitedChannel implements ReadableByteChannel {
        private final FileChannel inner;
        private long remaining;

        LimitedChannel(FileChannel inner, long limit) {
            this.inner = inner;
            this.remaining = limit;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int max = (int) Math.min(dst.remaining(), remaining);
            ByteBuffer view = dst.slice(dst.position(), max);
            int n = inner.read(view);
            if (n > 0) {
                dst.position(dst.position() + n);
                remaining -= n;
            }
            return n;
        }

        @Override
        public boolean isOpen() {
            return inner.isOpen();
        }

        @Override
        public void close() throws IOException {
            inner.close();
        }
    }
}
