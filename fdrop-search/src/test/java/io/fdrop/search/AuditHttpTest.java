package io.fdrop.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.audit.Audit;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The audit log API with the Lucene audit index: access, search syntax, facets, paging, export, chain check. */
class AuditHttpTest {

    @TempDir
    Path dir;

    Fdrop app;
    HttpServer server;
    HttpClient http = HttpClient.newHttpClient();
    String base;

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.search.indexDir = dir.resolve("search").toString();
        cfg.search.extract.isolation = "inprocess";
        cfg.audit.indexDir = dir.resolve("audit").toString();
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("files"));
        app = TestSupport.fdrop(cfg, b);
        server = new HttpServer(app).start();
        base = "http://localhost:" + server.port();

        Audit.log("app.login", "user", "erp-export", "ip", "198.51.100.23", "protocol", "HTTP", "outcome", "refused",
                "reason", "address not allowed");
        Audit.log("login", "user", "alice", "ip", "10.20.3.4", "protocol", "HTTP");
        Audit.log("login", "user", "bob", "ip", "10.21.0.9", "protocol", "SFTP");
        Audit.log("login.refused", "user", "Mallory", "ip", "2001:db8::7", "protocol", "SFTP");
        Audit.log("upload", "user", "alice", "ip", "10.20.3.4", "protocol", "HTTP", "path",
                "/shared/Finance/q3-zebrafish-report.pdf", "size", 2048);
        Audit.log("download", "user", "bob", "ip", "10.21.0.9", "protocol", "HTTP", "path",
                "/shared/Finance/q3-zebrafish-report.pdf", "size", 2048);
        Audit.log("download", "user", "bob", "ip", "10.21.0.9", "protocol", "HTTP", "path",
                "/shared/FinanceOld/x.pdf", "size", 1);
        index().indexNow();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        app.close();
    }

    private LuceneAuditIndex index() {
        return (LuceneAuditIndex) app.audit.index();
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, "admin2fa:root");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.MAPPER.readTree(r.body());
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static List<String> users(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("events").forEach(e -> out.add(e.get("user").asText()));
        return out;
    }

    private JsonNode search(String query) throws Exception {
        return ok("/api/v1/admin/audit?q=" + q(query));
    }

    @Test
    void onlyAdminsWithTwoFactorsSeeIt() throws Exception {
        assertThat(send("GET", "/api/v1/admin/audit", "user:alice").statusCode()).isEqualTo(403);
        // an admin's token without a second factor is not accepted at all
        assertThat(send("GET", "/api/v1/admin/audit", "admin:root").statusCode()).isIn(401, 403);
        assertThat(send("GET", "/api/v1/admin/audit/export?format=csv", "user:alice").statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/v1/admin/audit/verify", "user:alice").statusCode()).isEqualTo(403);

        JsonNode page = ok("/api/v1/admin/audit");
        assertThat(page.get("total").asInt()).isGreaterThanOrEqualTo(7);
        // read from the DB, not the index
        assertThat(page.hasNonNull("indexedUpTo")).isFalse();
    }

    @Test
    void fieldSyntaxAndFreeText() throws Exception {
        assertThat(users(search("user:erp-export outcome:refused"))).containsExactly("erp-export");
        assertThat(users(search("ip:10.20.0.0/16"))).containsExactly("alice", "alice");
        assertThat(users(search("ip:10.0.0.0/8 protocol:sftp"))).containsExactly("bob");
        assertThat(users(search("ip:2001:db8::/32"))).containsExactly("Mallory");
        assertThat(users(search("user:mallory"))).containsExactly("Mallory");
        assertThat(users(search("ip:198.51.100.23"))).containsExactly("erp-export");
        assertThat(search("path:/shared/Finance -event:download").get("events").get(0).get("event").asText())
                .isEqualTo("upload");
        assertThat(search("path:/shared/Finance").get("total").asInt()).isEqualTo(2);
        assertThat(search("zebrafish").get("total").asInt()).isEqualTo(2);
        assertThat(search("\"address not allowed\"").get("total").asInt()).isEqualTo(1);
        assertThat(users(search("group:sign-in -user:alice"))).contains("erp-export", "bob", "Mallory")
                .doesNotContain("alice");
        assertThat(search("event:login*").get("total").asInt()).isGreaterThanOrEqualTo(3);

        HttpResponse<String> bad = send("GET", "/api/v1/admin/audit?q=" + q("ip:not-an-ip"), "admin2fa:root");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("invalid-audit-search");
    }

    @Test
    void facetsFiltersAndPaging() throws Exception {
        JsonNode r = ok("/api/v1/admin/audit?user=bob");
        assertThat(r.get("total").asInt()).isEqualTo(3);
        assertThat(r.get("facets").get("group").get("Sign-in").asInt()).isEqualTo(1);
        assertThat(r.get("facets").get("group").get("Files").asInt()).isEqualTo(2);
        assertThat(r.hasNonNull("indexedUpTo")).isTrue();

        // a selected group still shows the counts of the others
        JsonNode files = ok("/api/v1/admin/audit?user=bob&group=Files");
        assertThat(files.get("total").asInt()).isEqualTo(2);
        assertThat(files.get("facets").get("group").get("Sign-in").asInt()).isEqualTo(1);

        JsonNode p1 = ok("/api/v1/admin/audit?user=bob&limit=2");
        assertThat(p1.get("events")).hasSize(2);
        JsonNode p2 = ok("/api/v1/admin/audit?user=bob&limit=2&cursor=" + p1.get("nextCursor").asText());
        assertThat(p2.get("events")).hasSize(1);
        assertThat(p2.get("events").get(0).get("event").asText()).isEqualTo("login");
        assertThat(p2.hasNonNull("nextCursor")).isFalse();

        assertThat(ok("/api/v1/admin/audit?outcome=refused").get("total").asInt()).isEqualTo(2);
    }

    @Test
    void detailExportVerifyAndRebuild() throws Exception {
        String id = search("user:erp-export").get("events").get(0).get("id").asText();
        JsonNode d = ok("/api/v1/admin/audit/" + id);
        assertThat(d.get("data").get("reason").asText()).isEqualTo("address not allowed");
        assertThat(d.get("chain").get("node").asText()).isEqualTo("test");

        HttpResponse<String> csv = send("GET", "/api/v1/admin/audit/export?format=csv&q=" + q("user:bob"),
                "admin2fa:root");
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Disposition").orElse("")).contains(".csv");
        assertThat(csv.body().split("\r\n")).hasSize(4);
        HttpResponse<String> jsonl = send("GET", "/api/v1/admin/audit/export?format=jsonl&q=" + q("zebrafish"),
                "admin2fa:root");
        assertThat(jsonl.body().lines().toList()).hasSize(2)
                .allSatisfy(l -> assertThat(l).startsWith("{\"ts\":").contains("zebrafish"));

        assertThat(ok("/api/v1/admin/audit/events").get("events").toString()).contains("\"event\":\"app.login\"");

        HttpResponse<String> v = send("POST", "/api/v1/admin/audit/verify", "admin2fa:root");
        assertThat(v.statusCode()).as(v.body()).isEqualTo(200);
        JsonNode node = Json.MAPPER.readTree(v.body()).get("nodes").get(0);
        assertThat(node.get("intact").asBoolean()).isTrue();

        // looking was recorded, with the query
        app.auditStore.flush();
        index().indexNow();
        assertThat(search("event:audit.searched export").get("total").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(search("event:audit.exported").get("total").asInt()).isEqualTo(2);

        long before = ok("/api/v1/admin/audit/index").get("documents").asLong();
        HttpResponse<String> rb = send("POST", "/api/v1/admin/audit/index/rebuild", "admin2fa:root");
        assertThat(rb.statusCode()).as(rb.body()).isEqualTo(202);
        assertThat(ok("/api/v1/admin/audit/index").get("documents").asLong()).isGreaterThanOrEqualTo(before);
        assertThat(users(search("ip:10.20.0.0/16"))).containsExactly("alice", "alice");
    }

    @Test
    void theIndexIsEncryptedAndSeparateFromFileSearch() throws Exception {
        assertThat(Files.exists(dir.resolve("audit/index.key"))).isTrue();
        try (Stream<Path> files = Files.walk(dir.resolve("audit/index"))) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String raw = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                assertThat(raw).as(p.toString()).doesNotContain("zebrafish").doesNotContain("erp-export");
            }
        }
        // the file search never sees audit events
        HttpResponse<String> r = send("GET", "/api/v1/search?q=zebrafish", "admin2fa:root");
        assertThat(r.statusCode()).isIn(200, 403);
        if (r.statusCode() == 200) {
            assertThat(Json.MAPPER.readTree(r.body()).get("total").asInt()).isZero();
        }
    }
}
