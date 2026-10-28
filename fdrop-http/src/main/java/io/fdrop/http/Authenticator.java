package io.fdrop.http;

import io.fdrop.core.Fdrop;
import io.fdrop.core.auth.ClientCertificates;
import io.fdrop.core.auth.OidcClient;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.auth.TwoFactorService;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.service.AppAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Who is calling: a browser session cookie (refreshing its Keycloak tokens shortly before they expire), a
 * script's bearer JWT with the {@code fdrop-api} audience, or an application account's client certificate (on the
 * mTLS listener, or forwarded by a trusted proxy) or HTTP Basic password. Also records whether the caller signed in
 * with a second factor, and refuses admin routes to callers who did not when the policy requires it.
 */
final class Authenticator {

    static final String COOKIE = "fdrop_session";
    private static final Logger log = LoggerFactory.getLogger(Authenticator.class);

    private final Fdrop app;
    private final SessionStore sessions;

    Authenticator(Fdrop app, SessionStore sessions) {
        this.app = app;
        this.sessions = sessions;
    }

    /** Fills {@code ctx.principal} and {@code ctx.session}, or throws 401. */
    void authenticate(Ctx ctx, boolean csrfRequired) {
        Ctx.Connection conn = ctx.connection();
        if (conn.mtlsListener()) {
            // the application listener accepts client certificates only
            application(ctx, () -> app.appAccounts.certificateLogin(conn.clientCerts(), ctx.clientIp(), "http",
                    ctx.header("User-Agent")));
            return;
        }
        String proxyHeader = app.config.auth.applications.mtls.proxyHeader;
        if (proxyHeader != null && !proxyHeader.isBlank() && conn.trustedPeer() && ctx.header(proxyHeader) != null) {
            List<X509Certificate> chain = ClientCertificates.fromHeader(ctx.header(proxyHeader));
            application(ctx, () -> app.appAccounts.certificateLogin(chain, ctx.clientIp(), "http",
                    ctx.header("User-Agent")));
            return;
        }
        String auth = ctx.header("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            basic(ctx, auth.substring(6).trim());
            return;
        }
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            OidcClient.Bearer b = app.oidc.verifyBearer(auth.substring(7).trim());
            Principal p = b.principal();
            if (p.app() || p.mixedRoles()) {
                throw FdropException.unauthorized("application accounts sign in with a client certificate or"
                        + " their password, not a bearer token");
            }
            boolean twoFactor = b.serviceAccount() || app.twoFactor.isTwoFactorAcr(b.acr());
            if (!twoFactor && app.twoFactor.needed(p)) {
                throw FdropException.unauthorized("this user signs in with two factors; use a token Keycloak"
                        + " issued after a two-factor sign-in (acr " + app.twoFactor.config().acr + ")");
            }
            app.accounts.ensure(p);
            ctx.principal = p;
            ctx.twoFactor = twoFactor;
            return;
        }
        Optional<String> id = ctx.cookie(COOKIE);
        SessionStore.WebSession s = id.flatMap(sessions::find)
                .orElseThrow(() -> FdropException.unauthorized("sign in required"));
        if (csrfRequired) {
            String token = ctx.header("X-Fdrop-CSRF");
            if (token == null || !java.security.MessageDigest.isEqual(token.getBytes(), s.csrf().getBytes())) {
                throw FdropException.forbidden("missing or invalid X-Fdrop-CSRF header");
            }
        }
        refreshIfNeeded(s);
        ctx.session = s;
        ctx.principal = s.principal;
        ctx.twoFactor = s.twoFactor;
    }

    /** HTTP Basic: application accounts only (every listener is TLS, so the password is never sent in clear). */
    private void basic(Ctx ctx, String encoded) {
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw FdropException.unauthorized("malformed Basic credentials");
        }
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            throw FdropException.unauthorized("malformed Basic credentials");
        }
        String username = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);
        application(ctx, () -> app.appAccounts.passwordLogin(username, password, ctx.clientIp(), "http",
                ctx.header("User-Agent")));
    }

    /** Runs an application login; any refusal is the same {@code 401}, with no hint which check failed. */
    private void application(Ctx ctx, java.util.function.Supplier<Principal> login) {
        Principal p;
        try {
            p = login.get();
        } catch (AppAccountService.LoginRefused e) {
            throw FdropException.unauthorized("application account sign-in refused");
        }
        // AppAccountService created the account's rows; application accounts never use a second factor
        ctx.principal = p;
        ctx.twoFactor = false;
    }

    /** Under {@code admins} or {@code all}, the admin API needs a two-factor sign-in. */
    void checkAdminTwoFactor(Ctx ctx) {
        // non-admins get the plain 403 from the admin services instead
        if (app.twoFactor.policy() != TwoFactorService.Policy.NONE && ctx.principal().admin() && !ctx.twoFactor) {
            throw TwoFactorService.required("admin pages need a two-factor sign-in");
        }
    }

    private void refreshIfNeeded(SessionStore.WebSession s) {
        if (s.accessExpiresAt - System.currentTimeMillis() > 60_000) {
            return;
        }
        synchronized (s) {
            if (s.accessExpiresAt - System.currentTimeMillis() > 60_000) {
                return;
            }
            if (s.refreshToken == null) {
                sessions.remove(s.id);
                throw FdropException.unauthorized("session expired");
            }
            try {
                OidcClient.Tokens t = app.oidc.refresh(s.refreshToken);
                s.apply(t);
            } catch (FdropException e) {
                if (e.status() == 503) {
                    // Keycloak is down: keep the session working on its last known identity
                    log.warn("token refresh skipped, Keycloak unreachable");
                    s.accessExpiresAt = System.currentTimeMillis() + 60_000;
                    return;
                }
                app.metrics.login("refresh_failed");
                sessions.remove(s.id);
                throw FdropException.unauthorized("session expired");
            }
        }
    }
}
