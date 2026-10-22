package io.fdrop.core.auth;

/**
 * An authenticated caller, the same shape for browser sessions, bearer tokens and SFTP sessions.
 *
 * @param sub      Keycloak subject, the stable user id
 * @param username Keycloak preferred_username, for display and SFTP login
 * @param user     holds the standard role ({@code fdrop-user})
 * @param admin    holds the admin role ({@code fdrop-admin})
 */
public record Principal(String sub, String username, String email, boolean user, boolean admin) {

    /** Every fdrop operation needs the standard role; admins without it can only manage users. */
    public boolean canUseFiles() {
        return user;
    }
}
