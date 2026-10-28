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
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.service.AppAccountService;
import io.fdrop.core.service.DestinationService;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.http.HttpServer;
import io.fdrop.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI against the real fdrop HTTP server with its mTLS listener, in this JVM: an application account with a
 * pinned certificate uploads into its home, a shared folder and a forward folder, is refused where it should be,
 * and gets a large file through a connection that keeps being cut, resuming across runs.
 */
class CliIT {

    @TempDir
    Path dir;

    final TestCerts.Issued serverCa = TestCerts.ca("fdrop test CA");
    final TestCerts.Issued appCert = TestCerts.selfSigned("CN=app-cli");
    final FakeDirectory directory = new FakeDirectory();
    final Principal admin = TestSupport.admin("admin1");
    final Principal alice = TestSupport.user("alice");
    Fdrop app;
    HttpServer server;
    Path cert;
    Path key;
    Path ca;
    String url;
    String out;
    String err;

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
        app = TestSupport.fdrop(cfg, b, directory);
        server = new HttpServer(app).start();
        url = "https://localhost:" + server.mtlsPort();

        Path[] client = appCert.write(dir, "app-cli");
        cert = client[0];
        key = client[1];
        ca = dir.resolve("ca.pem");
        Files.writeString(ca, serverCa.certPem());

        app.accounts.ensure(admin);
        app.accounts.ensure(alice);
        app.appAccounts.create(admin, new AppAccountService.CreateInput("app-cli", "test", "alice",
                EnumSet.of(AppMethod.MTLS), List.of()));
        app.appAccounts.addCertificate(admin, "sub-app-cli", new AppAccountService.CertInput(appCert.certPem(),
                null, null));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        app.close();
    }

    private int cli(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        FdropCli.Context ctx = new FdropCli.Context(Map.of(), new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8), Backoff.Clock.SYSTEM, false, dir.resolve("state"),
                false);
        int code = FdropCli.run(args, ctx);
        out = o.toString(StandardCharsets.UTF_8);
        err = e.toString(StandardCharsets.UTF_8);
        return code;
    }

    private String[] upload(String base, Path file, String remote, String... extra) {
        List<String> a = new ArrayList<>(List.of("upload", file.toString(), remote, "--url", base, "--cert",
                cert.toString(), "--key", key.toString(), "--ca", ca.toString()));
        a.addAll(List.of(extra));
        return a.toArray(new String[0]);
    }

    private Path file(String name, int bytes) throws IOException {
        byte[] data = new byte[bytes];
        new Random(bytes).nextBytes(data);
        return Files.write(dir.resolve(name), data);
    }

    private String storedSha(String storageKey) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        long size = app.storage.stat(StoragePath.of(storageKey)).orElseThrow().size();
        try (ReadableByteChannel ch = app.storage.openRead(StoragePath.of(storageKey), 0, size)) {
            ByteBuffer buf = ByteBuffer.allocate(1 << 16);
            while (ch.read(buf) >= 0) {
                buf.flip();
                d.update(buf);
                buf.clear();
            }
        }
        return HexFormat.of().formatHex(d.digest());
    }

    private static String sha(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    @Test
    void uploadsIntoHomeSharedAndForwardFolders() throws Exception {
        Path f = file("report.csv", 12_345);
        assertThat(cli(upload(url, f, "/home/"))).as(err).isZero();
        assertThat(storedSha("users/sub-app-cli/report.csv")).isEqualTo(sha(f));

        String shared = app.folderService.create(alice, FolderType.SHARED, "Finance", null, null).id();
        app.folderService.putMember(alice, shared, "app-cli", Permission.WRITE);
        assertThat(cli(upload(url, f, "/shared/Finance/2026-10.csv"))).as(err).isZero();
        assertThat(out).contains("/shared/Finance/2026-10.csv");

        DestinationService.AdminView d = app.destinationService.create(admin, new DestinationService.DestinationInput(
                "Partner", null, "sftp.test.invalid", 22, "/in", "fdrop", ServerAuth.HOST_KEY,
                "SHA256:" + "A".repeat(43), null, false, true));
        app.destinationService.assign(admin, d.destination().id(), "alice");
        String forward = app.folderService.create(alice, FolderType.FORWARD, "Out", List.of(d.destination().id()), 0).id();
        app.folderService.putMember(alice, forward, "app-cli", Permission.WRITE);
        assertThat(cli(upload(url, f, "/forward/Out/"))).as(err).isZero();
        assertThat(out).contains("queued for forwarding");
        assertThat(app.records.jobsForFolder(forward, 10)).hasSize(1);
    }

    @Test
    void expiresIn() throws Exception {
        Path f = file("nightly.csv", 2_000);
        assertThat(cli(upload(url, f, "/home/", "--expires-in", "2weeks", "--json"))).as(err).isZero();
        assertThat(out).contains("\"expiresIn\":\"2 weeks\"").contains("\"expiresAt\":");
        assertThat(app.files.stat(io.fdrop.core.TestSupport.app("app-cli"), "/home/nightly.csv").expiresIn())
                .isEqualTo("2 weeks");

        // checked before connecting, with the server's message
        assertThat(cli(upload(url, f, "/home/other.csv", "--expires-in", "6 dys"))).isEqualTo(CliException.USAGE);
        assertThat(err).contains("Unknown unit 'dys'");

        // limits are the server's: a folder maximum refuses a longer value
        String shared = app.folderService.create(alice, FolderType.SHARED, "Short", null, null).id();
        app.folderService.putMember(alice, shared, "app-cli", Permission.WRITE);
        app.folderService.updateExpiry(alice, shared, null, "7 days", false);
        assertThat(cli(upload(url, f, "/shared/Short/", "--expires-in", "30 days"))).isEqualTo(CliException.USAGE);
        assertThat(err).contains("this folder's maximum of '7 days'");
    }

    @Test
    void refusals() throws Exception {
        Path f = file("x.bin", 100);
        String ro = app.folderService.create(alice, FolderType.SHARED, "ReadOnly", null, null).id();
        app.folderService.putMember(alice, ro, "app-cli", Permission.READ);
        assertThat(cli(upload(url, f, "/shared/ReadOnly/"))).isEqualTo(CliException.NOT_ALLOWED);
        assertThat(cli(upload(url, f, "/shared/NotMine/"))).isEqualTo(CliException.NOT_ALLOWED);

        assertThat(cli(upload(url, f, "/home/"))).isZero();
        assertThat(cli(upload(url, f, "/home/"))).isEqualTo(CliException.EXISTS);
        assertThat(cli(upload(url, f, "/home/", "--overwrite"))).as(err).isZero();

        // a certificate fdrop does not know
        Path[] stranger = TestCerts.selfSigned("CN=app-cli").write(dir, "stranger");
        assertThat(cli("whoami", "--url", url, "--cert", stranger[0].toString(), "--key", stranger[1].toString(),
                "--ca", ca.toString())).isEqualTo(CliException.AUTH);

        // a disabled account
        app.appAccounts.update(admin, "sub-app-cli", new AppAccountService.UpdateInput(null, null, null, null,
                false));
        assertThat(cli(upload(url, f, "/home/y.bin"))).isEqualTo(CliException.AUTH);
    }

    @Test
    void aLargeFileThroughACuttingConnection() throws Exception {
        Path big = file("big.bin", 40 * 1024 * 1024);
        try (CuttingProxy proxy = new CuttingProxy(server.mtlsPort(), 6 * 1024 * 1024, 3)) {
            assertThat(cli(upload("https://localhost:" + proxy.port(), big, "/home/", "--retry-for", "2m")))
                    .as(err).isZero();
            assertThat(proxy.cuts.get()).isEqualTo(3);
        }
        assertThat(storedSha("users/sub-app-cli/big.bin")).isEqualTo(sha(big));
    }

    @Test
    void anInterruptedRunResumesWithoutResendingWhatArrived() throws Exception {
        Path big = file("resume.bin", 30 * 1024 * 1024);
        int chunks;
        try (CuttingProxy proxy = new CuttingProxy(server.mtlsPort(), Long.MAX_VALUE, 0)) {
            proxy.blackHoleAfter = 14L * 1024 * 1024;
            assertThat(cli(upload("https://localhost:" + proxy.port(), big, "/home/", "--retry-for", "3s",
                    "--parallel", "1"))).isEqualTo(CliException.GAVE_UP);
            assertThat(err).contains("run the same command again to resume");
            long storedBefore = storedChunks();
            assertThat(storedBefore).isPositive();

            proxy.blackHoleAfter = Long.MAX_VALUE;
            assertThat(cli(upload("https://localhost:" + proxy.port(), big, "/home/", "--json"))).as(err).isZero();
            assertThat(Api.JSON.readTree(out).path("resumed").asBoolean()).isTrue();
            chunks = (int) (storedChunks() - storedBefore);
        }
        // 30 MiB in 8 MiB chunks is 4; the chunks that arrived the first time were not sent again
        assertThat(chunks).isLessThan(4);
        assertThat(storedSha("users/sub-app-cli/resume.bin")).isEqualTo(sha(big));
    }

    private long storedChunks() {
        String scrape = app.metrics.scrape(null);
        for (String line : scrape.split("\n")) {
            if (line.startsWith("fdrop_upload_chunks_total{") && line.contains("result=\"stored\"")) {
                return (long) Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return 0;
    }

    /**
     * A TCP proxy in front of the mTLS listener that cuts every connection after {@code cutEvery} bytes from the
     * client, {@code cuts} times, or refuses everything once {@code blackHoleAfter} bytes have passed in total.
     * TLS goes through it untouched.
     */
    static final class CuttingProxy implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final AtomicInteger cuts = new AtomicInteger();
        final AtomicLong total = new AtomicLong();
        volatile long blackHoleAfter = Long.MAX_VALUE;
        private final int target;
        private final long cutEvery;
        private final int maxCuts;

        CuttingProxy(int target, long cutEvery, int maxCuts) throws IOException {
            this.target = target;
            this.cutEvery = cutEvery;
            this.maxCuts = maxCuts;
            Thread.ofVirtual().start(this::accept);
        }

        int port() {
            return listener.getLocalPort();
        }

        private void accept() {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    if (total.get() >= blackHoleAfter) {
                        client.close();
                        continue;
                    }
                    Socket upstream = new Socket(InetAddress.getLoopbackAddress(), target);
                    AtomicLong sent = new AtomicLong();
                    Thread.ofVirtual().start(() -> pump(client, upstream, sent, true));
                    Thread.ofVirtual().start(() -> pump(upstream, client, sent, false));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void pump(Socket from, Socket to, AtomicLong sent, boolean fromClient) {
            byte[] buf = new byte[32 * 1024];
            try (InputStream in = from.getInputStream(); OutputStream o = to.getOutputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    if (fromClient) {
                        long now = sent.addAndGet(n);
                        long all = total.addAndGet(n);
                        boolean cut = now >= cutEvery && cuts.get() < maxCuts;
                        if (cut || all >= blackHoleAfter) {
                            if (cut) {
                                cuts.incrementAndGet();
                            }
                            from.close();
                            to.close();
                            return;
                        }
                    }
                }
            } catch (IOException e) {
                // the other side closed
            } finally {
                try {
                    from.close();
                    to.close();
                } catch (IOException ignored) {
                    // already closed
                }
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
