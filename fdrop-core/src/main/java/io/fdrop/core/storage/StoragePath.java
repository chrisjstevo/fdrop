package io.fdrop.core.storage;

import io.fdrop.core.error.FdropException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A normalised logical storage key such as {@code users/abc/docs/report.pdf}. Paths are never OS paths; every path
 * that reaches a backend is built here, so this is the one place that rejects traversal and unsafe names.
 */
public final class StoragePath implements Comparable<StoragePath> {

    public static final StoragePath ROOT = new StoragePath(List.of());
    private static final int MAX_SEGMENT_BYTES = 255;
    private static final int MAX_KEY_BYTES = 1024;

    private final List<String> segments;

    private StoragePath(List<String> segments) {
        this.segments = segments;
    }

    /** Parses a {@code /}-separated path; empty segments and {@code .} are dropped, {@code ..} is refused. */
    public static StoragePath of(String path) {
        if (path == null) {
            throw FdropException.badRequest("path is required");
        }
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            out.add(checkSegment(seg));
        }
        StoragePath p = new StoragePath(Collections.unmodifiableList(out));
        if (p.toString().getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw FdropException.badRequest("path is too long");
        }
        return p;
    }

    public static StoragePath ofSegments(List<String> segments) {
        List<String> out = new ArrayList<>(segments.size());
        for (String s : segments) {
            out.add(checkSegment(s));
        }
        return new StoragePath(Collections.unmodifiableList(out));
    }

    /** Validates one file or folder name. */
    public static String checkSegment(String seg) {
        if (seg.isEmpty()) {
            throw FdropException.badRequest("empty name");
        }
        if (seg.equals("..") || seg.equals(".")) {
            throw FdropException.badRequest("'..' and '.' are not allowed in paths");
        }
        if (seg.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
            throw FdropException.badRequest("name longer than 255 bytes: " + seg.substring(0, Math.min(40, seg.length())));
        }
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7f) {
                throw FdropException.badRequest("name contains a forbidden character");
            }
        }
        return seg;
    }

    public StoragePath resolve(String child) {
        StoragePath c = of(child);
        List<String> out = new ArrayList<>(segments);
        out.addAll(c.segments);
        return new StoragePath(Collections.unmodifiableList(out));
    }

    public StoragePath resolve(StoragePath child) {
        List<String> out = new ArrayList<>(segments);
        out.addAll(child.segments);
        return new StoragePath(Collections.unmodifiableList(out));
    }

    public StoragePath parent() {
        if (segments.isEmpty()) {
            return null;
        }
        return new StoragePath(segments.subList(0, segments.size() - 1));
    }

    public String name() {
        return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
    }

    public List<String> segments() {
        return segments;
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public boolean startsWith(StoragePath prefix) {
        if (prefix.segments.size() > segments.size()) {
            return false;
        }
        return segments.subList(0, prefix.segments.size()).equals(prefix.segments);
    }

    /** The remainder of this path after {@code prefix}, which it must start with. */
    public StoragePath relativize(StoragePath prefix) {
        if (!startsWith(prefix)) {
            throw new IllegalArgumentException(this + " is not under " + prefix);
        }
        return new StoragePath(segments.subList(prefix.segments.size(), segments.size()));
    }

    @Override
    public String toString() {
        return String.join("/", segments);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StoragePath other && other.segments.equals(segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    @Override
    public int compareTo(StoragePath o) {
        return toString().compareTo(o.toString());
    }
}
