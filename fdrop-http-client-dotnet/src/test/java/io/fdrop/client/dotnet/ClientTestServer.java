package io.fdrop.client.dotnet;

import com.sun.net.httpserver.HttpServer;
import io.fdrop.core.FakeDirectory;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestCerts;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.DataSize;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.model.Enums.AppMethod;
import io.fdrop.core.service.AppAccountService;
import io.fdrop.storage.local.LocalStorageBackend;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A real fdrop HTTP server for the .NET client's integration tests (see ".NET client library" in SPEC.md), with the
 * test identity provider (bearer tokens like {@code user:alice}), an in-memory DB and storage in a temp folder. The
 * Maven build runs it around {@code dotnet test}:
 * <pre>
 *   ClientTestServer --clean &lt;dir&gt;          remove an old server.json
 *   ClientTestServer --run &lt;dir&gt;            start, write &lt;dir&gt;/server.json, serve until stopped
 *   ClientTestServer --await &lt;dir&gt; &lt;secs&gt;   wait for server.json
 *   ClientTestServer --stop &lt;dir&gt;           ask the running server to stop
 *   ClientTestServer --check &lt;target&gt; &lt;min&gt;  fail on failed .NET tests or line coverage below min
 * </pre>
 */
public final class ClientTestServer {

    private ClientTestServer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: --clean|--run|--await|--stop <dir> [seconds]");
        }
        Path dir = Path.of(args[1]).toAbsolutePath();
        Path json = dir.resolve("server.json");
        switch (args[0]) {
            case "--clean" -> Files.deleteIfExists(json);
            case "--run" -> run(dir, json);
            case "--await" -> await(json, Integer.parseInt(args.length > 2 ? args[2] : "60"));
            case "--stop" -> stop(json);
            case "--check" -> check(Path.of(args[1]), Double.parseDouble(args.length > 2 ? args[2] : "0"));
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    private static void run(Path dir, Path json) throws Exception {
        Files.createDirectories(dir);
        Path work = Files.createTempDirectory("fdrop-dotnet-tests");
        FdropConfig cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.http.mtls.enabled = true;
        cfg.http.mtls.port = 0;
        cfg.auth.twoFactor.require = "none";
        cfg.upload.chunkSize = DataSize.ofMiB(5);
        cfg.limits.defaultQuota = DataSize.ofGiB(4);
        cfg.limits.defaultAppQuota = DataSize.ofGiB(1);
        cfg.audit.indexDir = work.resolve("audit-index").toString();
        cfg.search.indexDir = work.resolve("search-index").toString();
        cfg.search.extract.isolation = "inprocess";
        cfg.search.commitInterval = "0s";

        TestCerts.Issued ca = TestCerts.ca("fdrop .NET client test CA");
        Path[] server = TestCerts.server(ca).write(dir, "server");
        cfg.http.tls.cert = server[0].toString();
        cfg.http.tls.key = server[1].toString();
        Path caFile = Files.writeString(dir.resolve("ca.pem"), ca.certPem());
        TestCerts.Issued appCert = TestCerts.selfSigned("CN=app-client");
        Path[] client = appCert.write(dir, "app-client");

        LocalStorageBackend b = new LocalStorageBackend();
        b.init(Files.createDirectories(work.resolve("files")));
        Fdrop app = TestSupport.fdrop(cfg, b, new FakeDirectory());
        app.search.start();
        io.fdrop.http.HttpServer http = new io.fdrop.http.HttpServer(app).start();
        Principal admin = TestSupport.admin("admin1");
        app.accounts.ensure(admin);
        app.accounts.ensure(TestSupport.user("alice"));
        app.accounts.ensure(TestSupport.user("bob"));
        app.appAccounts.create(admin, new AppAccountService.CreateInput("app-client", "dotnet tests", "alice",
                EnumSet.of(AppMethod.MTLS), List.of()));
        app.appAccounts.addCertificate(admin, "sub-app-client", new AppAccountService.CertInput(appCert.certPem(),
                null, null));

        CountDownLatch stopped = new CountDownLatch(1);
        HttpServer control = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        control.createContext("/stop", ex -> {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            stopped.countDown();
        });
        control.start();

        String body = "{\n"
                + "  \"url\": \"http://localhost:" + http.port() + "\",\n"
                + "  \"mtlsUrl\": \"https://localhost:" + http.mtlsPort() + "\",\n"
                + "  \"ca\": " + quote(caFile) + ",\n"
                + "  \"clientCert\": " + quote(client[0]) + ",\n"
                + "  \"clientKey\": " + quote(client[1]) + ",\n"
                + "  \"control\": \"http://127.0.0.1:" + control.getAddress().getPort() + "\"\n"
                + "}\n";
        Path tmp = dir.resolve("server.json.tmp");
        Files.writeString(tmp, body);
        Files.move(tmp, json, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("fdrop test server for the .NET client: " + json);

        stopped.await();
        control.stop(0);
        http.close();
        app.close();
        Files.deleteIfExists(json);
        System.exit(0);
    }

    private static String quote(Path p) {
        return "\"" + p.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void await(Path json, int seconds) throws Exception {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (!Files.exists(json)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the fdrop test server did not start within " + seconds + " s");
            }
            Thread.sleep(200);
        }
    }

    private static void stop(Path json) throws IOException, InterruptedException {
        if (!Files.exists(json)) {
            return;
        }
        Matcher m = Pattern.compile("\"control\": \"([^\"]+)\"").matcher(Files.readString(json,
                StandardCharsets.UTF_8));
        if (!m.find()) {
            return;
        }
        try (HttpClient c = HttpClient.newHttpClient()) {
            c.send(HttpRequest.newBuilder(URI.create(m.group(1) + "/stop"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // already gone
        }
    }

    /**
     * The verify step: the integration run's TRX results must exist and have no failures, and the merged line
     * coverage must reach {@code minimum}. Done here so the test server is stopped before the build fails.
     */
    private static void check(Path target, double minimum) throws IOException {
        Path trx = target.resolve("test-results/integration.trx");
        if (!Files.exists(trx)) {
            throw new IllegalStateException("no .NET integration test results in " + trx + "; see the dotnet test"
                    + " output above");
        }
        String results = Files.readString(trx, StandardCharsets.UTF_8);
        Matcher counters = Pattern.compile("<Counters total=\"(\\d+)\"[^>]*?failed=\"(\\d+)\"").matcher(results);
        if (!counters.find()) {
            throw new IllegalStateException("cannot read the counters in " + trx);
        }
        int total = Integer.parseInt(counters.group(1));
        int failed = Integer.parseInt(counters.group(2));
        Matcher errors = Pattern.compile("[^>]*?error=\"(\\d+)\"").matcher(results.substring(counters.start()));
        int crashed = errors.find() ? Integer.parseInt(errors.group(1)) : 0;
        if (total == 0 || failed > 0 || crashed > 0) {
            throw new IllegalStateException(".NET integration tests: " + failed + " failed and " + crashed
                    + " errors of " + total + " (" + trx + ")");
        }
        Path cobertura = target.resolve("coverage/coverage.cobertura.xml");
        if (!Files.exists(cobertura)) {
            throw new IllegalStateException("no coverage report at " + cobertura);
        }
        Matcher rate = Pattern.compile("<coverage line-rate=\"([0-9.]+)\"").matcher(Files.readString(cobertura,
                StandardCharsets.UTF_8));
        if (!rate.find()) {
            throw new IllegalStateException("cannot read the line rate in " + cobertura);
        }
        double lines = Double.parseDouble(rate.group(1));
        System.out.printf(".NET client: %d integration tests passed, line coverage %.1f%%%n", total, lines * 100);
        if (lines < minimum) {
            throw new IllegalStateException(String.format(".NET client line coverage is %.1f%%, below %.0f%%",
                    lines * 100, minimum * 100));
        }
    }
}
