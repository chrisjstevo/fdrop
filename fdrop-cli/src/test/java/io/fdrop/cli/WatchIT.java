package io.fdrop.cli;

import io.fdrop.core.FakeDirectory;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestCerts;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.model.Enums.AppMethod;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.service.AppAccountService;
import io.fdrop.core.service.FileService;
import io.fdrop.http.HttpServer;
import io.fdrop.storage.local.LocalStorageBackend;
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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code fdrop watch} against the real fdrop HTTP server with its mTLS listener, in this JVM: an application account
 * watching a local folder and uploading into a named server folder.
 */
class WatchIT {

    @TempDir
    Path dir;

    final TestCerts.Issued serverCa = TestCerts.ca("fdrop test CA");
    final TestCerts.Issued appCert = TestCerts.selfSigned("CN=app-watch");
    final Principal admin = TestSupport.admin("admin1");
    final Principal alice = TestSupport.user("alice");
    final Principal appUser = TestSupport.app("app-watch");
    Fdrop app;
    HttpServer server;
    Path cert;
    Path key;
    Path ca;
    String url;
    Path local;

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.http.mtls.enabled = true;
        cfg.http.mtls.port = 0;
        cfg.auth.twoFactor.require = "none";
        cfg.limits.defaultAppQuota = io.fdrop.core.config.DataSize.ofGiB(1);
        Path[] serverFiles = TestCerts.server(serverCa).write(dir, "server");
        cfg.http.tls.cert = serverFiles[0].toString();
        cfg.http.tls.key = serverFiles[1].toString();
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(Files.createDirectories(dir.resolve("files")));
        app = TestSupport.fdrop(cfg, b, new FakeDirectory());
        server = new HttpServer(app).start();
        url = "https://localhost:" + server.mtlsPort();
        Path[] client = appCert.write(dir, "app-watch");
        cert = client[0];
        key = client[1];
        ca = Files.writeString(dir.resolve("ca.pem"), serverCa.certPem());
        app.accounts.ensure(admin);
        app.accounts.ensure(alice);
        app.appAccounts.create(admin, new AppAccountService.CreateInput("app-watch", "test", "alice",
                EnumSet.of(AppMethod.MTLS), List.of()));
        app.appAccounts.addCertificate(admin, "sub-app-watch", new AppAccountService.CertInput(appCert.certPem(),
                null, null));
        local = Files.createDirectories(dir.resolve("outbox"));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        app.close();
    }

    /** A watch running in the background; {@link #stop()} stops it as a signal would. */
    final class Running {
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        final AtomicReference<Runnable> stopper = new AtomicReference<>();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();

        Running(String... args) {
            List<String> a = new ArrayList<>(List.of("watch"));
            a.addAll(List.of(args));
            a.addAll(List.of("--url", url, "--cert", cert.toString(), "--key", key.toString(), "--ca", ca.toString(),
                    "--settle", "300ms", "--rescan", "2s", "--shutdown-timeout", "5s"));
            FdropCli.Context ctx = new FdropCli.Context(Map.of(), new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(err, true, StandardCharsets.UTF_8), Backoff.Clock.SYSTEM, false,
                    dir.resolve("state/uploads"), false, stopper::set);
            Thread.ofPlatform().daemon().start(() -> exit.complete(FdropCli.run(a.toArray(new String[0]), ctx)));
        }

        String log() {
            return err.toString(StandardCharsets.UTF_8);
        }

        int stop() throws Exception {
            await(() -> stopper.get() != null || exit.isDone(), "the watcher to start");
            if (stopper.get() != null) {
                stopper.get().run();
            }
            return exit.get(30, TimeUnit.SECONDS);
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private List<String> names(Principal p, String folder) {
        try {
            return app.files.list(p, folder).stream().map(FileService.Entry::name).sorted().toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private String shared(String name, Permission permission) {
        String id = app.folderService.create(alice, FolderType.SHARED, name, null, null).id();
        app.folderService.putMember(alice, id, "app-watch", permission);
        return "/shared/" + name + "/";
    }

    @Test
    void uploadsNewFilesIntoTheNamedServerFolderOnce() throws Exception {
        String finance = shared("Finance", Permission.WRITE);
        Files.writeString(local.resolve("existing.csv"), "there before the start");
        Running w = new Running(local.toString(), finance, "--recursive");
        await(() -> names(alice, "/shared/Finance").contains("existing.csv"), "a file there at the start");

        Files.writeString(local.resolve("in-place.csv"), "written in place");
        Path tmp = Files.writeString(dir.resolve("renamed.tmp-outside"), "renamed into place");
        Files.move(tmp, local.resolve("renamed.csv"), StandardCopyOption.ATOMIC_MOVE);
        for (int i = 0; i < 20; i++) {
            Files.writeString(local.resolve("burst-" + i + ".txt"), "file " + i);
        }
        Files.writeString(local.resolve("ignored.tmp"), "temporary");
        // a folder created while running, and a file in it
        Path nested = Files.createDirectories(local.resolve("2026/10"));
        Files.writeString(nested.resolve("ledger.csv"), "nested");
        await(() -> names(alice, "/shared/Finance").size() == 24 && names(alice, "/shared/Finance/2026/10")
                .contains("ledger.csv"), "every file to arrive");
        assertThat(names(alice, "/shared/Finance")).doesNotContain("ignored.tmp").contains("2026");
        assertThat(w.stop()).isZero();
        long uploads = w.log().lines().filter(l -> l.contains(" uploaded ")).count();
        assertThat(uploads).isEqualTo(24);

        // a restart uploads nothing again: the ledger knows every file
        Running again = new Running(local.toString(), finance, "--recursive", "--json");
        Thread.sleep(3000);
        assertThat(again.stop()).isZero();
        assertThat(again.log()).contains("\"event\":\"started\"").doesNotContain("\"event\":\"uploaded\"");
    }

    @Test
    void movesSentFilesAndSetsAsideRefusedOnes() throws Exception {
        Path sent = dir.resolve("sent");
        Path failed = dir.resolve("failed");
        Path status = dir.resolve("status.json");
        Running w = new Running(local.toString(), "/home/", "--after-upload", "move=" + sent, "--on-failure",
                "move=" + failed, "--status-file", status.toString());
        Files.writeString(local.resolve("dup.csv"), "first");
        await(() -> Files.exists(sent.resolve("dup.csv")), "the first file to be moved after its upload");
        assertThat(names(appUser, "/home")).contains("dup.csv");

        // the same name again, without --overwrite: the server refuses it and it is set aside
        Files.writeString(local.resolve("dup.csv"), "second");
        await(() -> Files.exists(failed.resolve("dup.csv.error.txt")), "the refused file to be set aside");
        assertThat(Files.readString(failed.resolve("dup.csv"))).isEqualTo("second");
        assertThat(Files.readString(failed.resolve("dup.csv.error.txt"))).containsIgnoringCase("exists");
        await(() -> {
            try {
                return Files.readString(status).contains("\"refused\" : 1");
            } catch (IOException e) {
                return false;
            }
        }, "the status file to count the refusal");
        String json = Files.readString(status);
        assertThat(json).contains("\"uploaded\" : 1", "\"remoteDir\" : \"/home/\"", "\"lastUpload\" : \"/home/dup.csv\"");
        assertThat(w.stop()).isZero();
    }

    @Test
    void pollingFindsFilesWithoutEvents() throws Exception {
        Running w = new Running(local.toString(), "/home/", "--poll", "500ms");
        Files.writeString(local.resolve("polled.csv"), "found by a scan");
        await(() -> names(appUser, "/home").contains("polled.csv"), "the polled file");
        assertThat(w.log()).contains("polling every PT0.5S");
        assertThat(w.stop()).isZero();
    }

    @Test
    void theServerFolderIsCheckedAtTheStart() throws Exception {
        Running missing = new Running(local.toString(), "/shared/Nope/");
        assertThat(missing.exit.get(30, TimeUnit.SECONDS)).isEqualTo(CliException.NOT_ALLOWED);
        assertThat(missing.log()).contains("cannot upload into /shared/Nope/");

        String readOnly = shared("ReadOnly", Permission.READ);
        Running ro = new Running(local.toString(), readOnly);
        assertThat(ro.exit.get(30, TimeUnit.SECONDS)).isEqualTo(CliException.NOT_ALLOWED);
        assertThat(ro.log()).contains("cannot write to " + readOnly);

        Running local404 = new Running(dir.resolve("not-there").toString(), "/home/");
        assertThat(local404.exit.get(30, TimeUnit.SECONDS)).isEqualTo(CliException.USAGE);
    }

    @Test
    void aSecondWatcherAndAVanishedFolderStopWithCode7() throws Exception {
        Running first = new Running(local.toString(), "/home/");
        await(() -> first.log().contains(" started "), "the first watcher to start");
        Running second = new Running(local.toString(), "/home/");
        assertThat(second.exit.get(30, TimeUnit.SECONDS)).isEqualTo(CliException.WATCH);
        assertThat(second.log()).contains("already running");

        Files.delete(local);
        assertThat(first.exit.get(30, TimeUnit.SECONDS)).isEqualTo(CliException.WATCH);
        assertThat(first.log()).contains("disappeared or became unreadable");
    }
}
