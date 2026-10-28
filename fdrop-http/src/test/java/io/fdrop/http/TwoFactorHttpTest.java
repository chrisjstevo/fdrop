package io.fdrop.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.FakeDirectory;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.OidcClient;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Two-factor sign-in over HTTP: the step-up at login, sessions and bearer tokens, and the routes that need it. */
class TwoFactorHttpTest {

    private static final Pattern STATE = Pattern.compile("state=([^&]+)");

    @TempDir
    Path dir;

    Fdrop app;
    HttpServer server;
    FakeDirectory directory = new FakeDirectory();
    HttpClient http = TestSupport.httpClient();
    String base;

    void start(String require) throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.auth.twoFactor.require = require;
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir);
        app = TestSupport.fdrop(cfg, b, directory);
        server = new HttpServer(app).start();
        base = "https://localhost:" + server.port();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        app.close();
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(base + path));
    }

    private HttpRequest.Builder bearer(String path, String token) {
        return get(path).header("Authorization", "Bearer " + token);
    }

    /** A browser session as the login callback would create it. */
    private SessionStore.WebSession session(Principal p, boolean twoFactor) {
        return server.sessions().create(new OidcClient.Tokens("id", "access", "refresh",
                System.currentTimeMillis() + 3_600_000, p, "sid", twoFactor ? "2fa" : "1"), twoFactor);
    }

    private HttpRequest.Builder withSession(HttpRequest.Builder b, SessionStore.WebSession s) {
        return b.header("Cookie", Authenticator.COOKIE + "=" + s.id).header("X-Fdrop-CSRF", s.csrf());
    }

    private static String state(HttpResponse<String> redirect) {
        Matcher m = STATE.matcher(redirect.headers().firstValue("Location").orElseThrow());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> callback(String state, String code) throws Exception {
        return send(get("/auth/callback?state=" + state + "&code=" + code));
    }

    private static String location(HttpResponse<String> r) {
        assertThat(r.statusCode()).isEqualTo(302);
        return r.headers().firstValue("Location").orElseThrow();
    }

    @Test
    void anAdminWithAFactorIsSentBackForItAndThenSignedIn() throws Exception {
        directory.withOtp("root");
        start("admins");
        HttpResponse<String> login = send(get("/auth/login?returnTo=%2Fadmin%2Fusers"));
        assertThat(location(login)).startsWith("http://keycloak.test/auth").doesNotContain("acr_values");

        HttpResponse<String> passwordOnly = callback(state(login), "admin:root");
        assertThat(location(passwordOnly)).contains("acr_values=2fa").doesNotContain("kc_action");
        assertThat(passwordOnly.headers().firstValue("Set-Cookie")).isEmpty();

        HttpResponse<String> done = callback(state(passwordOnly), "admin:root:2fa");
        assertThat(location(done)).isEqualTo("/admin/users");
        assertThat(done.headers().firstValue("Set-Cookie")).hasValueSatisfying(c -> assertThat(c)
                .startsWith(Authenticator.COOKIE + "="));
        assertThat(server.sessions().size()).isEqualTo(1);
    }

    @Test
    void aSignInKeycloakDoesNotConfirmIsRefusedWithoutASession() throws Exception {
        directory.withOtp("root");
        start("admins");
        HttpResponse<String> stepUp = callback(state(send(get("/auth/login"))), "admin:root");
        HttpResponse<String> refused = callback(state(stepUp), "admin:root");
        assertThat(location(refused)).isEqualTo("/sign-in-problem?reason=two-factor");
        assertThat(refused.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(server.sessions().size()).isZero();
    }

    @Test
    void anAdminWithoutAFactorSetsOneUpFirst() throws Exception {
        start("admins");
        HttpResponse<String> enrol = callback(state(send(get("/auth/login"))), "admin:root");
        assertThat(location(enrol)).contains("kc_action=CONFIGURE_TOTP").doesNotContain("acr_values");

        // cancelled in Keycloak: still no factor
        HttpResponse<String> cancelled = callback(state(enrol), "admin:root");
        assertThat(location(cancelled)).isEqualTo("/sign-in-problem?reason=two-factor-setup");

        HttpResponse<String> again = callback(state(send(get("/auth/login"))), "admin:root");
        directory.withOtp("root");
        HttpResponse<String> stepUp = callback(state(again), "admin:root");
        assertThat(location(stepUp)).contains("acr_values=2fa");
        assertThat(location(callback(state(stepUp), "admin:root:2fa"))).isEqualTo("/");
    }

    @Test
    void standardUsersSignInWithAPasswordUnlessTheyTurnedItOn() throws Exception {
        start("admins");
        HttpResponse<String> alice = callback(state(send(get("/auth/login"))), "user:alice");
        assertThat(location(alice)).isEqualTo("/");
        assertThat(alice.headers().firstValue("Set-Cookie")).isPresent();

        directory.withOtp("bob");
        HttpResponse<String> bob = callback(state(send(get("/auth/login"))), "user:bob");
        assertThat(location(bob)).contains("acr_values=2fa");
    }

    @Test
    void accountActionsStartKeycloakActions() throws Exception {
        start("admins");
        assertThat(location(send(get("/auth/login?action=register-security-key"))))
                .contains("kc_action=webauthn-register");
        assertThat(send(get("/auth/login?action=delete-everything")).statusCode()).isEqualTo(400);
    }

    @Test
    void adminRoutesNeedATwoFactorSession() throws Exception {
        start("admins");
        Principal root = TestSupport.admin("root");
        HttpResponse<String> passwordOnly = send(withSession(get("/api/v1/admin/forward-destinations"),
                session(root, false)));
        assertThat(passwordOnly.statusCode()).isEqualTo(403);
        assertThat(Json.MAPPER.readTree(passwordOnly.body()).get("type").asText()).isEqualTo("two-factor-required");

        assertThat(send(withSession(get("/api/v1/admin/forward-destinations"), session(root, true))).statusCode())
                .isEqualTo(200);

        // a standard user gets the ordinary refusal, not an invitation to sign in again
        HttpResponse<String> alice = send(withSession(get("/api/v1/admin/forward-destinations"),
                session(TestSupport.user("alice"), false)));
        assertThat(alice.statusCode()).isEqualTo(403);
        assertThat(Json.MAPPER.readTree(alice.body()).get("type").asText()).isEqualTo("about:blank");
    }

    @Test
    void bearerTokensForPeopleWhoNeedTwoFactorMustCarryIt() throws Exception {
        start("admins");
        assertThat(send(bearer("/api/v1/me", "admin:root")).statusCode()).isEqualTo(401);
        HttpResponse<String> ok = send(bearer("/api/v1/admin/forward-destinations", "admin2fa:root"));
        assertThat(ok.statusCode()).isEqualTo(200);

        JsonNode me = Json.MAPPER.readTree(send(bearer("/api/v1/me", "admin2fa:root")).body()).get("twoFactor");
        assertThat(me.get("policy").asText()).isEqualTo("admins");
        assertThat(me.get("required").asBoolean()).isTrue();
        assertThat(me.get("thisSession").asBoolean()).isTrue();
        assertThat(me.get("factors").size()).isZero();

        JsonNode alice = Json.MAPPER.readTree(send(bearer("/api/v1/me", "user:alice")).body()).get("twoFactor");
        assertThat(alice.get("required").asBoolean()).isFalse();
    }

    @Test
    void withPolicyNoneAdminsNeedNoSecondFactor() throws Exception {
        start("none");
        assertThat(send(bearer("/api/v1/admin/forward-destinations", "admin:root")).statusCode()).isEqualTo(200);
        assertThat(send(withSession(get("/api/v1/admin/forward-destinations"),
                session(TestSupport.admin("root"), false))).statusCode()).isEqualTo(200);
    }

    @Test
    void addingKeysAndRemovingFactorsNeedATwoFactorSession() throws Exception {
        directory.withOtp("alice");
        start("admins");
        Principal alice = TestSupport.user("alice");
        String key = "{\"publicKey\":\"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl\"}";

        HttpResponse<String> addKey = send(withSession(get("/api/v1/me/ssh-keys"), session(alice, false))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(key)));
        assertThat(addKey.statusCode()).isEqualTo(403);
        assertThat(addKey.body()).contains("two-factor-required");

        HttpResponse<String> removeWithoutIt = send(withSession(get("/api/v1/me/two-factor/otp-1"),
                session(alice, false)).DELETE());
        assertThat(removeWithoutIt.statusCode()).isEqualTo(403);

        SessionStore.WebSession strong = session(alice, true);
        assertThat(send(withSession(get("/api/v1/me/ssh-keys"), strong).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(key))).statusCode()).isEqualTo(201);
        assertThat(send(withSession(get("/api/v1/me/two-factor/otp-1"), strong).DELETE()).statusCode())
                .isEqualTo(204);
        assertThat(directory.credentials("sub-alice")).isEmpty();
    }
}
