package io.fdrop.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** File expiry over the HTTP API: upload fields and headers, PATCH, listings, folder and home settings. */
class ExpiryHttpTest {

    @TempDir
    Path dir;

    Fdrop app;
    HttpServer server;
    TestSupport.MutableClock clock;
    HttpClient http = TestSupport.httpClient();
    String base;
    final String alice = "user:alice";
    final String bob = "user:bob";

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.management.port = 0;
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir);
        clock = new TestSupport.MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        app = TestSupport.fdrop(cfg, b, clock);
        server = new HttpServer(app).start();
        base = "https://localhost:" + server.port();
        send(req("/api/v1/me", alice).build());
        send(req("/api/v1/me", bob).build());
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        app.close();
    }

    private HttpRequest.Builder req(String path, String token) {
        return HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> send(HttpRequest r) throws Exception {
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return Json.MAPPER.readTree(r.body());
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> put(String path, String token, String query, String header) throws Exception {
        HttpRequest.Builder b = req("/api/v1/files/content?path=" + q(path) + (query == null ? ""
                : "&expiresIn=" + q(query)), token).PUT(HttpRequest.BodyPublishers.ofString("hello"));
        if (header != null) {
            b.header("X-Fdrop-Expires-In", header);
        }
        return send(b.build());
    }

    private HttpResponse<String> patch(String path, String token, String body) throws Exception {
        return send(req(path, token).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build());
    }

    @Test
    void simpleUploadWithAnExpiryShowsItEverywhere() throws Exception {
        HttpResponse<String> r = put("/home/2026-10.pdf", alice, null, "6 days");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        String at = clock.instant().plus(Duration.ofDays(6)).toString();
        assertThat(json(r).get("expiresAt").asText()).isEqualTo(at);
        assertThat(json(r).get("expiresIn").asText()).isEqualTo("6 days");
        assertThat(json(r).get("expirySource").asText()).isEqualTo("upload");

        JsonNode list = json(send(req("/api/v1/files?path=/home", alice).build()));
        assertThat(list.get("entries").get(0).get("expiresAt").asText()).isEqualTo(at);
        assertThat(list.get("folderExpiry").get("allowed").asBoolean()).isTrue();
        assertThat(list.get("folderExpiry").get("serverMax").asText()).isEqualTo("10 years");

        HttpResponse<String> get = send(req("/api/v1/files/content?path=" + q("/home/2026-10.pdf"), alice).build());
        assertThat(get.headers().firstValue("X-Fdrop-Expires-At")).contains(at);

        clock.advance(Duration.ofDays(6));
        assertThat(send(req("/api/v1/files/content?path=" + q("/home/2026-10.pdf"), alice).build()).statusCode())
                .isEqualTo(404);
        assertThat(json(send(req("/api/v1/files?path=/home", alice).build())).get("entries")).isEmpty();
    }

    @Test
    void badAndConflictingValuesAreRefusedWithProblemTypes() throws Exception {
        HttpResponse<String> bad = put("/home/a.txt", alice, "1 decade", null);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("type").asText()).isEqualTo("invalid-expiry");
        assertThat(json(bad).get("detail").asText())
                .isEqualTo("Unknown unit 'decade'. Use one of sec, min, hour, day, week, month, year");

        HttpResponse<String> conflict = put("/home/a.txt", alice, "6 days", "7 days");
        assertThat(conflict.statusCode()).isEqualTo(400);
        assertThat(json(conflict).get("type").asText()).isEqualTo("conflicting-expiry");
        // the same value written two ways is not a conflict
        assertThat(put("/home/a.txt", alice, "6days", "6 days").statusCode()).isEqualTo(201);

        HttpResponse<String> reserved = put("/home/a.txt.fdrop-expires", alice, null, null);
        assertThat(json(reserved).get("type").asText()).isEqualTo("reserved-name");

        HttpResponse<String> metrics = send(req("/api/v1/me", alice).build());
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(app.metrics.scrape(null)).contains("fdrop_expiry_refused_total{protocol=\"http\",reason=\"syntax\"}");
    }

    @Test
    void patchChangesAndClearsTheExpiry() throws Exception {
        put("/home/a.txt", alice, "1 day", null);
        clock.advance(Duration.ofHours(23));
        HttpResponse<String> r = patch("/api/v1/files?path=" + q("/home/a.txt"), alice, "{\"expiresIn\":\"2weeks\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("expirySource").asText()).isEqualTo("changed");
        assertThat(json(r).get("expiresAt").asText()).isEqualTo(clock.instant().plus(Duration.ofDays(14)).toString());

        HttpResponse<String> date = patch("/api/v1/files?path=" + q("/home/a.txt"), alice,
                "{\"expiresAt\":\"" + clock.instant().plus(Duration.ofDays(3)) + "\"}");
        assertThat(json(date).get("expiresIn")).isNull();

        HttpResponse<String> cleared = patch("/api/v1/files?path=" + q("/home/a.txt"), alice, "{\"expiresAt\":null}");
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(json(cleared).get("expiresAt")).isNull();

        HttpResponse<String> both = patch("/api/v1/files?path=" + q("/home/a.txt"), alice,
                "{\"expiresIn\":\"1 day\",\"expiresAt\":null}");
        assertThat(both.statusCode()).isEqualTo(400);
    }

    @Test
    void resumableUploadEchoesTheFileExpiry() throws Exception {
        HttpResponse<String> r = send(req("/api/v1/uploads", alice).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"/home/r.bin\",\"size\":5,"
                        + "\"expiresIn\":\"6 days\"}")).build());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(json(r).get("fileExpiresIn").asText()).isEqualTo("6 days");
        String id = json(r).get("id").asText();
        send(req("/api/v1/uploads/" + id + "/chunks/0", alice).PUT(HttpRequest.BodyPublishers.ofString("hello")).build());
        HttpResponse<String> done = send(req("/api/v1/uploads/" + id + "/complete", alice)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        assertThat(json(done).get("file").get("expiresIn").asText()).isEqualTo("6 days");
    }

    @Test
    void folderAndHomeSettings() throws Exception {
        HttpResponse<String> f = send(req("/api/v1/folders", alice).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"shared\",\"name\":\"Invoices\"}")).build());
        String id = json(f).get("id").asText();
        put("/shared/Invoices/old.pdf", alice, null, null);

        HttpResponse<String> r = patch("/api/v1/folders/" + id, alice,
                "{\"defaultExpiry\":\"7days\",\"maxExpiry\":\"30 days\",\"applyToExisting\":true}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("defaultExpiry").asText()).isEqualTo("7 days");
        assertThat(json(r).get("maxExpiry").asText()).isEqualTo("30 days");
        assertThat(json(r).get("expiryFilesChanged").asInt()).isEqualTo(1);

        JsonNode list = json(send(req("/api/v1/files?path=" + q("/shared/Invoices"), alice).build()));
        assertThat(list.get("folderExpiry").get("default").asText()).isEqualTo("7 days");
        assertThat(list.get("folderExpiry").get("max").asText()).isEqualTo("30 days");
        HttpResponse<String> tooLong = put("/shared/Invoices/new.pdf", alice, null, "2 years");
        assertThat(json(tooLong).get("detail").asText())
                .isEqualTo("'2 years' is longer than this folder's maximum of '30 days', set by the folder owner");

        HttpResponse<String> cleared = patch("/api/v1/folders/" + id, alice, "{\"maxExpiry\":null}");
        assertThat(json(cleared).has("maxExpiry")).isFalse();

        HttpResponse<String> me = patch("/api/v1/me", alice, "{\"homeDefaultExpiry\":\"30 days\"}");
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(json(me).get("expiry").get("homeDefault").asText()).isEqualTo("30 days");
        assertThat(json(put("/home/x.txt", alice, null, null)).get("expirySource").asText()).isEqualTo("homeDefault");
    }
}
