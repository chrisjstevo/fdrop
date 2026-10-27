package io.fdrop.http;

import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.audit.Audit;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The audit log API without the fdrop-search module: browsing from the DB works, searching says it cannot. */
class AuditApiTest {

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
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("files"));
        app = TestSupport.fdrop(cfg, b);
        server = new HttpServer(app).start();
        base = "http://localhost:" + server.port();
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

    @Test
    void adminsBrowseUsersAreRefusedSearchNeedsTheIndex() throws Exception {
        Audit.log("login", "user", "alice", "ip", "10.0.0.5", "protocol", "HTTP");
        app.auditStore.flush();
        assertThat(get("/api/v1/admin/audit", "user:alice").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/admin/audit/index", "user:alice").statusCode()).isEqualTo(403);

        HttpResponse<String> list = get("/api/v1/admin/audit", "admin2fa:root");
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(list.body()).contains("\"event\":\"login\"", "\"user\":\"alice\"", "\"Sign-in\"");

        HttpResponse<String> search = get("/api/v1/admin/audit?q=alice", "admin2fa:root");
        assertThat(search.statusCode()).isEqualTo(503);
        assertThat(search.body()).contains("audit-search-unavailable");
        assertThat(get("/api/v1/admin/audit/index", "admin2fa:root").body()).contains("\"available\":false");
        assertThat(get("/api/v1/admin/audit/no-such-id", "admin2fa:root").statusCode()).isEqualTo(404);
    }
}
