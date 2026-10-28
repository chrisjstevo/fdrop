package io.fdrop.client;

import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.http.HttpServer;
import io.fdrop.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client against a real fdrop HTTP server in this JVM, with bearer tokens from the test identity provider.
 * Faults are put between them with a {@link Transport} that fails chosen requests.
 */
class FdropClientTest {

    private static final int MIB = 1024 * 1024;

    @TempDir
    Path dir;

    Fdrop app;
    HttpServer server;
    String url;
    final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.limits.defaultQuota = io.fdrop.core.config.DataSize.ofMiB(500);
        // the smallest chunk the server allows, so small test files still take several chunks
        cfg.upload.chunkSize = io.fdrop.core.config.DataSize.ofMiB(5);
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(Files.createDirectories(dir.resolve("files")));
        app = TestSupport.fdrop(cfg, b);
        server = new HttpServer(app).start();
        url = "https://localhost:" + server.port();
        app.accounts.ensure(TestSupport.user("alice"));
        app.accounts.ensure(TestSupport.user("bob"));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        app.close();
    }

    private FdropClient.Builder builder(String user) {
        return FdropClient.builder().baseUrl(url).sslContext(TestSupport.clientTls()).bearerToken(() -> "user:" + user)
                .maxAttempts(3);
    }

    /** A client whose transport fails the requests {@code fail} picks with a reset connection; no waiting. */
    private HttpFdropClient faulty(Predicate<HttpRequest> fail) {
        FdropClient.Builder b = builder("alice");
        HttpFdropClient[] holder = new HttpFdropClient[1];
        Transport t = new Transport() {
            @Override
            public HttpResponse<byte[]> send(HttpRequest r) throws IOException, InterruptedException {
                requests.add(r.method() + " " + r.uri().getPath());
                if (fail.test(r)) {
                    throw new IOException("Connection reset");
                }
                return holder[0].real.send(r);
            }

            @Override
            public HttpResponse<InputStream> stream(HttpRequest r) throws IOException, InterruptedException {
                return holder[0].real.stream(r);
            }
        };
        holder[0] = new Holder(b, t).client;
        return holder[0];
    }

    private Path file(String name, int bytes) throws IOException {
        byte[] data = new byte[bytes];
        new Random(bytes).nextBytes(data);
        return Files.write(dir.resolve(name), data);
    }

    private long openSessions() {
        return app.db.queryLong("SELECT COUNT(*) FROM upload_session WHERE state = 'OPEN'");
    }

    @Test
    void uploadsAFileInChunksThatLooksLikeOneCall() throws Exception {
        Path big = file("big.bin", 12 * MIB + 123);
        List<Long> progress = new CopyOnWriteArrayList<>();
        try (FdropClient fdrop = builder("alice").build()) {
            FileEntry f = fdrop.upload(big, "/home/", UploadOptions.DEFAULTS.withExpiresIn("30 days")
                    .withProgress((done, total) -> progress.add(done)));
            assertThat(f.path()).isEqualTo("/home/big.bin");
            assertThat(f.size()).isEqualTo(Files.size(big));
            assertThat(f.expiresIn()).isEqualTo("30 days");
            // 5 MiB chunks: three
            assertThat(progress).contains(Files.size(big)).hasSizeGreaterThanOrEqualTo(4);

            Path back = dir.resolve("back.bin");
            fdrop.download("/home/big.bin", back);
            assertThat(Files.mismatch(big, back)).isEqualTo(-1);
            try (InputStream in = fdrop.openDownload("/home/big.bin")) {
                assertThat(in.readAllBytes()).isEqualTo(Files.readAllBytes(big));
            }
        }
        assertThat(openSessions()).isZero();
    }

    @Test
    void uploadsFromAStreamAndAnEmptyFile() throws Exception {
        byte[] data = Files.readAllBytes(file("s.bin", 6 * MIB));
        try (FdropClient fdrop = builder("alice").build()) {
            assertThat(fdrop.mkdir("/home/in").isDirectory()).isTrue();
            assertThat(fdrop.list("/").entries()).allMatch(FileEntry::isDirectory);
            FileEntry f = fdrop.upload(new ByteArrayInputStream(data), data.length, "/home/in/s.bin",
                    UploadOptions.DEFAULTS);
            assertThat(f.size()).isEqualTo(data.length);
            FileEntry empty = fdrop.upload(file("empty.txt", 0), "/home/in/");
            assertThat(empty.size()).isZero();
            assertThat(fdrop.list("/home/in").entries()).extracting(FileEntry::name)
                    .containsExactlyInAnyOrder("s.bin", "empty.txt");

            // a stream shorter than it said is refused, and its session discarded
            assertThatThrownBy(() -> fdrop.upload(new ByteArrayInputStream(new byte[100]), 1000, "/home/in/short",
                    UploadOptions.DEFAULTS)).isInstanceOf(FdropClientException.class)
                    .hasMessageContaining("ended after 100 of the 1000 bytes");
            assertThat(fdrop.list("/home/in").entries()).extracting(FileEntry::name).doesNotContain("short");
        }
        assertThat(openSessions()).isZero();
    }

    @Test
    void refusalsAreApiExceptions() throws Exception {
        Path a = file("a.txt", 10);
        try (FdropClient fdrop = builder("alice").build()) {
            fdrop.upload(a, "/home/a.txt");
            assertThatThrownBy(() -> fdrop.upload(a, "/home/a.txt"))
                    .isInstanceOfSatisfying(FdropApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(409);
                        assertThat(e.getMessage()).contains("/home/a.txt");
                    });
            assertThat(fdrop.upload(a, "/home/a.txt", UploadOptions.DEFAULTS.withOverwrite(true)).size())
                    .isEqualTo(10);
            assertThatThrownBy(() -> fdrop.list("/home/nope")).isInstanceOfSatisfying(FdropApiException.class,
                    e -> assertThat(e.status()).isEqualTo(404));
            assertThatThrownBy(() -> fdrop.download("/home/nope.txt", dir.resolve("x")))
                    .isInstanceOfSatisfying(FdropApiException.class, e -> assertThat(e.status()).isEqualTo(404));
        }
        try (FdropClient bob = builder("bob").build()) {
            assertThatThrownBy(() -> bob.upload(a, "/shared/nothing-of-bobs/a.txt"))
                    .isInstanceOf(FdropApiException.class);
        }
        assertThat(openSessions()).isZero();
    }

    @Test
    void filesAndFolders() throws Exception {
        try (FdropClient fdrop = builder("alice").build()) {
            assertThat(fdrop.me().username()).isEqualTo("alice");
            fdrop.upload(file("r.txt", 5), "/home/r.txt");
            assertThat(fdrop.move("/home/r.txt", "/home/report.txt").path()).isEqualTo("/home/report.txt");
            assertThat(fdrop.setExpiry("/home/report.txt", "2 days").expiresIn()).isEqualTo("2 days");
            Instant at = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            assertThat(fdrop.setExpiryAt("/home/report.txt", at).expiresAt()).isEqualTo(at.toString());
            assertThat(fdrop.clearExpiry("/home/report.txt").expiresAt()).isNull();

            Folder f = fdrop.createSharedFolder("Finance");
            assertThat(f.type()).isEqualTo("SHARED");
            Folder withBob = fdrop.putMember(f.id(), "bob", Permission.READ);
            assertThat(withBob.members()).anySatisfy(m -> {
                assertThat(m.username()).isEqualTo("bob");
                assertThat(m.permission()).isEqualTo(Permission.READ);
            });
            assertThat(fdrop.folders()).extracting(Folder::name).contains("Finance");
            fdrop.upload(file("q3.txt", 3), f.path() + "/");
            try (FdropClient bob = builder("bob").build()) {
                assertThat(bob.list(f.path()).entries()).extracting(FileEntry::name).containsExactly("q3.txt");
                assertThatThrownBy(() -> bob.upload(file("b.txt", 1), f.path() + "/"))
                        .isInstanceOfSatisfying(FdropApiException.class, e -> assertThat(e.status()).isEqualTo(403));
            }
            fdrop.delete("/home/report.txt", false);
            assertThat(fdrop.list("/home").entries()).isEmpty();
            assertThat(fdrop.uploadHistory(null, 10).rows()).extracting(UploadRecord::fileName)
                    .contains("q3.txt", "r.txt");
        }
    }

    @Test
    void failedChunksAreSentAgain() throws Exception {
        Path big = file("flaky.bin", 11 * MIB);
        // every chunk's first try is cut off
        Set<String> failedOnce = ConcurrentHashMap.newKeySet();
        try (HttpFdropClient fdrop = faulty(r -> r.uri().getPath().contains("/chunks/")
                && failedOnce.add(r.uri().getPath()))) {
            FileEntry f = fdrop.upload(big, "/home/flaky.bin");
            assertThat(f.size()).isEqualTo(Files.size(big));
        }
        assertThat(failedOnce).hasSize(3);
        assertThat(requests.stream().filter(r -> r.startsWith("PUT")).count()).isEqualTo(6);
        Path back = dir.resolve("back");
        try (FdropClient fdrop = builder("alice").build()) {
            fdrop.download("/home/flaky.bin", back);
        }
        assertThat(Files.mismatch(big, back)).isEqualTo(-1);
    }

    @Test
    void givesUpAfterMaxAttemptsAndLeavesNothingBehind() throws Exception {
        Path big = file("doomed.bin", 6 * MIB);
        AtomicInteger chunkTries = new AtomicInteger();
        try (HttpFdropClient fdrop = faulty(r -> r.uri().getPath().endsWith("/chunks/1")
                && chunkTries.incrementAndGet() > 0)) {
            assertThatThrownBy(() -> fdrop.upload(big, "/home/doomed.bin"))
                    .isInstanceOfSatisfying(FdropRetriesExhaustedException.class, e -> {
                        assertThat(e.attempts()).isEqualTo(3);
                        assertThat(e.getMessage()).contains("sending chunk 2 of 2", "/home/doomed.bin",
                                "gave up after 3 attempts", "Connection reset");
                    });
        }
        assertThat(chunkTries).hasValue(3);
        assertThat(requests).contains("DELETE " + requests.stream().filter(r -> r.startsWith("PUT")).findFirst()
                .orElseThrow().replaceAll("PUT (/api/v1/uploads/[^/]+)/.*", "$1"));
        assertThat(openSessions()).isZero();
        try (FdropClient fdrop = builder("alice").build()) {
            assertThat(fdrop.list("/home").entries()).isEmpty();
        }
    }

    @Test
    void settingsAreChecked() {
        assertThatThrownBy(() -> FdropClient.builder().baseUrl("http://example.com").bearerToken(() -> "t").build())
                .hasMessageContaining("https://");
        assertThatThrownBy(() -> FdropClient.builder().baseUrl("http://localhost:1")
                .basicAuth("app", "pw".toCharArray()).build()).hasMessageContaining("HTTPS only");
        assertThatThrownBy(() -> FdropClient.builder().maxAttempts(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Builds the client with its real transport kept aside, so the faulty one can delegate to it. */
    private static final class Holder {
        final HttpFdropClient client;

        Holder(FdropClient.Builder b, Transport faulty) {
            this.client = new HttpFdropClient(b, faulty, d -> {
            });
        }
    }
}
