package io.fdrop.core.auth;

import io.fdrop.core.config.FdropConfig;

import java.util.Collection;

/**
 * An authenticated caller, the same shape for browser sessions, bearer tokens, SFTP sessions and application
 * accounts.
 *
 * @param sub      Keycloak subject, the stable user id
 * @param username Keycloak preferred_username, for display and SFTP login
 * @param user     holds the standard role ({@code fdrop-user})
 * @param admin    holds the admin role ({@code fdrop-admin})
 * @param app      holds the application role ({@code fdrop-app}, through the {@code fdrop-applications} group)
 */
public record Principal(String sub, String username, String email, boolean user, boolean admin, boolean app) {

    /** A person: standard and/or admin, never an application account. */
    public Principal(String sub, String username, String email, boolean user, boolean admin) {
        this(sub, username, email, user, admin, false);
    }

    /** From a token's or Keycloak's realm role names, with the role names fdrop is configured with. */
    public static Principal of(String sub, String username, String email, Collection<String> roles,
                               FdropConfig.Oidc cfg) {
        return new Principal(sub, username, email, roles.contains(cfg.userRole), roles.contains(cfg.adminRole),
                roles.contains(cfg.appRole));
    }

    /** Standard users and application accounts work with files; admins need the standard role too. */
    public boolean canUseFiles() {
        return !mixedRoles() && (user || app);
    }

    /** An application account, with none of the people's roles. */
    public boolean isApplication() {
        return app && !mixedRoles();
    }

    /**
     * {@code fdrop-app} together with {@code fdrop-user} or {@code fdrop-admin}: refused everywhere, so a person's
     * account cannot become an unattended one by accident, or the reverse.
     */
    public boolean mixedRoles() {
        return app && (user || admin);
    }

    /** Creates and owns managed folders, is assigned destinations, manages own keys and factors. */
    public boolean isPerson() {
        return !app && user;
    }
}
