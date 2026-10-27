package io.fdrop.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code fdrop watch} without a server: when files are ready, what is skipped, the ledger, refusals, settings. */
class WatchTest {

    @TempDir
    Path dir;

    Path local;
    Path state;
    final FakeClock clock = new FakeClock();
    final List<String> sent = new CopyOnWriteArrayList<>();
    final List<String> folders = new CopyOnWriteArrayList<>();
    /** What the fake server does with the next upload; null to accept it. */
    volatile RuntimeException answer;
    final ByteArrayOutputStream logged = new ByteArrayOutputStream();
    WatchLedger ledger;

    static final class FakeClock implements Backoff.Clock {
        volatile long nanos;

        @Override
        public long nanos() {
            return nanos;
        }

        @Override
        public void sleep(Duration d) {
            nanos += d.toNanos();
        }

        void advance(Duration d) {
            nanos += d.toNanos();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        local = Files.createDirectories(dir.resolve("outbox"));
        state = Files.createDirectories(dir.resolve("state"));
    }

    @AfterEach
    void tearDown() {
        if (ledger != null) {
            ledger.close();
        }
    }

    private WatchConfig config(String... settings) {
        Map<String, String> s = new java.util.HashMap<>();
        for (int i = 0; i + 1 < settings.length; i += 2) {
            s.put(settings[i], settings[i + 1]);
        }
        return WatchConfig.check(local, s.getOrDefault("remote", "/home/in/"), s.containsKey("recursive"),
                s.containsKey("include") ? List.of(s.get("include").split(",")) : List.of(),
                s.containsKey("ignore") ? List.of(s.get("ignore").split(",")) : WatchConfig.DEFAULT_IGNORE,
                Duration.ofSeconds(10), s.get("marker"),
                WatchConfig.Action.parse("--after-upload", s.getOrDefault("after", "keep"), true),
                WatchConfig.Action.parse("--on-failure", s.getOrDefault("failure", "keep"), false), 2,
                Duration.ofMinutes(5), null, null, Duration.ofSeconds(5), false, null, 3, Duration.ofHours(1));
    }

    private Watcher watcher(WatchConfig cfg) {
        if (ledger != null) {
            ledger.close();
        }
        ledger = new WatchLedger(state.resolve("ledger.jsonl"), cfg.localDir());
        Watcher.Uploads uploads = new Watcher.Uploads() {
            @Override
            public Uploader.Result upload(Path file, String remote, BooleanSupplier unchanged) {
                RuntimeException e = answer;
                if (e != null) {
                    throw e;
                }
                if (!unchanged.getAsBoolean()) {
                    throw new Uploader.FileChanged(file);
                }
                sent.add(remote);
                return new Uploader.Result(remote, 1, "ab", "upl_1", false, Api.JSON.createObjectNode());
            }

            @Override
            public void mkdir(String remote) {
                folders.add(remote);
            }
        };
        return new Watcher(cfg, uploads, ledger, new WatchLog(new PrintStream(logged, true, StandardCharsets.UTF_8),
                false), clock);
    }

    private Path write(String rel, String content) throws IOException {
        Path p = local.resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, content);
    }

    /** Scans, waits the settle time, and starts what is ready; then waits for the uploads. */
    private void settleAndUpload(Watcher w) throws InterruptedException {
        w.scan();
        w.tick();
        clock.advance(Duration.ofSeconds(11));
        w.tick();
        w.awaitIdle(Duration.ofSeconds(5));
    }

    @Test
    void aFileIsUploadedOnlyOnceItHasSettled() throws Exception {
        Watcher w = watcher(config());
        Path f = write("report.csv", "a");
        w.scan();
        w.tick();
        clock.advance(Duration.ofSeconds(9));
        w.tick();
        assertThat(sent).isEmpty();
        // still being written: the wait starts again
        Files.writeString(f, "ab");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
        w.observe(f);
        clock.advance(Duration.ofSeconds(9));
        w.tick();
        assertThat(sent).isEmpty();
        clock.advance(Duration.ofSeconds(2));
        w.tick();
        w.awaitIdle(Duration.ofSeconds(5));
        assertThat(sent).containsExactly("/home/in/report.csv");
        assertThat(logged.toString(StandardCharsets.UTF_8)).contains("uploaded report.csv -> /home/in/report.csv");
    }

    @Test
    void aMarkerFileMakesAFileReady() throws Exception {
        Watcher w = watcher(config("marker", ".done"));
        write("data.csv", "x");
        w.scan();
        clock.advance(Duration.ofHours(1));
        w.tick();
        assertThat(sent).isEmpty();
        write("data.csv.done", "");
        w.scan();
        w.tick();
        w.awaitIdle(Duration.ofSeconds(5));
        assertThat(sent).containsExactly("/home/in/data.csv");
    }

    @Test
    void hiddenTemporaryAndExcludedFilesAreNotUploaded() throws Exception {
        for (String n : List.of(".hidden", "a.tmp", "b.part", "c.partial", "d.crdownload", "e~", "f.csv", "g.txt")) {
            write(n, n);
        }
        settleAndUpload(watcher(config()));
        assertThat(sent).containsExactlyInAnyOrder("/home/in/f.csv", "/home/in/g.txt");

        sent.clear();
        Files.delete(state.resolve("ledger.jsonl"));
        settleAndUpload(watcher(config("include", "*.csv")));
        assertThat(sent).containsExactly("/home/in/f.csv");

        sent.clear();
        Files.delete(state.resolve("ledger.jsonl"));
        settleAndUpload(watcher(config("ignore", "*.txt")));
        assertThat(sent).contains("/home/in/a.tmp", "/home/in/.hidden").doesNotContain("/home/in/g.txt");
    }

    @Test
    void symbolicLinksAreSkipped() throws Exception {
        Path target = Files.writeString(dir.resolve("elsewhere.txt"), "secret");
        Files.createSymbolicLink(local.resolve("link.txt"), target);
        write("real.txt", "x");
        settleAndUpload(watcher(config()));
        assertThat(sent).containsExactly("/home/in/real.txt");
        assertThat(logged.toString(StandardCharsets.UTF_8)).contains("link.txt is a symbolic link");
    }

    @Test
    void theLedgerStopsRepeatUploadsUntilAFileChanges() throws Exception {
        Path f = write("a.csv", "1");
        settleAndUpload(watcher(config()));
        assertThat(sent).hasSize(1);

        // a restart: the same file is not uploaded again
        settleAndUpload(watcher(config()));
        assertThat(sent).hasSize(1);
        assertThat(ledger.get("a.csv").orElseThrow().outcome()).isEqualTo("uploaded");

        Files.writeString(f, "22");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        settleAndUpload(watcher(config()));
        assertThat(sent).hasSize(2);

        // compaction keeps the latest record of each file that still exists
        Files.delete(f);
        watcher(config());
        assertThat(ledger.size()).isZero();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(state.resolve("ledger.jsonl"))))
                .isEqualTo("rw-------");
    }

    @Test
    void aRefusedFileIsSetAsideAndNotRetried() throws Exception {
        Path failed = dir.resolve("failed");
        write("dup.csv", "x");
        answer = new CliException(CliException.EXISTS, "Conflict: /home/in/dup.csv already exists");
        Watcher w = watcher(config("failure", "move=" + failed));
        settleAndUpload(w);
        assertThat(local.resolve("dup.csv")).doesNotExist();
        assertThat(failed.resolve("dup.csv")).exists();
        assertThat(Files.readString(failed.resolve("dup.csv.error.txt"))).contains("already exists");
        assertThat(ledger.get("dup.csv").orElseThrow().outcome()).isEqualTo("refused");

        // kept in place: recorded, and not tried again until it changes
        answer = new CliException(CliException.NOT_ALLOWED, "Forbidden");
        write("other.csv", "x");
        Watcher keep = watcher(config());
        settleAndUpload(keep);
        answer = null;
        clock.advance(Duration.ofHours(1));
        settleAndUpload(keep);
        assertThat(sent).isEmpty();
    }

    @Test
    void anUnreachableServerKeepsTheFileQueued() throws Exception {
        write("later.csv", "x");
        answer = new CliException(CliException.GAVE_UP, "gave up: connection refused");
        Watcher w = watcher(config());
        settleAndUpload(w);
        assertThat(sent).isEmpty();
        answer = null;
        w.tick();
        w.awaitIdle(Duration.ofSeconds(5));
        assertThat(sent).isEmpty();
        // tried again after --rescan
        clock.advance(Duration.ofMinutes(6));
        w.tick();
        w.awaitIdle(Duration.ofSeconds(5));
        assertThat(sent).containsExactly("/home/in/later.csv");
    }

    @Test
    void aFileThatChangesWhileUploadingGoesUpAgainOnceSettled() throws Exception {
        Path f = write("growing.csv", "1");
        Watcher w = watcher(config());
        answer = new Uploader.FileChanged(f);
        settleAndUpload(w);
        assertThat(sent).isEmpty();
        assertThat(logged.toString(StandardCharsets.UTF_8)).contains("changed while it was being uploaded");
        answer = null;
        w.tick();
        w.awaitIdle(Duration.ofSeconds(5));
        assertThat(sent).isEmpty();
        clock.advance(Duration.ofSeconds(11));
        w.tick();
        w.awaitIdle(Duration.ofSeconds(5));
        assertThat(sent).containsExactly("/home/in/growing.csv");
    }

    @Test
    void nestedFilesKeepTheirPathsAndFoldersAreCreated() throws Exception {
        write("2026/10/ledger.csv", "x");
        write("2026/11/a.csv", "x");
        write(".git/config", "x");
        settleAndUpload(watcher(config("recursive", "y", "remote", "/shared/finance/incoming/")));
        assertThat(sent).containsExactlyInAnyOrder("/shared/finance/incoming/2026/10/ledger.csv",
                "/shared/finance/incoming/2026/11/a.csv");
        assertThat(folders).containsExactlyInAnyOrder("/shared/finance/incoming/2026",
                "/shared/finance/incoming/2026/10", "/shared/finance/incoming/2026/11");

        // without --recursive, subfolders are left alone
        sent.clear();
        Files.delete(state.resolve("ledger.jsonl"));
        write("top.csv", "x");
        settleAndUpload(watcher(config()));
        assertThat(sent).containsExactly("/home/in/top.csv");
    }

    @Test
    void afterUploadDeletesOrMoves() throws Exception {
        Path sentDir = dir.resolve("sent");
        write("a/one.csv", "1");
        settleAndUpload(watcher(config("recursive", "y", "after", "move=" + sentDir)));
        assertThat(sentDir.resolve("a/one.csv")).exists();
        assertThat(local.resolve("a/one.csv")).doesNotExist();

        write("two.csv", "2");
        write("two.csv.done", "");
        settleAndUpload(watcher(config("after", "delete", "marker", ".done")));
        assertThat(local.resolve("two.csv")).doesNotExist();
        assertThat(local.resolve("two.csv.done")).doesNotExist();
    }

    @Test
    void anOverflowAsksForAFullScan() throws Exception {
        Watcher w = watcher(config());
        w.scan();
        write("missed.csv", "x");
        WatchEvent<Object> overflow = new WatchEvent<>() {
            @Override
            public Kind<Object> kind() {
                return StandardWatchEventKinds.OVERFLOW;
            }

            @Override
            public int count() {
                return 1;
            }

            @Override
            public Object context() {
                return null;
            }
        };
        w.handle(local, List.of(overflow));
        assertThat(logged.toString(StandardCharsets.UTF_8)).contains("too many changes at once");
    }

    @Test
    void settingsAreChecked() throws Exception {
        assertThatThrownBy(() -> WatchConfig.check(null, "/home/", false, List.of(), List.of(), Duration.ZERO, null,
                WatchConfig.Action.KEEP, WatchConfig.Action.KEEP, 1, Duration.ZERO, null, null, Duration.ZERO, false,
                null, 1, Duration.ZERO)).hasMessageContaining("local folder to listen to is required");
        assertThatThrownBy(() -> config("remote", "/home/in")).hasMessageContaining("must start and end with /");
        assertThatThrownBy(() -> config("after", "move=" + local.resolve("sent")))
                .hasMessageContaining("must be outside the watched folder");
        assertThatThrownBy(() -> config("after", "move=" + dir)).hasMessageContaining("and not contain it");
        assertThatThrownBy(() -> config("failure", "delete")).hasMessageContaining("--on-failure must be keep, or");
        assertThatThrownBy(() -> config("after", "archive")).hasMessageContaining("keep, delete, or move=<dir>");
        assertThatThrownBy(() -> WatchConfig.check(dir.resolve("nope"), "/home/", false, List.of(), List.of(),
                Duration.ZERO, null, WatchConfig.Action.KEEP, WatchConfig.Action.KEEP, 1, Duration.ZERO, null, null,
                Duration.ZERO, false, null, 1, Duration.ZERO)).hasMessageContaining("not a readable folder");
    }

    @Test
    void aSecondWatcherWithTheSameSettingsIsRefused() {
        try (WatchLock first = WatchLock.acquire(state.resolve("lock"))) {
            assertThatThrownBy(() -> WatchLock.acquire(state.resolve("lock")))
                    .isInstanceOfSatisfying(CliException.class, e -> {
                        assertThat(e.code()).isEqualTo(CliException.WATCH);
                        assertThat(e.getMessage()).contains("PID " + ProcessHandle.current().pid());
                    });
        }
        WatchLock.acquire(state.resolve("lock")).close();
    }

    @Test
    void theFoldersComeFromArgumentsOptionsEnvironmentOrTheConfigFile() throws Exception {
        Path cfg = Files.writeString(dir.resolve("cli.properties"), "watch.localDir=" + local + "\n"
                + "watch.remoteDir=/shared/finance/incoming/\nwatch.afterUpload=archive\n");
        Files.setPosixFilePermissions(cfg, PosixFilePermissions.fromString("rw-------"));
        // the folders were read from the file: it got as far as checking the next setting
        assertThat(run(Map.of(), "watch", "--config", cfg.toString())).contains("--after-upload must be keep");

        Files.writeString(cfg, "watch.localDir=" + local + "\n");
        assertThat(run(Map.of(), "watch", "--config", cfg.toString()))
                .contains("server folder to upload into is required");
        assertThat(run(Map.of("FDROP_WATCH_REMOTE_DIR", "/home/in"), "watch", "--config", cfg.toString()))
                .contains("must start and end with /");
        assertThat(run(Map.of(), "watch", local.toString(), "/home/in/", "--local-dir", dir.toString()))
                .contains("the local folder is given twice");
        assertThat(run(Map.of(), "watch", local.toString(), "/home/in/")).contains("a client certificate is required");
    }

    private String run(Map<String, String> env, String... args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        FdropCli.Context ctx = new FdropCli.Context(env, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8), clock, false, state.resolve("uploads"), false);
        List<String> a = new ArrayList<>(List.of(args));
        assertThat(FdropCli.run(a.toArray(new String[0]), ctx)).isNotZero();
        return err.toString(StandardCharsets.UTF_8);
    }
}
