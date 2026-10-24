package io.fdrop.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.FakeDirectory;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestCerts;
import io.fdrop.core.TestSupport;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Application accounts over HTTP: admin management, HTTP Basic, client certificates on the mTLS listener and from
 * a trusted proxy, folder membership, and the refusals (wrong method, source, kind, web login).
 */
class AppAccountHttpTest {

    private static final String ADMIN = "admin:boss";

    @TempDir
    Path dir;

    TestCerts.Issued serverCa = TestCerts.ca("Server CA");
    TestCerts.Issued clientCa = TestCerts.ca("Client CA");
    FakeDirectory directory = new FakeDirectory();
    FdropConfig cfg;
    Fdrop app;
    HttpServer server;
    HttpClient http = HttpClient.newHttpClient();
    String base;
    String mtlsBase;

    @BeforeEach
    void start() throws Exception {
        cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.http.mtls.enabled = true;
        cfg.http.mtls.port = 0;
        Path[] server = TestCerts.server(serverCa).write(dir, "server");
        cfg.http.tls.cert = server[0].toString();
        cfg.http.tls.key = server[1].toString();
        Path cas = dir.resolve("client-cas.pem");
        Files.writeString(cas, clientCa.certPem());
        cfg.auth.applications.mtls.trustedCas = cas.toString();
        cfg.auth.applications.mtls.proxyHeader = "X-Client-Cert";
        cfg.http.trustedProxies.addAll(List.of("127.0.0.1", "::1"));
        cfg.auth.twoFactor.require = "none";
        cfg.auth.session.secureCookie = false;
        Path files = Files.createDirectories(dir.resolve("files"));
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(files);
        app = TestSupport.fdrop(cfg, b, directory);
        this.server = new HttpServer(app).start();
        base = "http://localhost:" + this.server.port();
        mtlsBase = "https://localhost:" + this.server.mtlsPort();
        // the people involved must have signed in once
        send(bearer("/api/v1/me", ADMIN));
        send(bearer("/api/v1/me", "user:alice"));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        app.close();
    }

    // ---- helpers ----

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(base + path));
    }

    private HttpRequest.Builder bearer(String path, String token) {
        return req(path).header("Authorization", "Bearer " + token);
    }

    private HttpRequest.Builder basic(String path, String user, String password) {
        return req(path).header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.BodyPublisher json(Object body) throws Exception {
        return HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body));
    }

    private JsonNode admin(String method, String path, Object body) throws Exception {
        HttpResponse<String> r = send(bearer(path, ADMIN).method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : json(body)));
        assertThat(r.statusCode()).as(method + " " + path + ": " + r.body()).isBetween(200, 299);
        return r.body().isEmpty() ? null : Json.MAPPER.readTree(r.body());
    }

    /** Creates an application account and returns its JSON (with "password" when one was generated). */
    private JsonNode createApp(String name, List<String> methods, List<String> sources) throws Exception {
        return admin("POST", "/api/v1/admin/app-accounts", Map.of("username", name, "responsible", "alice",
                "description", "test system", "allowedMethods", methods, "allowedSources", sources));
    }

    private HttpClient mtlsClient(TestCerts.Issued clientCert) throws Exception {
        return HttpClient.newBuilder().sslContext(TestCerts.clientContext(clientCert, serverCa)).build();
    }

    private HttpResponse<String> mtlsGet(TestCerts.Issued clientCert, String path) throws Exception {
        return mtlsClient(clientCert).send(HttpRequest.newBuilder(URI.create(mtlsBase + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- tests ----

    @Test
    void adminCreatesAnAccountAndItSignsInWithBasic() throws Exception {
        JsonNode created = createApp("app-erp", List.of("PASSWORD"), List.of());
        String id = created.path("account").path("id").asText();
        String password = created.path("password").asText();
        assertThat(password).hasSize(32);
        assertThat(created.path("account").path("responsible").asText()).isEqualTo("alice");

        // Basic needs TLS: refused on plain HTTP, accepted from a trusted proxy that terminated TLS
        assertThat(send(basic("/api/v1/me", "app-erp", password)).statusCode()).isEqualTo(401);
        HttpResponse<String> me = send(basic("/api/v1/me", "app-erp", password).header("X-Forwarded-Proto", "https"));
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(me.body()).path("kind").asText()).isEqualTo("application");
        assertThat(send(basic("/api/v1/me", "app-erp", "wrong").header("X-Forwarded-Proto", "https"))
                .statusCode()).isEqualTo(401);

        // a new password replaces the old one at once, even though the old one was cached
        String fresh = admin("POST", "/api/v1/admin/app-accounts/" + id + "/password", null).path("password").asText();
        assertThat(fresh).isNotEqualTo(password);
        assertThat(send(basic("/api/v1/me", "app-erp", password).header("X-Forwarded-Proto", "https")).statusCode())
                .isEqualTo(401);
        assertThat(send(basic("/api/v1/me", "app-erp", fresh).header("X-Forwarded-Proto", "https")).statusCode())
                .isEqualTo(200);

        JsonNode list = admin("GET", "/api/v1/admin/app-accounts", null);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("lastLoginAt").isNumber()).isTrue();
    }

    @Test
    void basicIsForApplicationAccountsOnly() throws Exception {
        cfg.auth.applications.basicWithoutTls = true;
        // alice's password is her name in the fake identity provider
        assertThat(send(basic("/api/v1/me", "alice", "alice")).statusCode()).isEqualTo(401);
        // and application accounts cannot use bearer tokens
        assertThat(send(bearer("/api/v1/me", "app:app-x")).statusCode()).isEqualTo(401);
        assertThat(send(bearer("/api/v1/me", "mixed:both")).statusCode()).isEqualTo(401);
    }

    @Test
    void pinnedCertificateOnTheMtlsListener() throws Exception {
        String id = createApp("app-pinned", List.of("MTLS"), List.of()).path("account").path("id").asText();
        TestCerts.Issued cert = TestCerts.selfSigned("CN=app-pinned");
        admin("POST", "/api/v1/admin/app-accounts/" + id + "/certificates", Map.of("pem", cert.certPem()));

        HttpResponse<String> me = mtlsGet(cert, "/api/v1/me");
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(me.body()).path("username").asText()).isEqualTo("app-pinned");

        // an unknown or expired certificate gets the same 401
        assertThat(mtlsGet(TestCerts.selfSigned("CN=app-pinned"), "/api/v1/me").statusCode()).isEqualTo(401);
        assertThat(mtlsGet(TestCerts.expired("CN=app-pinned"), "/api/v1/me").statusCode()).isEqualTo(401);
        // no certificate at all fails the handshake
        assertThatThrownBy(() -> mtlsGet(null, "/api/v1/me")).isInstanceOf(IOException.class);
        // the listener serves the API only
        assertThat(mtlsGet(cert, "/auth/login").statusCode()).isEqualTo(404);
        assertThat(mtlsGet(cert, "/").statusCode()).isEqualTo(404);

        // retiring the certificate takes effect at once
        String certId = admin("GET", "/api/v1/admin/app-accounts/" + id, null).path("certificates").get(0)
                .path("id").asText();
        admin("DELETE", "/api/v1/admin/app-accounts/" + id + "/certificates/" + certId, null);
        assertThat(mtlsGet(cert, "/api/v1/me").statusCode()).isEqualTo(401);
    }

    @Test
    void bySubjectCertificateNeedsTheTrustedCa() throws Exception {
        String id = createApp("app-subject", List.of("MTLS"), List.of()).path("account").path("id").asText();
        admin("POST", "/api/v1/admin/app-accounts/" + id + "/certificates", Map.of("san", "DNS:erp.example.com"));

        TestCerts.Issued good = TestCerts.client(clientCa, "CN=whatever", "DNS:erp.example.com");
        assertThat(mtlsGet(good, "/api/v1/me").statusCode()).isEqualTo(200);
        // the CA can reissue: a different certificate with the same SAN works without an admin change
        assertThat(mtlsGet(TestCerts.client(clientCa, "CN=renewed", "DNS:erp.example.com"), "/api/v1/me")
                .statusCode()).isEqualTo(200);

        TestCerts.Issued untrusted = TestCerts.client(TestCerts.ca("Rogue CA"), "CN=whatever", "DNS:erp.example.com");
        assertThat(mtlsGet(untrusted, "/api/v1/me").statusCode()).isEqualTo(401);
        assertThat(mtlsGet(TestCerts.client(clientCa, "CN=x", "DNS:other.example.com"), "/api/v1/me").statusCode())
                .isEqualTo(401);

        // a disabled account is refused, whatever its certificate
        admin("PATCH", "/api/v1/admin/app-accounts/" + id, Map.of("enabled", false));
        assertThat(mtlsGet(good, "/api/v1/me").statusCode()).isEqualTo(401);
    }

    @Test
    void certificateFromATrustedProxyHeader() throws Exception {
        String id = createApp("app-proxied", List.of("MTLS"), List.of()).path("account").path("id").asText();
        admin("POST", "/api/v1/admin/app-accounts/" + id + "/certificates",
                Map.of("subject", "CN=app-proxied, O=Example"));
        TestCerts.Issued cert = TestCerts.client(clientCa, "CN=app-proxied,O=Example");
        HttpResponse<String> r = send(req("/api/v1/me").header("X-Client-Cert", q(cert.certPem())));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    @Test
    void methodsAndSourcesAreEnforced() throws Exception {
        JsonNode pwOnly = createApp("app-pw", List.of("PASSWORD"), List.of());
        String id = pwOnly.path("account").path("id").asText();
        TestCerts.Issued cert = TestCerts.selfSigned("CN=app-pw");
        admin("POST", "/api/v1/admin/app-accounts/" + id + "/certificates", Map.of("pem", cert.certPem()));
        assertThat(mtlsGet(cert, "/api/v1/me").statusCode()).isEqualTo(401);

        String password = pwOnly.path("password").asText();
        admin("PATCH", "/api/v1/admin/app-accounts/" + id, Map.of("allowedSources", List.of("10.0.0.0/8")));
        assertThat(send(basic("/api/v1/me", "app-pw", password).header("X-Forwarded-Proto", "https")).statusCode())
                .isEqualTo(401);
        admin("PATCH", "/api/v1/admin/app-accounts/" + id,
                Map.of("allowedSources", List.of("127.0.0.0/8", "::1/128")));
        assertThat(send(basic("/api/v1/me", "app-pw", password).header("X-Forwarded-Proto", "https")).statusCode())
                .isEqualTo(200);

        String scrape = app.metrics.scrape(null);
        assertThat(scrape).contains("fdrop_app_auth_total{method=\"mtls\",protocol=\"http\",result=\"method_not_allowed\"}")
                .contains("result=\"source_refused\"");
    }

    @Test
    void expiredPasswordsAreRefused() throws Exception {
        cfg.auth.applications.basicWithoutTls = true;
        JsonNode created = createApp("app-old", List.of("PASSWORD"), List.of());
        cfg.auth.applications.passwordMaxAge = "1ms";
        Thread.sleep(5);
        assertThat(send(basic("/api/v1/me", "app-old", created.path("password").asText())).statusCode())
                .isEqualTo(401);
        cfg.auth.applications.passwordMaxAge = "0";
        assertThat(send(basic("/api/v1/me", "app-old", created.path("password").asText())).statusCode())
                .isEqualTo(200);
    }

    @Test
    void secondFactorsAreNeverAskedForAndAreRemoved() throws Exception {
        cfg.auth.applications.basicWithoutTls = true;
        JsonNode created = createApp("app-otp", List.of("PASSWORD"), List.of());
        directory.withOtp("app-otp");
        assertThat(send(basic("/api/v1/me", "app-otp", created.path("password").asText())).statusCode())
                .isEqualTo(200);
        assertThat(directory.credentials("sub-app-otp")).isEmpty();
    }

    @Test
    void applicationAccountsAreMembersOnly() throws Exception {
        cfg.auth.applications.basicWithoutTls = true;
        String password = createApp("app-member", List.of("PASSWORD"), List.of()).path("password").asText();
        String alice = "user:alice";
        JsonNode folder = Json.MAPPER.readTree(send(bearer("/api/v1/folders", alice)
                .POST(json(Map.of("type", "SHARED", "name", "Finance")))).body());
        String fid = folder.path("id").asText();

        JsonNode found = Json.MAPPER.readTree(send(bearer("/api/v1/users?search=app-m", alice)).body());
        assertThat(found.get(0).path("kind").asText()).isEqualTo("application");

        // read-only member: can list, cannot upload
        assertThat(send(bearer("/api/v1/folders/" + fid + "/members/app-member", alice)
                .PUT(json(Map.of("permission", "READ")))).statusCode()).isEqualTo(200);
        assertThat(send(basic("/api/v1/files?path=/shared/Finance", "app-member", password)).statusCode())
                .isEqualTo(200);
        HttpRequest.BodyPublisher data = HttpRequest.BodyPublishers.ofString("a,b\n1,2\n");
        assertThat(send(basic("/api/v1/files/content?path=" + q("/shared/Finance/x.csv"), "app-member", password)
                .PUT(data)).statusCode()).isEqualTo(403);

        // read-write member: uploads
        send(bearer("/api/v1/folders/" + fid + "/members/app-member", alice).PUT(json(Map.of("permission", "WRITE"))));
        HttpResponse<String> put = send(basic("/api/v1/files/content?path=" + q("/shared/Finance/x.csv"),
                "app-member", password).PUT(HttpRequest.BodyPublishers.ofString("a,b\n1,2\n")));
        assertThat(put.statusCode()).as(put.body()).isEqualTo(201);

        JsonNode members = Json.MAPPER.readTree(send(bearer("/api/v1/folders/" + fid, alice)).body()).path("members");
        assertThat(members.get(0).path("kind").asText()).isEqualTo("APPLICATION");

        // never an owner, never a folder creator, never assigned a destination
        assertThat(send(bearer("/api/v1/folders/" + fid + "/owner", alice)
                .POST(json(Map.of("username", "app-member")))).statusCode()).isEqualTo(400);
        assertThat(send(basic("/api/v1/folders", "app-member", password)
                .POST(json(Map.of("type", "SHARED", "name", "Mine")))).statusCode()).isEqualTo(403);
        assertThat(send(basic("/api/v1/me/ssh-keys", "app-member", password)
                .POST(json(Map.of("publicKey", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIabc x")))).statusCode())
                .isEqualTo(403);
    }

    @Test
    void theWebLoginRefusesApplicationAccounts() throws Exception {
        HttpResponse<String> login = send(req("/auth/login"));
        Matcher m = Pattern.compile("state=([^&]+)").matcher(login.headers().firstValue("Location").orElseThrow());
        assertThat(m.find()).isTrue();
        HttpResponse<String> cb = send(req("/auth/callback?state=" + m.group(1) + "&code=app:app-web"));
        assertThat(cb.statusCode()).isEqualTo(302);
        assertThat(cb.headers().firstValue("Location")).contains("/sign-in-problem?reason=application");
        assertThat(cb.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void deletingAnAccountRemovesItsAccess() throws Exception {
        cfg.auth.applications.basicWithoutTls = true;
        JsonNode created = createApp("app-gone", List.of("PASSWORD"), List.of());
        String id = created.path("account").path("id").asText();
        String password = created.path("password").asText();
        assertThat(send(basic("/api/v1/me", "app-gone", password)).statusCode()).isEqualTo(200);
        admin("DELETE", "/api/v1/admin/app-accounts/" + id, null);
        assertThat(admin("GET", "/api/v1/admin/app-accounts", null)).isEmpty();
        assertThat(send(bearer("/api/v1/admin/app-accounts", "user:alice")).statusCode()).isEqualTo(403);
    }
}
