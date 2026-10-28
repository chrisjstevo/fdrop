package io.fdrop.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.http.HttpServer;
import io.fdrop.http.Json;
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

import static org.assertj.core.api.Assertions.assertThat;

/** The search API over HTTP: results as plain segments, suggestions, problems, the admin view. */
class SearchHttpTest {

    @TempDir
    Path dir;

    Fdrop app;
    HttpServer server;
    HttpClient http = TestSupport.httpClient();
    String base;

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.search.indexDir = dir.resolve("index").toString();
        cfg.search.extract.isolation = "inprocess";
        cfg.search.commitInterval = "0s";
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("files"));
        app = TestSupport.fdrop(cfg, b);
        server = new HttpServer(app).start();
        base = "https://localhost:" + server.port();
        for (String u : new String[]{"user:alice", "user:carol", "admin2fa:root"}) {
            get("/api/v1/me", u);
        }
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        app.close();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void searchSuggestAndAdmin() throws Exception {
        HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/files/content?path="
                        + q("/home/credit-note-0412.txt"))).header("Authorization", "Bearer user:alice")
                .PUT(HttpRequest.BodyPublishers.ofString("This credit note is issued to Acme Ltd and cancels invoice"
                        + " 2026-0391.")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).as(put.body()).isEqualTo(201);
        ((LuceneSearchService) app.search).indexNow();

        HttpResponse<String> r = get("/api/v1/search?q=" + q("\"credit note\" acme"), "user:alice");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = Json.MAPPER.readTree(r.body());
        assertThat(body.get("total").asInt()).isEqualTo(1);
        JsonNode hit = body.get("results").get(0);
        assertThat(hit.get("path").asText()).isEqualTo("/home/credit-note-0412.txt");
        assertThat(hit.get("location").asText()).isEqualTo("Home");
        assertThat(hit.get("contents").asText()).isEqualTo("ok");
        boolean marked = false;
        for (JsonNode seg : hit.get("snippets").get(0)) {
            marked |= seg.path("match").asBoolean() && seg.get("text").asText().equalsIgnoreCase("acme");
        }
        assertThat(marked).isTrue();

        assertThat(Json.MAPPER.readTree(get("/api/v1/search?q=acme", "user:carol").body()).get("total").asInt())
                .isZero();
        JsonNode sugg = Json.MAPPER.readTree(get("/api/v1/search/suggest?q=cred", "user:alice").body());
        assertThat(sugg.get(0).get("path").asText()).isEqualTo("/home/credit-note-0412.txt");
        assertThat(Json.MAPPER.readTree(get("/api/v1/search/suggest?q=cred", "user:carol").body())).isEmpty();

        HttpResponse<String> bad = get("/api/v1/search?q=" + q("a*"), "user:alice");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(Json.MAPPER.readTree(bad.body()).get("type").asText()).isEqualTo("invalid-search");

        assertThat(get("/api/v1/admin/search", "user:alice").statusCode()).isEqualTo(403);
        HttpResponse<String> status = get("/api/v1/admin/search", "admin2fa:root");
        assertThat(status.statusCode()).as(status.body()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(status.body()).get("documents").asInt()).isEqualTo(1);
    }
}
