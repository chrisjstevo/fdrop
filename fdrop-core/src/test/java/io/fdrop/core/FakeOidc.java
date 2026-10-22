package io.fdrop.core;

import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import io.fdrop.core.auth.OidcClient;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;

import java.net.URI;

/**
 * An OIDC client for tests: bearer tokens are {@code user:<name>} or {@code admin:<name>}, with a {@code 2fa}
 * suffix on the kind ({@code admin2fa:<name>}) for a token Keycloak issued after a second factor; SFTP passwords
 * equal the username, and one-time codes are {@code 123456}. Browser logins go to {@code http://keycloak.test},
 * and an authorization code is {@code <kind>:<name>}, with {@code :2fa} appended when the second factor was used.
 * Keycloak is never contacted.
 */
public class FakeOidc extends OidcClient {

    public FakeOidc(FdropConfig.Oidc cfg, FdropMetrics metrics) {
        super(cfg, metrics);
    }

    @Override
    public Bearer verifyBearer(String jwt) {
        String[] parts = jwt.split(":", 2);
        if (parts.length != 2) {
            throw FdropException.unauthorized("invalid token");
        }
        boolean twoFactor = parts[0].endsWith("2fa");
        String kind = twoFactor ? parts[0].substring(0, parts[0].length() - 3) : parts[0];
        Principal p = switch (kind) {
            case "user" -> TestSupport.user(parts[1]);
            case "admin" -> TestSupport.admin(parts[1]);
            case "nobody" -> new Principal("sub-" + parts[1], parts[1], null, false, false);
            default -> throw FdropException.unauthorized("invalid token");
        };
        return new Bearer(p, twoFactor ? "2fa" : "1", false);
    }

    /** Codes are only accepted from users who have one (see {@link FakeDirectory}). */
    @Override
    public Principal passwordLogin(String username, String password, String totp) {
        if (!username.equals(password)) {
            return null;
        }
        if (totp != null && !totp.equals("123456")) {
            return null;
        }
        return username.startsWith("admin") ? TestSupport.admin(username) : TestSupport.user(username);
    }

    @Override
    public URI authorizationUri(String state, String nonce, CodeVerifier verifier, URI redirect, String acr,
                                String kcAction) {
        return URI.create("http://keycloak.test/auth?state=" + state + (acr == null ? "" : "&acr_values=" + acr)
                + (kcAction == null ? "" : "&kc_action=" + kcAction));
    }

    @Override
    public Tokens exchangeCode(String code, CodeVerifier verifier, URI redirect, String expectedNonce) {
        String[] parts = code.split(":");
        Principal p = parts[0].equals("admin") ? TestSupport.admin(parts[1]) : TestSupport.user(parts[1]);
        boolean twoFactor = parts.length > 2 && parts[2].equals("2fa");
        return new Tokens("id-" + parts[1], "access-" + parts[1], "refresh-" + parts[1],
                System.currentTimeMillis() + 3_600_000, p, "sid-" + parts[1], twoFactor ? "2fa" : "1");
    }

    @Override
    public boolean reachable() {
        return false;
    }
}
