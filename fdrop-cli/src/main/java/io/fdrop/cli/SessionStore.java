package io.fdrop.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Upload sessions saved between runs, in {@code ~/.local/state/fdrop/uploads/} (or {@code $XDG_STATE_HOME}), so a
 * run that was interrupted or gave up resumes where it stopped. A session is keyed by everything that must match
 * for the server's session to be the same upload: server, certificate, remote path and the file's size, time and
 * hash. Files are owner-only.
 */
class SessionStore {

    /** What a run needs to resume: the server's upload id and its chunking. */
    record Saved(String id, long chunkSize, int chunkCount) {
    }

    private final Path dir;

    SessionStore(Path dir) {
        this.dir = dir;
    }

    static Path defaultDir(java.util.Map<String, String> env) {
        String state = env.get("XDG_STATE_HOME");
        Path base = state != null && !state.isBlank() ? Path.of(state)
                : Path.of(System.getProperty("user.home"), ".local", "state");
        return base.resolve("fdrop").resolve("uploads");
    }

    static String key(String url, String certFingerprint, String remotePath, long size, long lastModified,
                      String fileSha256) {
        return key(url, certFingerprint, remotePath, size, lastModified, fileSha256, null);
    }

    /** As above; a different {@code --expires-in} for the same file is a different session. */
    static String key(String url, String certFingerprint, String remotePath, long size, long lastModified,
                      String fileSha256, String expiresIn) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{url, certFingerprint, remotePath, Long.toString(size),
                    Long.toString(lastModified), fileSha256}) {
                d.update(part.getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
            }
            if (expiresIn != null) {
                d.update(("expires:" + expiresIn).getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Optional<Saved> load(String key) {
        Path f = dir.resolve(key + ".json");
        if (!Files.exists(f)) {
            return Optional.empty();
        }
        try {
            var n = Api.JSON.readTree(f.toFile());
            return Optional.of(new Saved(n.path("id").asText(), n.path("chunkSize").asLong(),
                    n.path("chunkCount").asInt()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    void save(String key, Saved s, String remotePath, Path local) {
        try {
            createPrivateDir(dir);
            ObjectNode n = Api.JSON.createObjectNode();
            n.put("id", s.id());
            n.put("chunkSize", s.chunkSize());
            n.put("chunkCount", s.chunkCount());
            n.put("remotePath", remotePath);
            n.put("localFile", local.toAbsolutePath().toString());
            n.put("savedAt", java.time.Instant.now().toString());
            Path tmp = dir.resolve(key + ".tmp");
            Files.deleteIfExists(tmp);
            createPrivateFile(tmp);
            Files.writeString(tmp, Api.JSON.writeValueAsString(n));
            Files.move(tmp, dir.resolve(key + ".json"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // resuming across runs is a convenience; the upload itself goes on
            System.err.println("fdrop: warning: cannot save the upload session in " + dir + ": " + e.getMessage());
        }
    }

    void delete(String key) {
        try {
            Files.deleteIfExists(dir.resolve(key + ".json"));
        } catch (IOException e) {
            // a stale session file only means the next run checks with the server and starts afresh
        }
    }

    Path file(String key) {
        return dir.resolve(key + ".json");
    }

    private static void createPrivateDir(Path d) throws IOException {
        if (Files.isDirectory(d)) {
            return;
        }
        try {
            Files.createDirectories(d.getParent());
            Files.createDirectory(d, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(d);
        } catch (FileAlreadyExistsException e) {
            // created by a concurrent run
        }
    }

    private static void createPrivateFile(Path f) throws IOException {
        try {
            Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(f);
        }
    }
}
