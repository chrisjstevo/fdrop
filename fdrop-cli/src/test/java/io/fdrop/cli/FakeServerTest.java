package io.fdrop.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.TestCerts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI against a scripted HTTPS server that requires a client certificate: the whole protocol, resuming, retries
 * after dropped connections, and the exit code for every kind of failure. Time is faked, so retries do not wait.
 */
class FakeServerTest {

    private static final int CHUNK = 1000;

    @TempDir
    Path dir;

    FakeFdrop server;
    BackoffTest.FakeClock clock = new BackoffTest.FakeClock();
    Path cert;
    Path key;
    Path ca;
    Path file;
    byte[] data;
    String out;
    String err;

    @BeforeEach
    void start() throws Exception {
        server = new FakeFdrop(CHUNK);
        Path[] client = TestCerts.selfSigned("CN=app-test").write(dir, "client");
        cert = client[0];
        key = client[1];
        ca = dir.resolve("server-ca.pem");
        Files.writeString(ca, server.ca.certPem());
        data = new byte[5_500];
        new Random(7).nextBytes(data);
        file = dir.resolve("report.bin");
        Files.write(file, data);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private int run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        FdropCli.Context ctx = new FdropCli.Context(Map.of(), new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8), clock, false, dir.resolve("state"), false);
        int code = FdropCli.run(args, ctx);
        out = o.toString(StandardCharsets.UTF_8);
        err = e.toString(StandardCharsets.UTF_8);
        return code;
    }

    private String[] upload(String... extra) {
        List<String> a = new ArrayList<>(List.of("upload", file.toString(), "/home/", "--url", server.url(),
                "--cert", cert.toString(), "--key", key.toString(), "--ca", ca.toString()));
        a.addAll(List.of(extra));
        return a.toArray(new String[0]);
    }

    private long stateFiles() throws Exception {
        Path d = dir.resolve("state");
        try (var s = Files.exists(d) ? Files.list(d) : java.util.stream.Stream.<Path>empty()) {
            return s.count();
        }
    }

    @Test
    void uploadsEveryChunkWithItsHash() throws Exception {
        assertThat(run(upload())).as(err).isEqualTo(0);
        assertThat(server.only().assembled()).isEqualTo(data);
        assertThat(server.only().path).isEqualTo("/home/report.bin");
        assertThat(server.chunkPuts.get()).isEqualTo(6);
        assertThat(out).startsWith("Uploaded /home/report.bin (5.4 KB, sha256 " + FakeFdrop.sha(data) + ")");
        // no request without a client certificate, and every one says who it is
        assertThat(server.clientCertSeen).isNotEmpty().allMatch(Boolean::booleanValue);
        assertThat(server.userAgents).allSatisfy(ua -> assertThat(ua).startsWith("fdrop-cli/"));
        assertThat(stateFiles()).isZero();
    }

    @Test
    void jsonResult() throws Exception {
        assertThat(run(upload("--json", "--parallel", "1"))).isEqualTo(0);
        JsonNode n = Api.JSON.readTree(out);
        assertThat(n.path("path").asText()).isEqualTo("/home/report.bin");
        assertThat(n.path("size").asLong()).isEqualTo(5_500);
        assertThat(n.path("sha256").asText()).isEqualTo(FakeFdrop.sha(data));
        assertThat(n.path("resumed").asBoolean()).isFalse();
    }

    @Test
    void droppedConnectionsAndConflictsAreRetried() throws Exception {
        server.chunkFaults.add(new FakeFdrop.Drop());
        server.chunkFaults.add(new FakeFdrop.Status(503, "busy"));
        server.chunkFaults.add(new FakeFdrop.Status(409, "chunk is already being uploaded"));
        assertThat(run(upload("--parallel", "1"))).as(err).isEqualTo(0);
        assertThat(server.only().assembled()).isEqualTo(data);
        assertThat(clock.sleeps).hasSizeGreaterThanOrEqualTo(3);
        // after each failed round the client asked the server what it holds
        assertThat(server.statusPolls.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void aRunThatGaveUpIsResumedByTheNext() throws Exception {
        server.okChunksBeforeFailing = 3;
        server.chunkFaultsAfter = new FakeFdrop.Status(503, "down");
        assertThat(run(upload("--parallel", "1", "--retry-for", "30s"))).isEqualTo(CliException.GAVE_UP);
        assertThat(err).contains("run the same command again to resume");
        assertThat(Duration.ofNanos(clock.now)).isEqualTo(Duration.ofSeconds(30));
        assertThat(stateFiles()).isEqualTo(1);

        server.chunkFaultsAfter = null;
        int before = server.chunkPuts.get();
        assertThat(run(upload("--parallel", "1"))).as(err).isEqualTo(0);
        assertThat(out).contains("resumed");
        // only the three chunks the server did not have were sent again
        assertThat(server.chunkPuts.get() - before).isEqualTo(3);
        assertThat(server.only().assembled()).isEqualTo(data);
        assertThat(stateFiles()).isZero();
    }

    @Test
    void noResumeStartsAFreshSession() throws Exception {
        server.okChunksBeforeFailing = 2;
        server.chunkFaultsAfter = new FakeFdrop.Status(503, "down");
        run(upload("--parallel", "1", "--retry-for", "0s"));
        server.chunkFaultsAfter = null;
        int before = server.chunkPuts.get();
        assertThat(run(upload("--no-resume"))).isEqualTo(0);
        assertThat(server.chunkPuts.get() - before).isEqualTo(6);
    }

    @Test
    void aSavedSessionTheServerForgotStartsAgain() throws Exception {
        server.okChunksBeforeFailing = 2;
        server.chunkFaultsAfter = new FakeFdrop.Status(503, "down");
        run(upload("--parallel", "1", "--retry-for", "0s"));
        server.sessions.clear();
        server.chunkFaultsAfter = null;
        assertThat(run(upload())).as(err).isEqualTo(0);
        assertThat(out).doesNotContain("resumed");
        assertThat(server.only().assembled()).isEqualTo(data);
    }

    @Test
    void waitsForAssembly() throws Exception {
        server.assembleLater = true;
        assertThat(run(upload())).as(err).isEqualTo(0);
        assertThat(server.only().state).isEqualTo("COMPLETED");
    }

    @Test
    void failedAssemblyIsAbortedWithExitCodeSix() throws Exception {
        server.assembleLater = true;
        server.assemblyFails = true;
        assertThat(run(upload())).isEqualTo(CliException.SERVER);
        assertThat(err).contains("assembly failed: disk full");
        assertThat(server.deletes.get()).isEqualTo(1);
    }

    @Test
    void exitCodesForWhatTheServerSays() {
        server.createFaults.add(new FakeFdrop.Status(409, "exists"));
        assertThat(run(upload())).isEqualTo(CliException.EXISTS);
        assertThat(err).contains("--overwrite");

        server.createFaults.add(new FakeFdrop.Status(403, "read-only member"));
        assertThat(run(upload())).isEqualTo(CliException.NOT_ALLOWED);
        assertThat(err).contains("read-only member");

        server.createFaults.add(new FakeFdrop.Status(413, "quota exceeded"));
        assertThat(run(upload())).isEqualTo(CliException.NOT_ALLOWED);

        server.createFaults.add(new FakeFdrop.Status(404, "no such folder"));
        assertThat(run(upload())).isEqualTo(CliException.NOT_ALLOWED);

        server.createFaults.add(new FakeFdrop.Status(401, "application account sign-in refused"));
        assertThat(run(upload())).isEqualTo(CliException.AUTH);

        server.chunkFaults.add(new FakeFdrop.Status(400, "chunk does not match"));
        assertThat(run(upload())).isEqualTo(CliException.SERVER);
    }

    @Test
    void anUntrustedServerIsRefused() {
        List<String> args = new ArrayList<>(List.of(upload()));
        int i = args.indexOf("--ca");
        args.remove(i + 1);
        args.remove(i);
        assertThat(run(args.toArray(new String[0]))).isEqualTo(CliException.AUTH);
        assertThat(err).contains("TLS handshake");
        assertThat(server.sessions).isEmpty();
    }

    @Test
    void givingUpOnAnUnreachableServer() {
        server.createFaults.add(new FakeFdrop.Status(503, "starting"));
        server.createFaults.add(new FakeFdrop.Status(503, "starting"));
        assertThat(run(upload())).as(err).isEqualTo(0);

        for (int i = 0; i < 100; i++) {
            server.createFaults.add(new FakeFdrop.Status(503, "down"));
        }
        assertThat(run(upload("--retry-for", "2m", "--overwrite"))).isEqualTo(CliException.GAVE_UP);
    }

    @Test
    void usageErrors() throws Exception {
        assertThat(run("upload", file.toString(), "/home/", "--url", server.url())).isEqualTo(CliException.USAGE);
        assertThat(err).contains("--cert");
        assertThat(run(upload("--parallel", "9"))).isEqualTo(CliException.USAGE);
        assertThat(run(upload("--retry-for", "forever"))).isEqualTo(CliException.USAGE);
        assertThat(run("upload", dir.resolve("missing").toString(), "/home/", "--url", server.url(), "--cert",
                cert.toString(), "--key", key.toString(), "--ca", ca.toString())).isEqualTo(CliException.USAGE);
        assertThat(run("upload", file.toString(), "/home/", "--url", "http://insecure", "--cert", cert.toString(),
                "--key", key.toString())).isEqualTo(CliException.USAGE);
        assertThat(err).contains("https://");
        assertThat(run("upload", "--bogus")).isEqualTo(CliException.USAGE);
        assertThat(run()).isEqualTo(CliException.USAGE);
        assertThat(run("--version")).isEqualTo(0);
        assertThat(out).startsWith("fdrop-cli ");
    }

    @Test
    void whoami() throws Exception {
        assertThat(run("whoami", "--url", server.url(), "--cert", cert.toString(), "--key", key.toString(), "--ca",
                ca.toString())).isEqualTo(0);
        assertThat(out).startsWith("app-test (application account) at " + server.url());
        assertThat(run("whoami", "--json", "--url", server.url(), "--cert", cert.toString(), "--key", key.toString(),
                "--ca", ca.toString())).isEqualTo(0);
        assertThat(Api.JSON.readTree(out).path("kind").asText()).isEqualTo("application");
    }
}
