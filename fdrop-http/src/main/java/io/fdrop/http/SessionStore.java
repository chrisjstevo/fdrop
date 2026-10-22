package io.fdrop.http;

import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import io.fdrop.core.auth.OidcClient;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.util.Hashing;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side browser sessions. The browser only ever holds an opaque 256-bit id in an HttpOnly cookie; the
 * Keycloak tokens stay here. Sessions slide on use up to an absolute limit. Kept in memory: a restart signs users
 * out, and multi-node deployments need sticky sessions until a shared store is added.
 */
public final class SessionStore {

    /** One signed-in browser. */
    public static final class WebSession {
        final String id;
        final String csrf;
        final long createdAt;
        volatile Principal principal;
        volatile String idToken;
        volatile String accessToken;
        volatile String refreshToken;
        volatile long accessExpiresAt;
        volatile long lastSeen;
        final String sid;
        /** Signed in with a second factor (Keycloak returned the two-factor ACR). Token refreshes keep it. */
        final boolean twoFactor;

        WebSession(String id, OidcClient.Tokens t, boolean twoFactor) {
            this.id = id;
            this.csrf = Hashing.randomToken(24);
            this.createdAt = System.currentTimeMillis();
            this.lastSeen = createdAt;
            this.sid = t.sid();
            this.twoFactor = twoFactor;
            apply(t);
        }

        void apply(OidcClient.Tokens t) {
            this.principal = t.principal();
            if (t.idToken() != null) {
                this.idToken = t.idToken();
            }
            this.accessToken = t.accessToken();
            if (t.refreshToken() != null) {
                this.refreshToken = t.refreshToken();
            }
            this.accessExpiresAt = t.accessExpiresAt();
        }

        public String csrf() {
            return csrf;
        }

        public Principal principal() {
            return principal;
        }

        public boolean twoFactor() {
            return twoFactor;
        }
    }

    /**
     * A login in progress, keyed by its OAuth {@code state}. {@code stepUp} is set when this login already asked
     * Keycloak for a second factor; {@code action} is the Keycloak action it started, if any.
     */
    public record PendingLogin(CodeVerifier verifier, String nonce, String returnTo, long createdAt, boolean stepUp,
                               String action) {
    }

    private final Duration idle;
    private final Duration absolute;
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingLogin> pending = new ConcurrentHashMap<>();

    public SessionStore(Duration idle, Duration absolute) {
        this.idle = idle;
        this.absolute = absolute;
    }

    public WebSession create(OidcClient.Tokens t, boolean twoFactor) {
        WebSession s = new WebSession(Hashing.randomToken(32), t, twoFactor);
        sessions.put(s.id, s);
        return s;
    }

    public Optional<WebSession> find(String id) {
        WebSession s = sessions.get(id);
        if (s == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (now - s.lastSeen > idle.toMillis() || now - s.createdAt > absolute.toMillis()) {
            sessions.remove(id);
            return Optional.empty();
        }
        s.lastSeen = now;
        return Optional.of(s);
    }

    public void remove(String id) {
        sessions.remove(id);
    }

    /** Back-channel logout and user deletion: end every session of a user, or the one Keycloak session. */
    public int removeWhere(String sub, String sid) {
        int[] n = {0};
        sessions.values().removeIf(s -> {
            boolean match = (sid != null && !sid.isEmpty() && sid.equals(s.sid))
                    || (sub != null && !sub.isEmpty() && s.principal.sub().equals(sub));
            if (match) {
                n[0]++;
            }
            return match;
        });
        return n[0];
    }

    public String startLogin(CodeVerifier verifier, String nonce, String returnTo, boolean stepUp, String action) {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> now - p.createdAt() > Duration.ofMinutes(10).toMillis());
        String state = Hashing.randomToken(24);
        pending.put(state, new PendingLogin(verifier, nonce, returnTo, now, stepUp, action));
        return state;
    }

    public Optional<PendingLogin> finishLogin(String state) {
        PendingLogin p = state == null ? null : pending.remove(state);
        if (p == null || System.currentTimeMillis() - p.createdAt() > Duration.ofMinutes(10).toMillis()) {
            return Optional.empty();
        }
        return Optional.of(p);
    }

    public int size() {
        return sessions.size();
    }
}
