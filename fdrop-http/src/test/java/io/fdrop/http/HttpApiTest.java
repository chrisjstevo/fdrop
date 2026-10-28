package io.fdrop.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.util.Hashing;
import io.fdrop.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The HTTP API end to end over a real socket, with a fake identity provider. */
class HttpApiTest {

    @TempDir
    Path dir;

    Fdrop app;
    HttpServer server;
    ManagementServer mgmt;
    HttpClient http = TestSupport.httpClient();
    String base;

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.management.port = 0;
        cfg.http.mtls.enabled = true;
        cfg.http.mtls.port = 0;
        cfg.http.simpleUploadMax = io.fdrop.core.config.DataSize.ofMiB(1);
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir);
        app = TestSupport.fdrop(cfg, b);
        server = new HttpServer(app).start();
        mgmt = new ManagementServer(app).start();
        base = "https://localhost:" + server.port();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        mgmt.close();
        app.close();
    }

    private HttpRequest.Builder req(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return Json.MAPPER.readTree(r.body());
    }

    @Test
    void requiresSignIn() throws Exception {
        HttpResponse<String> r = http.send(req("/api/v1/me", null).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(json(r).get("status").asInt()).isEqualTo(401);

        HttpResponse<String> bad = http.send(req("/api/v1/me", "garbage").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(401);

        HttpResponse<String> me = http.send(req("/api/v1/me", "user:alice").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json(me).get("username").asText()).isEqualTo("alice");

        // users without the standard role cannot touch files
        HttpResponse<String> nobody = http.send(req("/api/v1/files?path=/home", "nobody:eve").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(nobody.statusCode()).isEqualTo(403);
    }

    @Test
    void simpleUploadListDownloadRangeAndEtag() throws Exception {
        String token = "user:alice";
        http.send(req("/api/v1/me", token).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> mk = http.send(req("/api/v1/files/mkdir", token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"/home/docs\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(mk.statusCode()).isEqualTo(201);

        byte[] data = new byte[300_000];
        new Random(1).nextBytes(data);
        HttpResponse<String> put = http.send(req("/api/v1/files/content?path=" + q("/home/docs/report ü.bin"), token)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).as(put.body()).isEqualTo(201);
        assertThat(json(put).get("size").asLong()).isEqualTo(data.length);

        HttpResponse<String> again = http.send(req("/api/v1/files/content?path=" + q("/home/docs/report ü.bin"), token)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(again.statusCode()).isEqualTo(409);

        HttpResponse<String> list = http.send(req("/api/v1/files?path=/home/docs", token).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(json(list).get("entries").get(0).get("name").asText()).isEqualTo("report ü.bin");

        HttpResponse<byte[]> get = http.send(req("/api/v1/files/content?path=" + q("/home/docs/report ü.bin"), token)
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(data);
        assertThat(get.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;")
                .contains("filename*=UTF-8''report%20%C3%BC.bin");
        assertThat(get.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        String etag = get.headers().firstValue("ETag").orElseThrow();

        HttpResponse<byte[]> notModified = http.send(req("/api/v1/files/content?path=" + q("/home/docs/report ü.bin"),
                token).header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(notModified.statusCode()).isEqualTo(304);

        HttpResponse<byte[]> range = http.send(req("/api/v1/files/content?path=" + q("/home/docs/report ü.bin"), token)
                .header("Range", "bytes=1000-1999").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(range.statusCode()).isEqualTo(206);
        assertThat(range.headers().firstValue("Content-Range")).contains("bytes 1000-1999/300000");
        assertThat(range.body()).isEqualTo(Arrays.copyOfRange(data, 1000, 2000));

        HttpResponse<String> mv = http.send(req("/api/v1/files/move", token).POST(HttpRequest.BodyPublishers.ofString(
                "{\"from\":\"/home/docs/report ü.bin\",\"to\":\"/home/report.bin\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(mv.statusCode()).as(mv.body()).isEqualTo(200);
        HttpResponse<String> del = http.send(req("/api/v1/files?path=" + q("/home/report.bin"), token).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(del.statusCode()).isEqualTo(204);

        HttpResponse<String> hist = http.send(req("/api/v1/me/uploads", token).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode row = json(hist).get("rows").get(0);
        assertThat(row.get("status").asText()).isEqualTo("DELETED");
        assertThat(row.get("clientIp").asText()).isIn("127.0.0.1", "::1");
        assertThat(row.get("wentTo").asText()).isEqualTo("HOME");

        HttpResponse<String> tooBig = http.send(req("/api/v1/files/content?path=/home/big", token)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[2 * 1024 * 1024])).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tooBig.statusCode()).isEqualTo(413);
    }

    @Test
    void resumableUploadOverHttp() throws Exception {
        String token = "user:bob";
        byte[] data = new byte[12 * 1024 * 1024 + 17];
        new Random(2).nextBytes(data);
        HttpResponse<String> create = http.send(req("/api/v1/uploads", token).POST(HttpRequest.BodyPublishers.ofString(
                "{\"path\":\"/home/big.iso\",\"size\":" + data.length + ",\"sha256\":\"" + Hashing.sha256Hex(data)
                        + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(create.statusCode()).as(create.body()).isEqualTo(201);
        JsonNode s = json(create);
        String id = s.get("id").asText();
        int chunkSize = s.get("chunkSize").asInt();
        int count = s.get("chunkCount").asInt();
        assertThat(count).isEqualTo(2);

        for (int i = count - 1; i >= 0; i--) {
            byte[] chunk = Arrays.copyOfRange(data, i * chunkSize, Math.min(data.length, (i + 1) * chunkSize));
            HttpResponse<String> put = http.send(req("/api/v1/uploads/" + id + "/chunks/" + i, token)
                    .header("X-Chunk-SHA256", Hashing.sha256Hex(chunk))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
            if (i == count - 1) {
                HttpResponse<String> st = http.send(req("/api/v1/uploads/" + id, token).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(json(st).get("received").toString()).isEqualTo("[[1,1]]");
            }
        }
        // wrong length for a chunk is refused
        HttpResponse<String> wrong = http.send(req("/api/v1/uploads/" + id + "/chunks/0", token)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[10])).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(wrong.statusCode()).isEqualTo(400);

        HttpResponse<String> done = http.send(req("/api/v1/uploads/" + id + "/complete", token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        assertThat(json(done).get("state").asText()).isEqualTo("COMPLETED");

        HttpResponse<byte[]> get = http.send(req("/api/v1/files/content?path=/home/big.iso", token).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.body()).isEqualTo(data);
    }

    @Test
    void staticFallbackAndManagementPort() throws Exception {
        HttpResponse<String> index = http.send(req("/some/client/route", null).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(index.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("default-src 'self'");
        assertThat(index.headers().firstValue("Strict-Transport-Security"))
                .contains("max-age=31536000; includeSubDomains");
        HttpResponse<String> apiMissing = http.send(req("/api/v1/nope", "user:alice").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(apiMissing.statusCode()).isEqualTo(404);
        HttpResponse<String> wrongMethod = http.send(req("/api/v1/uploads", "user:alice").DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(wrongMethod.statusCode()).isEqualTo(405);

        String m = "https://localhost:" + mgmt.port();
        HttpResponse<String> metrics = http.send(HttpRequest.newBuilder(URI.create(m + "/metrics")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("fdrop_http_requests_seconds_bucket").contains("fdrop_build_info")
                .contains("jvm_memory_used_bytes").contains("hikaricp_connections");
        assertThat(metrics.body()).doesNotContain("alice");
        HttpResponse<String> ready = http.send(HttpRequest.newBuilder(URI.create(m + "/readyz")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(ready.body()).contains("\"degraded\"");
        HttpResponse<String> main = http.send(req("/metrics", null).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(main.body()).doesNotContain("fdrop_build_info");
    }

    @Test
    void metricsTokenIsEnforcedWhenSet() throws Exception {
        app.config.management.metricsToken = "s3cret";
        String m = "https://localhost:" + mgmt.port() + "/metrics";
        assertThat(http.send(HttpRequest.newBuilder(URI.create(m)).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(URI.create(m)).header("Authorization", "Bearer s3cret").build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    void plainHttpIsAnsweredOnNoPort() throws Exception {
        for (int port : new int[]{server.port(), server.mtlsPort(), mgmt.port()}) {
            assertThat(plainHttp(port)).as("port " + port).doesNotStartWith("HTTP/");
            assertThatThrownBy(() -> http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/healthz")).build(), HttpResponse.BodyHandlers.ofString())).as("port " + port)
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    /** What a listener answers to a plain-HTTP request: never an HTTP response, at most a TLS alert. */
    private static String plainHttp(int port) throws Exception {
        try (Socket s = new Socket("localhost", port)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write("GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (java.net.SocketException e) {
            return "";   // reset: the TLS handler refused the bytes
        }
    }

    @Test
    void noListenerStartsWithoutACertificate() {
        String cert = app.config.http.tls.cert;
        try {
            app.config.http.tls.cert = null;
            assertThatThrownBy(() -> new HttpServer(app).start()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("http.tls.cert and http.tls.key are required");
            assertThatThrownBy(() -> new ManagementServer(app).start()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTPS only");
            app.config.http.tls.cert = dir.resolve("missing.crt").toString();
            assertThatThrownBy(() -> new HttpServer(app).start()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot read the TLS certificate");
        } finally {
            app.config.http.tls.cert = cert;
        }
    }

    private JsonNode send(String method, String path, String token, String body) throws Exception {
        HttpResponse<String> r = http.send(req(path, token).method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(method + " " + path + ": " + r.body()).isLessThan(300);
        return r.body().isEmpty() ? null : json(r);
    }

    @Test
    void forwardFoldersWithSeveralDestinations() throws Exception {
        String admin = "admin2fa:root";
        String alice = "user:alice";
        send("GET", "/api/v1/me", alice, null);
        String[] ids = new String[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = send("POST", "/api/v1/admin/forward-destinations", admin, "{\"name\":\"Partner " + i
                    + "\",\"host\":\"sftp.test.invalid\",\"port\":22,\"remoteDir\":\"/in" + i
                    + "\",\"username\":\"fdrop\",\"serverAuth\":\"HOST_KEY\",\"hostKeySha256\":\"SHA256:abc\"}")
                    .get("destination").get("id").asText();
            send("PUT", "/api/v1/admin/forward-destinations/" + ids[i] + "/assignments/alice", admin, null);
        }
        JsonNode me = send("GET", "/api/v1/me", alice, null);
        assertThat(me.get("settings").get("maxDestinationsPerFolder").asInt()).isEqualTo(5);

        JsonNode folder = send("POST", "/api/v1/folders", alice, "{\"type\":\"forward\",\"name\":\"Out\","
                + "\"destinationIds\":[\"" + ids[0] + "\",\"" + ids[1] + "\"]}");
        assertThat(folder.get("destinations")).hasSize(2);
        assertThat(folder.get("destinationId").asText()).isEqualTo(ids[0]);
        assertThat(folder.get("destinationName").asText()).isEqualTo("Partner 0, Partner 1");
        String fid = folder.get("id").asText();

        // a client written for one destination still works
        JsonNode old = send("POST", "/api/v1/folders", alice, "{\"type\":\"forward\",\"name\":\"Old\","
                + "\"destinationId\":\"" + ids[2] + "\"}");
        assertThat(old.get("destinations")).hasSize(1);

        HttpResponse<String> notArray = http.send(req("/api/v1/folders", alice).POST(HttpRequest.BodyPublishers
                .ofString("{\"type\":\"forward\",\"name\":\"Bad\",\"destinationIds\":\"x\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(notArray.statusCode()).isEqualTo(400);

        send("PUT", "/api/v1/files/content?path=" + q("/forward/Out/a.txt"), alice, "a");
        JsonNode jobs = send("GET", "/api/v1/folders/" + fid + "/forward-jobs", alice, null);
        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).get("uploadRecordId").asText()).isEqualTo(jobs.get(1).get("uploadRecordId").asText());
        assertThat(jobs.findValuesAsText("destinationName")).containsExactlyInAnyOrder("Partner 0", "Partner 1");

        // the worker gives up on one destination; the owner stops sending there through the API
        String failed = jobs.get(0).get("id").asText();
        app.records.updateJob(failed, io.fdrop.core.model.Enums.JobState.FAILED, "rejected", 0, 1L, null);
        JsonNode row = send("GET", "/api/v1/me/uploads?wentTo=forward", alice, null).get("rows").get(0);
        assertThat(row.get("status").asText()).isEqualTo("FAILED");
        assertThat(row.get("destinations")).hasSize(2);
        assertThat(row.get("jobId").asText()).isEqualTo(failed);
        assertThat(row.get("canRetry").asBoolean()).isTrue();
        send("POST", "/api/v1/folders/" + fid + "/forward-jobs/" + failed + "/cancel", alice,
                "{\"reason\":\"partner gone\"}");
        JsonNode after = send("GET", "/api/v1/folders/" + fid + "/forward-jobs", alice, null);
        assertThat(after.findValuesAsText("state")).contains("CANCELLED");
        assertThat(after.findValuesAsText("cancelReason")).contains("partner gone");

        // PATCH changes the destinations, here adding the third and sending it the file still in the folder
        JsonNode patched = send("PATCH", "/api/v1/folders/" + fid, alice, "{\"destinationIds\":[\"" + ids[0]
                + "\",\"" + ids[1] + "\",\"" + ids[2] + "\"],\"sendExisting\":true}");
        assertThat(patched.get("destinations")).hasSize(3);
        assertThat(send("GET", "/api/v1/folders/" + fid + "/forward-jobs", alice, null)).hasSize(3);
    }
}
