package io.fdrop.http;

import io.fdrop.core.Fdrop;
import io.fdrop.core.auth.OidcClient;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.auth.TwoFactorService;
import io.fdrop.core.error.FdropException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Who is calling: a browser session cookie (refreshing its Keycloak tokens shortly before they expire) or a
 * script's bearer JWT with the {@code fdrop-api} audience. Also records whether the caller signed in with a
 * second factor, and refuses admin routes to callers who did not when the policy requires it.
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
        String auth = ctx.header("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            OidcClient.Bearer b = app.oidc.verifyBearer(auth.substring(7).trim());
            Principal p = b.principal();
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
