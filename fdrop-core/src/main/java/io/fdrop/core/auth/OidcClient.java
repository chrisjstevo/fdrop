package io.fdrop.core.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.ClientCredentialsGrant;
import com.nimbusds.oauth2.sdk.ResourceOwnerPasswordCredentialsGrant;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenErrorResponse;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.oauth2.sdk.RefreshTokenGrant;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.claims.ACR;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything fdrop asks of Keycloak over OpenID Connect: browser login (authorization code + PKCE), token
 * refresh, SFTP password checks (password grant on the {@code fdrop-sftp} client), bearer JWT validation, and
 * back-channel logout tokens. Provider metadata is fetched lazily so fdrop starts while Keycloak is still coming up.
 */
public class OidcClient {

    private static final Logger log = LoggerFactory.getLogger(OidcClient.class);
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    /** Result of a successful token request; {@code acr} is the ID token's, for browser logins. */
    public record Tokens(String idToken, String accessToken, String refreshToken, long accessExpiresAt,
                         Principal principal, String sid, String acr) {
    }

    /**
     * A validated bearer token. {@code acr} is the token's ACR claim; {@code serviceAccount} is true for
     * client-credentials tokens, which belong to scripts rather than people.
     */
    public record Bearer(Principal principal, String acr, boolean serviceAccount) {
    }

    private final FdropConfig.Oidc cfg;
    private final FdropMetrics metrics;
    private volatile OIDCProviderMetadata meta;
    private volatile DefaultJWTProcessor<SecurityContext> accessProcessor;
    private volatile DefaultJWTProcessor<SecurityContext> logoutProcessor;
    private volatile IDTokenValidator idTokenValidator;

    /** Trusts the JDK's CAs and {@code auth.oidc.trustedCas}, for every call to Keycloak. */
    private final javax.net.ssl.SSLContext tls;

    public OidcClient(FdropConfig.Oidc cfg, FdropMetrics metrics) {
        this.cfg = cfg;
        this.metrics = metrics;
        io.fdrop.core.util.TrustedCas.requireHttps("auth.oidc.issuer", cfg.issuer);
        this.tls = io.fdrop.core.util.TrustedCas.context(cfg.trustedCas);
    }

    /** The TLS context for Keycloak calls: also used by the Admin REST API client. */
    public javax.net.ssl.SSLContext tls() {
        return tls;
    }

    /** Fetches provider metadata if not yet known. Throws {@code 503} when Keycloak is unreachable. */
    public synchronized OIDCProviderMetadata metadata() {
        if (meta != null) {
            return meta;
        }
        long start = System.nanoTime();
        try {
            OIDCProviderMetadata m = OIDCProviderMetadata.resolve(new Issuer(cfg.issuer), req -> {
                req.setConnectTimeout(CONNECT_TIMEOUT_MS);
                req.setReadTimeout(READ_TIMEOUT_MS);
                req.setSSLSocketFactory(tls.getSocketFactory());
            });
            JWKSource<SecurityContext> jwks = JWKSourceBuilder.<SecurityContext>create(m.getJWKSetURI().toURL(),
                    new com.nimbusds.jose.util.DefaultResourceRetriever(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
                            512 * 1024, true, tls.getSocketFactory()))
                    .retrying(true).build();

            DefaultJWTProcessor<SecurityContext> ap = new DefaultJWTProcessor<>();
            ap.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256,
                    JWSAlgorithm.PS256), jwks));
            ap.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt"),
                    null));
            ap.setJWTClaimsSetVerifier((claims, ctx) -> {
                if (!cfg.issuer.equals(claims.getIssuer())) {
                    throw new com.nimbusds.jwt.proc.BadJWTException("unexpected issuer");
                }
                if (claims.getExpirationTime() == null
                        || claims.getExpirationTime().getTime() < System.currentTimeMillis() - 30_000) {
                    throw new com.nimbusds.jwt.proc.BadJWTException("expired");
                }
            });

            DefaultJWTProcessor<SecurityContext> lp = new DefaultJWTProcessor<>();
            lp.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256,
                    JWSAlgorithm.PS256), jwks));
            lp.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT,
                    new JOSEObjectType("logout+jwt"), null));
            lp.setJWTClaimsSetVerifier((claims, ctx) -> {
                if (!cfg.issuer.equals(claims.getIssuer())) {
                    throw new com.nimbusds.jwt.proc.BadJWTException("unexpected issuer");
                }
                if (claims.getAudience() == null || !claims.getAudience().contains(cfg.webClientId)) {
                    throw new com.nimbusds.jwt.proc.BadJWTException("unexpected audience");
                }
                Object events = claims.getClaim("events");
                if (!(events instanceof Map<?, ?> ev)
                        || !ev.containsKey("http://schemas.openid.net/event/backchannel-logout")) {
                    throw new com.nimbusds.jwt.proc.BadJWTException("not a logout token");
                }
            });

            // the same key source as the other validators, so it also trusts auth.oidc.trustedCas
            IDTokenValidator idv = new IDTokenValidator(m.getIssuer(), new ClientID(cfg.webClientId),
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwks), null);
            idv.setMaxClockSkew(60);

            this.accessProcessor = ap;
            this.logoutProcessor = lp;
            this.idTokenValidator = idv;
            this.meta = m;
            metrics.keycloakCall("discovery", true, System.nanoTime() - start);
            return m;
        } catch (Exception e) {
            metrics.keycloakCall("discovery", false, System.nanoTime() - start);
            log.warn("Keycloak discovery at {} failed: {}", cfg.issuer, e.toString());
            throw FdropException.unavailable("identity provider unreachable");
        }
    }

    public boolean reachable() {
        try {
            metadata();
            return true;
        } catch (FdropException e) {
            return false;
        }
    }

    // ---- browser login ----

    /**
     * The Keycloak authorize URL. {@code acr} (may be null) asks for that level of authentication, for a
     * two-factor step-up; {@code kcAction} (may be null) starts a Keycloak application-initiated action such as
     * {@code CONFIGURE_TOTP}.
     */
    public URI authorizationUri(String state, String nonce, CodeVerifier verifier, URI redirect, String acr,
                                String kcAction) {
        OIDCProviderMetadata m = metadata();
        AuthenticationRequest.Builder b = new AuthenticationRequest.Builder(new ResponseType(ResponseType.Value.CODE),
                new Scope("openid", "profile", "email"), new ClientID(cfg.webClientId), redirect)
                .endpointURI(m.getAuthorizationEndpointURI())
                .state(new State(state))
                .nonce(new Nonce(nonce))
                .codeChallenge(verifier, CodeChallengeMethod.S256);
        if (acr != null) {
            b.acrValues(List.of(new ACR(acr)));
        }
        if (kcAction != null) {
            b.customParameter("kc_action", kcAction);
        }
        return b.build().toURI();
    }

    public Tokens exchangeCode(String code, CodeVerifier verifier, URI redirect, String expectedNonce) {
        Tokens t = tokenRequest("token", new AuthorizationCodeGrant(new AuthorizationCode(code), redirect, verifier),
                webAuth(), true, Map.of());
        try {
            IDTokenClaimsSet id = idTokenValidator.validate(com.nimbusds.jwt.JWTParser.parse(t.idToken()),
                    new Nonce(expectedNonce));
            Object sid = id.getClaim("sid");
            ACR acr = id.getACR();
            return new Tokens(t.idToken(), t.accessToken(), t.refreshToken(), t.accessExpiresAt(), t.principal(),
                    sid == null ? null : sid.toString(), acr == null ? null : acr.getValue());
        } catch (Exception e) {
            throw FdropException.unauthorized("invalid ID token: " + e.getMessage());
        }
    }

    public Tokens refresh(String refreshToken) {
        return tokenRequest("token", new RefreshTokenGrant(new RefreshToken(refreshToken)), webAuth(), false,
                Map.of());
    }

    /**
     * Checks an SFTP username and password with a password grant on {@code fdrop-sftp}; {@code totp} (may be
     * null) is the user's one-time code, checked by the realm's direct grant flow. Returns null when the
     * credentials are rejected; throws 503 when Keycloak cannot be reached.
     */
    public Principal passwordLogin(String username, String password, String totp) {
        try {
            return tokenRequest("password_grant", new ResourceOwnerPasswordCredentialsGrant(username,
                    new Secret(password)), sftpAuth(), false, totp == null ? Map.of() : Map.of("totp", totp))
                    .principal();
        } catch (FdropException e) {
            if (e.status() == 401) {
                return null;
            }
            throw e;
        }
    }

    /** An access token for the {@code fdrop-sftp} service account, used for the Keycloak Admin REST API. */
    public Tokens serviceAccountToken() {
        return tokenRequest("admin_api", new ClientCredentialsGrant(), sftpAuth(), false, Map.of());
    }

    public URI endSessionUri(String idTokenHint, URI postLogoutRedirect) {
        URI endpoint = metadata().getEndSessionEndpointURI();
        if (endpoint == null) {
            return postLogoutRedirect;
        }
        String q = "client_id=" + enc(cfg.webClientId) + "&post_logout_redirect_uri=" + enc(postLogoutRedirect.toString())
                + (idTokenHint != null ? "&id_token_hint=" + enc(idTokenHint) : "");
        return URI.create(endpoint + (endpoint.getQuery() == null ? "?" : "&") + q);
    }

    // ---- token validation ----

    /** Validates a script's bearer token: signature, issuer, expiry, and audience {@code fdrop-api}. */
    public Bearer verifyBearer(String jwt) {
        JWTClaimsSet claims = verifyAccessToken(jwt);
        List<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(cfg.apiAudience)) {
            throw FdropException.unauthorized("token audience must include " + cfg.apiAudience);
        }
        Principal p = principal(claims);
        Object acr = claims.getClaim("acr");
        return new Bearer(p, acr == null ? null : acr.toString(), serviceAccount(claims, p.username()));
    }

    /**
     * Whether a token is a client-credentials token, which stands for a script rather than a person. Keycloak names
     * the service-account user {@code service-account-<client id>}, puts that client in {@code client_id}, and opens
     * no user session ({@code sid}). The name alone is not enough: a person can be given a name like that.
     */
    static boolean serviceAccount(JWTClaimsSet claims, String username) {
        String prefix = "service-account-";
        if (username == null || !username.startsWith(prefix) || claims.getClaim("sid") != null) {
            return false;
        }
        Object clientId = claims.getClaim("client_id");
        return clientId != null && username.equals(prefix + clientId.toString().toLowerCase(java.util.Locale.ROOT));
    }

    public JWTClaimsSet verifyAccessToken(String jwt) {
        metadata();
        try {
            return accessProcessor.process(SignedJWT.parse(jwt), null);
        } catch (Exception e) {
            throw FdropException.unauthorized("invalid token: " + e.getMessage());
        }
    }

    /** Validates a back-channel logout token and returns its {@code sid} and {@code sub}. */
    public Map<String, String> verifyLogoutToken(String jwt) {
        metadata();
        try {
            JWTClaimsSet c = logoutProcessor.process(SignedJWT.parse(jwt), null);
            Object sid = c.getClaim("sid");
            return Map.of("sub", c.getSubject() == null ? "" : c.getSubject(), "sid", sid == null ? "" : sid.toString());
        } catch (Exception e) {
            throw FdropException.badRequest("invalid logout token: " + e.getMessage());
        }
    }

    public Principal principal(JWTClaimsSet claims) {
        Set<String> roles = realmRoles(claims);
        try {
            return Principal.of(claims.getSubject(), claims.getStringClaim("preferred_username"),
                    claims.getStringClaim("email"), roles, cfg);
        } catch (java.text.ParseException e) {
            throw FdropException.unauthorized("malformed token claims");
        }
    }

    @SuppressWarnings("unchecked")
    static Set<String> realmRoles(JWTClaimsSet claims) {
        Object ra = claims.getClaim("realm_access");
        if (ra instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> roles) {
            return Set.copyOf((Collection<String>) roles);
        }
        return Set.of();
    }

    // ---- internals ----

    private ClientSecretBasic webAuth() {
        return new ClientSecretBasic(new ClientID(cfg.webClientId), new Secret(cfg.webClientSecret));
    }

    private ClientSecretBasic sftpAuth() {
        return new ClientSecretBasic(new ClientID(cfg.sftpClientId), new Secret(cfg.sftpClientSecret));
    }

    private Tokens tokenRequest(String op, AuthorizationGrant grant, ClientSecretBasic auth, boolean oidc,
                                Map<String, String> extra) {
        OIDCProviderMetadata m = metadata();
        long start = System.nanoTime();
        try {
            TokenRequest.Builder tb = new TokenRequest.Builder(m.getTokenEndpointURI(), auth, grant);
            extra.forEach(tb::customParameter);
            HTTPRequest http = tb.build().toHTTPRequest();
            http.setConnectTimeout(CONNECT_TIMEOUT_MS);
            http.setReadTimeout(READ_TIMEOUT_MS);
            http.setSSLSocketFactory(tls.getSocketFactory());
            TokenResponse resp = oidc ? OIDCTokenResponseParser.parse(http.send()) : TokenResponse.parse(http.send());
            metrics.keycloakCall(op, true, System.nanoTime() - start);
            if (!resp.indicatesSuccess()) {
                TokenErrorResponse err = resp.toErrorResponse();
                String code = err.getErrorObject() == null ? "error" : err.getErrorObject().getCode();
                throw FdropException.unauthorized("identity provider rejected the request: " + code);
            }
            String idToken = null;
            AccessToken access;
            RefreshToken refresh;
            if (resp instanceof OIDCTokenResponse oidcResp) {
                JWT id = oidcResp.getOIDCTokens().getIDToken();
                idToken = id == null ? null : id.serialize();
                access = oidcResp.getOIDCTokens().getAccessToken();
                refresh = oidcResp.getOIDCTokens().getRefreshToken();
            } else {
                var tokens = resp.toSuccessResponse().getTokens();
                access = tokens.getAccessToken();
                refresh = tokens.getRefreshToken();
                Object rawId = resp.toSuccessResponse().getCustomParameters().get("id_token");
                idToken = rawId == null ? null : rawId.toString();
            }
            JWTClaimsSet claims = verifyAccessToken(access.getValue());
            long expiresAt = access.getLifetime() > 0 ? System.currentTimeMillis() + access.getLifetime() * 1000
                    : claims.getExpirationTime().getTime();
            return new Tokens(idToken, access.getValue(), refresh == null ? null : refresh.getValue(), expiresAt,
                    principal(claims), null, null);
        } catch (FdropException e) {
            throw e;
        } catch (Exception e) {
            metrics.keycloakCall(op, false, System.nanoTime() - start);
            throw FdropException.unavailable("identity provider request failed: " + e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public FdropConfig.Oidc config() {
        return cfg;
    }
}
