package io.fdrop.cli;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.List;

/**
 * A checked {@code fdrop watch} configuration (see "Watching a directory" in spec/clients.md): the one local folder to
 * listen to, the one server folder to upload into, and what to do with files.
 */
record WatchConfig(Path localDir, String remoteDir, boolean recursive, List<String> include, List<String> ignore,
                   Duration settle, String readyMarker, Action afterUpload, Action onFailure, int maxFiles,
                   Duration rescan, Duration poll, Path statusFile, Duration shutdownTimeout, boolean overwrite,
                   String expiresIn, int parallel, Duration retryFor) {

    /** Hidden files and the usual temporary names. */
    static final List<String> DEFAULT_IGNORE = List.of(".*", "*.tmp", "*.part", "*.partial", "*.crdownload", "*~");

    /** What to do with a file: leave it, delete it, or move it into a folder (keeping its relative path). */
    record Action(Kind kind, Path dir) {
        enum Kind { KEEP, DELETE, MOVE }

        static final Action KEEP = new Action(Kind.KEEP, null);

        /** {@code keep}, {@code delete} (when allowed) or {@code move=<dir>}. */
        static Action parse(String option, String value, boolean deleteAllowed) {
            String v = value == null ? "keep" : value.strip();
            if (v.equals("keep")) {
                return KEEP;
            }
            if (v.equals("delete") && deleteAllowed) {
                return new Action(Kind.DELETE, null);
            }
            if (v.startsWith("move=") && v.length() > 5) {
                return new Action(Kind.MOVE, Path.of(v.substring(5)).toAbsolutePath().normalize());
            }
            throw new CliException(CliException.USAGE, option + " must be keep, " + (deleteAllowed ? "delete, " : "")
                    + "or move=<dir>, not " + value);
        }
    }

    /** Checks the folders and settings; the local folder is resolved to its real path. */
    static WatchConfig check(Path local, String remote, boolean recursive, List<String> include, List<String> ignore,
                             Duration settle, String readyMarker, Action afterUpload, Action onFailure, int maxFiles,
                             Duration rescan, Duration poll, Path statusFile, Duration shutdownTimeout,
                             boolean overwrite, String expiresIn, int parallel, Duration retryFor) {
        if (local == null) {
            throw new CliException(CliException.USAGE, "the local folder to listen to is required: give it as the"
                    + " first argument or --local-dir");
        }
        if (remote == null || remote.isBlank()) {
            throw new CliException(CliException.USAGE, "the server folder to upload into is required: give it as"
                    + " the second argument or --remote-dir");
        }
        if (!remote.startsWith("/") || !remote.endsWith("/")) {
            throw new CliException(CliException.USAGE, "the server folder must start and end with /, e.g."
                    + " /shared/finance/incoming/, not " + remote);
        }
        if (!Files.isDirectory(local) || !Files.isReadable(local)) {
            throw new CliException(CliException.USAGE, "cannot watch " + local + ": not a readable folder");
        }
        Path real;
        try {
            real = local.toRealPath();
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, "cannot watch " + local + ": " + e.getMessage());
        }
        if (maxFiles < 1 || maxFiles > 8) {
            throw new CliException(CliException.USAGE, "--max-files must be between 1 and 8");
        }
        if (parallel < 1 || parallel > 8) {
            throw new CliException(CliException.USAGE, "--parallel must be between 1 and 8");
        }
        if (readyMarker != null && (readyMarker.isBlank() || readyMarker.contains("/"))) {
            throw new CliException(CliException.USAGE, "--ready-marker must be a file name suffix such as .done");
        }
        for (String g : include) {
            matcher(g);
        }
        for (String g : ignore) {
            matcher(g);
        }
        checkMoveTarget("--after-upload", afterUpload, real);
        checkMoveTarget("--on-failure", onFailure, real);
        return new WatchConfig(real, remote, recursive, List.copyOf(include), List.copyOf(ignore), settle,
                readyMarker, afterUpload, onFailure, maxFiles, rescan, poll, statusFile, shutdownTimeout, overwrite,
                expiresIn, parallel, retryFor);
    }

    /** A move target must be outside the watched tree, not contain it, and be on the same file system. */
    private static void checkMoveTarget(String option, Action a, Path watched) {
        if (a.kind() != Action.Kind.MOVE) {
            return;
        }
        try {
            Files.createDirectories(a.dir());
            Path target = a.dir().toRealPath();
            if (target.startsWith(watched) || watched.startsWith(target)) {
                throw new CliException(CliException.USAGE, option + " folder " + target + " must be outside the"
                        + " watched folder " + watched + ", and not contain it");
            }
            if (!Files.getFileStore(target).equals(Files.getFileStore(watched))) {
                throw new CliException(CliException.USAGE, option + " folder " + target + " must be on the same file"
                        + " system as " + watched + ", so files are moved by renaming them");
            }
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, option + ": cannot use " + a.dir() + ": " + e.getMessage());
        }
    }

    static PathMatcher matcher(String glob) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (IllegalArgumentException e) {
            throw new CliException(CliException.USAGE, "not a glob: " + glob);
        }
    }

    /** Whether the folder is on a file system that does not deliver watch events for changes from other hosts. */
    boolean onNetworkFileSystem() {
        try {
            String type = Files.getFileStore(localDir).type().toLowerCase(java.util.Locale.ROOT);
            return type.startsWith("nfs") || type.startsWith("cifs") || type.startsWith("smb")
                    || type.contains("sshfs") || type.equals("9p");
        } catch (IOException e) {
            return false;
        }
    }

    static boolean regularFile(Path p) {
        return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
    }
}
