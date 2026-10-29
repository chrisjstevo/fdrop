package io.fdrop.core.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountTokenTest {

    private static JWTClaimsSet.Builder token() {
        return new JWTClaimsSet.Builder().subject("u1").claim("preferred_username", "service-account-fdrop-ci");
    }

    @Test
    void aClientCredentialsTokenIsAServiceAccount() {
        assertThat(OidcClient.serviceAccount(token().claim("client_id", "fdrop-ci").build(),
                "service-account-fdrop-ci")).isTrue();
    }

    @Test
    void aPersonNamedLikeAServiceAccountIsNot() {
        // signed in through a client, so the token has a user session and no client_id
        assertThat(OidcClient.serviceAccount(token().claim("sid", "s1").build(), "service-account-fdrop-ci"))
                .isFalse();
        assertThat(OidcClient.serviceAccount(token().build(), "service-account-fdrop-ci")).isFalse();
        // even with client_id set, a user session means a person signed in
        assertThat(OidcClient.serviceAccount(token().claim("client_id", "fdrop-ci").claim("sid", "s1").build(),
                "service-account-fdrop-ci")).isFalse();
    }

    @Test
    void theClientMustBeTheOneTheNameBelongsTo() {
        assertThat(OidcClient.serviceAccount(token().claim("client_id", "fdrop-web").build(),
                "service-account-fdrop-ci")).isFalse();
        assertThat(OidcClient.serviceAccount(token().claim("client_id", "fdrop-ci").build(), "jugash")).isFalse();
    }
}
