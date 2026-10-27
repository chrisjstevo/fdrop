package io.fdrop.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a watch has done with each file, so a restart does not upload a file again: one JSON line per outcome in
 * {@code ledger.jsonl}, owner-only. At start it is compacted to the latest record of each file that still exists.
 */
final class WatchLedger implements AutoCloseable {

    /** {@code outcome} is {@code uploaded}, {@code refused} (with the problem) or {@code changed}. */
    record Entry(String path, long size, long modified, String sha256, String remote, String outcome, String problem,
                 String time) {

        boolean sameFile(long size, long modified) {
            return this.size == size && this.modified == modified;
        }
    }

    private final Path file;
    private final Map<String, Entry> latest = new LinkedHashMap<>();
    private BufferedWriter out;

    /** Reads and compacts the ledger; records of files no longer in {@code localDir} are dropped. */
    WatchLedger(Path file, Path localDir) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        JsonNode n = Api.JSON.readTree(line);
                        Entry e = new Entry(n.path("path").asText(), n.path("size").asLong(),
                                n.path("modified").asLong(), n.path("sha256").asText(null), n.path("remote").asText(null),
                                n.path("outcome").asText(), n.path("problem").asText(null), n.path("time").asText(null));
                        latest.put(e.path(), e);
                    } catch (IOException ignored) {
                        // a line cut short by a crash: the file is simply looked at again
                    }
                }
            }
            latest.keySet().removeIf(rel -> !Files.exists(localDir.resolve(rel)));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Entry e : latest.values()) {
                    w.write(json(e));
                    w.newLine();
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, "cannot use the watch ledger " + file + ": " + e.getMessage());
        }
    }

    synchronized Optional<Entry> get(String rel) {
        return Optional.ofNullable(latest.get(rel));
    }

    /** Whether this exact file (path, size, time) was already uploaded, or refused and not changed since. */
    synchronized boolean settled(String rel, long size, long modified) {
        Entry e = latest.get(rel);
        return e != null && e.sameFile(size, modified) && (e.outcome().equals("uploaded")
                || e.outcome().equals("refused"));
    }

    synchronized void record(String rel, long size, long modified, String sha256, String remote, String outcome,
                             String problem) {
        Entry e = new Entry(rel, size, modified, sha256, remote, outcome, problem, Instant.now().toString());
        latest.put(rel, e);
        try {
            out.write(json(e));
            out.newLine();
            out.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot write the watch ledger " + file, ex);
        }
    }

    synchronized int size() {
        return latest.size();
    }

    private static String json(Entry e) {
        ObjectNode n = Api.JSON.createObjectNode();
        n.put("path", e.path());
        n.put("size", e.size());
        n.put("modified", e.modified());
        if (e.sha256() != null) {
            n.put("sha256", e.sha256());
        }
        if (e.remote() != null) {
            n.put("remote", e.remote());
        }
        n.put("outcome", e.outcome());
        if (e.problem() != null) {
            n.put("problem", e.problem());
        }
        n.put("time", e.time());
        return n.toString();
    }

    @Override
    public synchronized void close() {
        try {
            out.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
