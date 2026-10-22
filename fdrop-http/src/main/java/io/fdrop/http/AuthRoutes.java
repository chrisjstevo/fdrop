package io.fdrop.http;

import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import io.fdrop.core.Fdrop;
import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.OidcClient;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.util.Hashing;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Browser login as a backend-for-frontend: fdrop runs the authorization-code + PKCE flow against Keycloak, keeps
 * the tokens server-side, and gives the browser only an opaque session cookie.
 */
final class AuthRoutes {

    private final Fdrop app;
    private final SessionStore sessions;

    AuthRoutes(Fdrop app, SessionStore sessions) {
        this.app = app;
        this.sessions = sessions;
    }

    void register(Router r) {
        r.publicGet("/auth/login", this::login);
        r.publicGet("/auth/callback", this::callback);
        r.publicPost("/auth/logout", this::logout);
        r.publicPost("/auth/backchannel-logout", this::backchannel);
    }

    private String publicUrl() {
        String u = app.config.http.publicUrl;
        if (u == null || u.isBlank()) {
            u = (app.config.http.tls.enabled ? "https" : "http") + "://localhost:" + app.config.http.port;
        }
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private URI redirectUri() {
        String r = app.config.auth.oidc.redirectUri;
        return URI.create(r == null || r.isBlank() ? publicUrl() + "/auth/callback" : r);
    }

    /** Only same-site relative paths are allowed as return targets, never another host. */
    static String safeReturnTo(String returnTo) {
        if (returnTo == null || !returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.contains("\\")
                || returnTo.startsWith("/auth/")) {
            return "/";
        }
        return returnTo;
    }

    /** The Account page's buttons, mapped to Keycloak application-initiated actions. */
    static final Map<String, String> ACTIONS = Map.of(
            "configure-totp", "CONFIGURE_TOTP",
            "register-security-key", "webauthn-register",
            "recovery-codes", "CONFIGURE_RECOVERY_AUTHN_CODES");

    /**
     * Starts a sign-in. {@code ?twoFactor=1} asks Keycloak for a second factor straight away (the UI uses it when
     * an admin page refuses a password-only session); {@code ?action=} starts one of {@link #ACTIONS}.
     */
    private Response login(Ctx ctx) {
        String action = null;
        if (ctx.query("action") != null) {
            action = ACTIONS.get(ctx.query("action"));
            if (action == null) {
                throw FdropException.badRequest("action must be one of " + String.join(", ", ACTIONS.keySet()));
            }
        }
        String returnTo = ctx.query("returnTo");
        if (returnTo == null && action != null) {
            returnTo = "/account";
        }
        boolean twoFactor = "1".equals(ctx.query("twoFactor"));
        return Response.redirect(authorize(safeReturnTo(returnTo), twoFactor, action));
    }

    private String authorize(String returnTo, boolean twoFactor, String action) {
        CodeVerifier verifier = new CodeVerifier();
        String nonce = Hashing.randomToken(16);
        String state = sessions.startLogin(verifier, nonce, returnTo, twoFactor, action);
        String acr = twoFactor ? app.twoFactor.config().acr : null;
        return app.oidc.authorizationUri(state, nonce, verifier, redirectUri(), acr, action).toString();
    }

    private Response callback(Ctx ctx) {
        String error = ctx.query("error");
        if (error != null) {
            app.metrics.login("failure");
            throw FdropException.unauthorized("sign-in was not completed: " + error);
        }
        SessionStore.PendingLogin p = sessions.finishLogin(ctx.query("state"))
                .orElseThrow(() -> FdropException.badRequest("sign-in expired or was started elsewhere; try again"));
        OidcClient.Tokens t;
        try {
            t = app.oidc.exchangeCode(ctx.requireQuery("code"), p.verifier(), redirectUri(), p.nonce());
        } catch (FdropException e) {
            app.metrics.login("failure");
            throw e;
        }
        Principal who = t.principal();
        if (p.action() != null || ctx.query("kc_action_status") != null) {
            // an enrolment may have added a factor
            app.twoFactor.invalidate(who.sub());
        }
        boolean twoFactor = app.twoFactor.isTwoFactorAcr(t.acr());
        if (app.twoFactor.needed(who)) {
            if (twoFactor) {
                app.metrics.twoFactor("satisfied");
            } else if (!p.stepUp()) {
                // Keycloak's SSO session means only the second factor is asked for. A user the policy covers who
                // has none yet sets up an authenticator app first, then comes back through here for the step-up.
                boolean enrol = !app.twoFactor.hasFactor(who.sub());
                if (enrol && p.action() != null) {
                    app.metrics.twoFactor("refused");
                    Audit.log("login.refused", "user", who.username(), "ip", ctx.clientIp(), "protocol", "HTTP",
                            "reason", "second factor not set up");
                    return Response.redirect("/sign-in-problem?reason=two-factor-setup");
                }
                app.metrics.twoFactor("step_up");
                return Response.redirect(authorize(p.returnTo(), !enrol, enrol ? "CONFIGURE_TOTP" : null));
            } else {
                app.metrics.twoFactor("refused");
                app.metrics.login("failure");
                Audit.log("login.refused", "user", who.username(), "ip", ctx.clientIp(), "protocol", "HTTP",
                        "reason", "Keycloak did not confirm a second factor", "acr", t.acr());
                return Response.redirect("/sign-in-problem?reason=two-factor");
            }
        }
        app.accounts.ensure(who);
        ctx.cookie(Authenticator.COOKIE).ifPresent(sessions::remove);
        SessionStore.WebSession s = sessions.create(t, twoFactor);
        app.metrics.login("success");
        Audit.log("login", "user", who.username(), "ip", ctx.clientIp(), "protocol", "HTTP", "twoFactor",
                twoFactor);
        Response.Empty r = Response.redirect(p.returnTo());
        r.headers().add("Set-Cookie", cookie(s.id, -1));
        return r;
    }

    private String cookie(String value, long maxAge) {
        DefaultCookie c = new DefaultCookie(Authenticator.COOKIE, value);
        c.setHttpOnly(true);
        c.setSecure(app.config.auth.session.secureCookie);
        c.setPath("/");
        c.setSameSite(CookieHeaderNames.SameSite.Lax);
        if (maxAge >= 0) {
            c.setMaxAge(maxAge);
        }
        return ServerCookieEncoder.STRICT.encode(c);
    }

    private Response logout(Ctx ctx) {
        Optional<SessionStore.WebSession> s = ctx.cookie(Authenticator.COOKIE).flatMap(sessions::find);
        String idToken = null;
        if (s.isPresent()) {
            String csrf = ctx.header("X-Fdrop-CSRF");
            if (csrf == null || !csrf.equals(s.get().csrf())) {
                throw FdropException.forbidden("missing or invalid X-Fdrop-CSRF header");
            }
            idToken = s.get().idToken;
            sessions.remove(s.get().id);
            Audit.log("logout", "user", s.get().principal().username(), "ip", ctx.clientIp());
        }
        String url;
        try {
            url = app.oidc.endSessionUri(idToken, URI.create(publicUrl() + "/")).toString();
        } catch (FdropException e) {
            url = "/";
        }
        Response.Json r = Response.json(Map.of("logoutUrl", url));
        r.headers().add("Set-Cookie", cookie("", 0));
        return r;
    }

    private Response backchannel(Ctx ctx) {
        Map<String, List<String>> form = new QueryStringDecoder(ctx.bodyText(), false).parameters();
        List<String> token = form.get("logout_token");
        if (token == null || token.isEmpty()) {
            throw FdropException.badRequest("logout_token is required");
        }
        Map<String, String> claims = app.oidc.verifyLogoutToken(token.get(0));
        int n = sessions.removeWhere(claims.get("sub"), claims.get("sid"));
        Audit.log("logout.backchannel", "sub", claims.get("sub"), "sessions", n);
        HttpHeaders h = Response.newHeaders();
        h.set("Cache-Control", "no-store");
        return new Response.Empty(200, h);
    }
}
