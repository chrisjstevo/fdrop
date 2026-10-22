package io.fdrop.core.auth;

import io.fdrop.core.config.FdropConfig;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a user is still enabled in Keycloak and still holds the standard role, cached for five minutes. Used
 * for SFTP public-key logins, which never pass through Keycloak themselves.
 */
public class UserStatusCache {

    private record Entry(Principal principal, long expiresAt) {
    }

    private static final Duration TTL = Duration.ofMinutes(5);

    private final KeycloakAdmin admin;
    private final FdropConfig.Oidc cfg;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public UserStatusCache(KeycloakAdmin admin, FdropConfig.Oidc cfg) {
        this.admin = admin;
        this.cfg = cfg;
    }

    /** The user as a principal if they are enabled, or empty if disabled or deleted. */
    public Optional<Principal> active(String sub) {
        Entry e = cache.get(sub);
        if (e != null && e.expiresAt() > System.currentTimeMillis()) {
            return Optional.ofNullable(e.principal());
        }
        Principal p = admin.getUser(sub).filter(KeycloakAdmin.KcUser::enabled).map(u -> {
            List<String> roles = admin.realmRoles(u.id());
            return new Principal(u.id(), u.username(), u.email(), roles.contains(cfg.userRole),
                    roles.contains(cfg.adminRole));
        }).orElse(null);
        cache.put(sub, new Entry(p, System.currentTimeMillis() + TTL.toMillis()));
        return Optional.ofNullable(p);
    }

    public void invalidate(String sub) {
        cache.remove(sub);
    }
}
