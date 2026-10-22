package io.fdrop.sftp.fs;

import io.fdrop.core.service.FileService;

import java.net.URI;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A path in a user's virtual tree. Paths are always interpreted against {@code /}. */
public final class FdropPath implements Path {

    private final FdropFileSystem fs;
    private final boolean absolute;
    private final List<String> names;
    /** Attributes captured from a directory listing, to spare a lookup per entry. */
    final FileService.Entry listed;

    FdropPath(FdropFileSystem fs, boolean absolute, List<String> names, FileService.Entry listed) {
        this.fs = fs;
        this.absolute = absolute;
        this.names = names;
        this.listed = listed;
    }

    static FdropPath parse(FdropFileSystem fs, String first, String... more) {
        StringBuilder sb = new StringBuilder(first);
        for (String m : more) {
            if (!m.isEmpty()) {
                sb.append('/').append(m);
            }
        }
        String s = sb.toString();
        List<String> parts = new ArrayList<>();
        for (String p : s.split("/")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        return new FdropPath(fs, s.startsWith("/"), Collections.unmodifiableList(parts), null);
    }

    FdropPath withListed(FileService.Entry e) {
        return new FdropPath(fs, absolute, names, e);
    }

    /** The absolute, normalised path string FileService understands. */
    String virtual() {
        FdropPath n = (FdropPath) toAbsolutePath().normalize();
        return n.names.isEmpty() ? "/" : "/" + String.join("/", n.names);
    }

    @Override
    public FdropFileSystem getFileSystem() {
        return fs;
    }

    @Override
    public boolean isAbsolute() {
        return absolute;
    }

    @Override
    public Path getRoot() {
        return absolute ? new FdropPath(fs, true, List.of(), null) : null;
    }

    @Override
    public Path getFileName() {
        return names.isEmpty() ? null : new FdropPath(fs, false, List.of(names.get(names.size() - 1)), null);
    }

    @Override
    public Path getParent() {
        if (names.isEmpty()) {
            return null;
        }
        if (names.size() == 1) {
            return absolute ? getRoot() : null;
        }
        return new FdropPath(fs, absolute, names.subList(0, names.size() - 1), null);
    }

    @Override
    public int getNameCount() {
        return names.size();
    }

    @Override
    public Path getName(int index) {
        return new FdropPath(fs, false, List.of(names.get(index)), null);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return new FdropPath(fs, false, names.subList(beginIndex, endIndex), null);
    }

    @Override
    public boolean startsWith(Path other) {
        FdropPath o = check(other);
        return o.absolute == absolute && o.names.size() <= names.size()
                && names.subList(0, o.names.size()).equals(o.names);
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(fs.getPath(other));
    }

    @Override
    public boolean endsWith(Path other) {
        FdropPath o = check(other);
        if (o.absolute) {
            return equals(o);
        }
        return o.names.size() <= names.size()
                && names.subList(names.size() - o.names.size(), names.size()).equals(o.names);
    }

    @Override
    public boolean endsWith(String other) {
        return endsWith(fs.getPath(other));
    }

    @Override
    public Path normalize() {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (n.equals(".")) {
                continue;
            }
            if (n.equals("..")) {
                if (!out.isEmpty() && !out.get(out.size() - 1).equals("..")) {
                    out.remove(out.size() - 1);
                } else if (!absolute) {
                    out.add(n);
                }
                continue;
            }
            out.add(n);
        }
        return new FdropPath(fs, absolute, Collections.unmodifiableList(out), listed);
    }

    @Override
    public Path resolve(Path other) {
        FdropPath o = check(other);
        if (o.absolute) {
            return o;
        }
        List<String> out = new ArrayList<>(names);
        out.addAll(o.names);
        return new FdropPath(fs, absolute, Collections.unmodifiableList(out), null);
    }

    @Override
    public Path resolve(String other) {
        return resolve(fs.getPath(other));
    }

    @Override
    public Path resolveSibling(Path other) {
        Path parent = getParent();
        return parent == null ? other : parent.resolve(other);
    }

    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(fs.getPath(other));
    }

    @Override
    public Path relativize(Path other) {
        FdropPath o = check(other);
        if (o.absolute != absolute) {
            throw new IllegalArgumentException("cannot relativize mixed paths");
        }
        int common = 0;
        while (common < names.size() && common < o.names.size() && names.get(common).equals(o.names.get(common))) {
            common++;
        }
        List<String> out = new ArrayList<>();
        for (int i = common; i < names.size(); i++) {
            out.add("..");
        }
        out.addAll(o.names.subList(common, o.names.size()));
        return new FdropPath(fs, false, Collections.unmodifiableList(out), null);
    }

    @Override
    public URI toUri() {
        return URI.create("fdrop://" + virtual());
    }

    /** Relative paths are resolved against {@code /home}, every session's working directory. */
    @Override
    public Path toAbsolutePath() {
        if (absolute) {
            return this;
        }
        List<String> out = new ArrayList<>();
        out.add("home");
        out.addAll(names);
        return new FdropPath(fs, true, Collections.unmodifiableList(out), listed);
    }

    @Override
    public Path toRealPath(LinkOption... options) {
        return toAbsolutePath().normalize();
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException("watching is not supported");
    }

    @Override
    public int compareTo(Path other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FdropPath p && p.fs == fs && p.absolute == absolute && p.names.equals(names);
    }

    @Override
    public int hashCode() {
        return Objects.hash(absolute, names);
    }

    @Override
    public String toString() {
        String joined = String.join("/", names);
        return absolute ? "/" + joined : joined;
    }

    private static FdropPath check(Path p) {
        if (!(p instanceof FdropPath fp)) {
            throw new ProviderMismatchException();
        }
        return fp;
    }
}
