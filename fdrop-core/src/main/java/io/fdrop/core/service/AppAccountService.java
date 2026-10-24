package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.ClientCertificates;
import io.fdrop.core.auth.KeycloakAdmin;
import io.fdrop.core.auth.OidcClient;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.auth.TwoFactorService;
import io.fdrop.core.auth.UserStatusCache;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.AppAccountRepo;
import io.fdrop.core.db.Db;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.model.Enums.AppMethod;
import io.fdrop.core.model.Enums.CertMatch;
import io.fdrop.core.model.Enums.CertState;
import io.fdrop.core.model.Enums.UserKind;
import io.fdrop.core.model.Records.AppAccount;
import io.fdrop.core.model.Records.AppClientCert;
import io.fdrop.core.model.Records.SshKey;
import io.fdrop.core.model.Records.User;
import io.fdrop.core.util.Cidr;
import io.fdrop.core.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application accounts: non-person accounts for other systems. Admins create them; they sign in with a client
 * certificate, a generated password or an SSH key, never need a second factor, and join folders as members only.
 * Keycloak holds the account (a user in {@code auth.applications.group}, which carries {@code fdrop-app}); fdrop
 * holds its certificates, allowed methods and source addresses.
 */
public final class AppAccountService {

    private static final Logger log = LoggerFactory.getLogger(AppAccountService.class);
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration PASSWORD_CACHE = Duration.ofMinutes(5);

    /** What fdrop needs from Keycloak for application accounts; the Admin REST API in production. */
    public interface Directory {
        /** Creates an enabled user with no email and no required actions, in the applications group. */
        String create(String username);

        void setPassword(String id, String password);

        void setEnabled(String id, boolean enabled);

        /** The account with its current roles, or empty when it is disabled or deleted. */
        Optional<Principal> active(String id);

        void logout(String id);

        void delete(String id);
    }

    /** A registered certificate as the admin screen shows it. */
    public record CertView(String id, CertMatch match, String sha256, String subject, String san, Long notAfter,
                           CertState state, long createdAt) {
    }

    /** A registered SSH key as the admin screen shows it. */
    public record KeyView(String id, String name, String type, String fingerprint, long createdAt, Long lastUsedAt) {
    }

    /** An application account as the admin screen shows it. */
    public record AppView(String id, String username, String description, String responsible, boolean enabled,
                          Set<AppMethod> allowedMethods, List<String> allowedSources, Long passwordSetAt,
                          Long passwordExpiresAt, List<CertView> certificates, List<KeyView> sshKeys,
                          Long nearestExpiry, Long lastLoginAt, long createdAt, long usedBytes, long quotaBytes) {
    }

    /** What creating an account or a new password produced; the password is shown once. */
    public record Created(AppView account, String password) {
    }

    public record CreateInput(String username, String description, String responsible, Set<AppMethod> methods,
                              List<String> sources) {
    }

    /** Fields left null are unchanged. */
    public record UpdateInput(String description, String responsible, Set<AppMethod> methods, List<String> sources,
                              Boolean enabled) {
    }

    /** A new certificate: a PEM (pinned), or the expected subject DN or SAN (by subject). */
    public record CertInput(String pem, String subject, String san) {
    }

    private record CachedPassword(Principal principal, String username, long expiresAt) {
    }

    private final FdropConfig cfg;
    private final Db db;
    private final AppAccountRepo repo;
    private final UserRepo users;
    private final UserAdminService userAdmin;
    private final TwoFactorService twoFactor;
    private final OidcClient oidc;
    private final ClientCertificates certs;
    private final FdropMetrics metrics;
    private volatile Directory directory;
    private final byte[] cacheKey = new byte[32];
    private final Map<String, CachedPassword> passwordCache = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    /** Logins already recorded: an HTTP client signs in on every request, but is logged once per window. */
    private final Map<String, Long> recorded = new ConcurrentHashMap<>();

    public AppAccountService(FdropConfig cfg, Db db, AppAccountRepo repo, UserRepo users, UserAdminService userAdmin,
                             TwoFactorService twoFactor, OidcClient oidc, ClientCertificates certs,
                             FdropMetrics metrics, Directory directory) {
        this.cfg = cfg;
        this.db = db;
        this.repo = repo;
        this.users = users;
        this.userAdmin = userAdmin;
        this.twoFactor = twoFactor;
        this.oidc = oidc;
        this.certs = certs;
        this.metrics = metrics;
        this.directory = directory;
        RANDOM.nextBytes(cacheKey);
    }

    /** Production directory: the Keycloak Admin REST API, with the status cache used for key logins. */
    public static Directory keycloak(KeycloakAdmin admin, UserStatusCache status, FdropConfig cfg) {
        return new Directory() {
            @Override
            public String create(String username) {
                String group = admin.groupId(cfg.auth.applications.group).orElseThrow(() -> FdropException.unavailable(
                        "the Keycloak group '" + cfg.auth.applications.group + "' does not exist"));
                // Keycloak's default user profile requires email, first and last name, and refuses password
                // grants ("Account is not fully set up") to users without them: give placeholders
                String id = admin.createUser(username, placeholderEmail(username), true, username,
                        "Application account");
                try {
                    admin.addToGroup(id, group);
                } catch (RuntimeException e) {
                    admin.deleteUser(id);
                    throw e;
                }
                return id;
            }

            @Override
            public void setPassword(String id, String password) {
                admin.setPassword(id, password);
            }

            @Override
            public void setEnabled(String id, boolean enabled) {
                admin.setEnabled(id, enabled);
                status.invalidate(id);
            }

            @Override
            public Optional<Principal> active(String id) {
                return status.active(id);
            }

            @Override
            public void logout(String id) {
                admin.logoutUser(id);
            }

            @Override
            public void delete(String id) {
                admin.deleteUser(id);
                status.invalidate(id);
            }
        };
    }

    /** An address that can never receive mail (RFC 2606 {@code .invalid}), unique per account. */
    static String placeholderEmail(String username) {
        return username + "@applications.invalid";
    }

    /** Replaces the directory; for tests that run without Keycloak. */
    public void useDirectory(Directory d) {
        this.directory = d;
    }

    // ---- admin ----

    public List<AppView> list(Principal admin) {
        DestinationService.requireAdmin(admin);
        List<AppView> out = new ArrayList<>();
        for (AppAccount a : repo.all()) {
            out.add(view(a));
        }
        return out;
    }

    public AppView get(Principal admin, String id) {
        DestinationService.requireAdmin(admin);
        return view(find(id));
    }

    public Created create(Principal admin, CreateInput in) {
        DestinationService.requireAdmin(admin);
        requireManagement();
        String name = Names.label(in.username(), "name", 255).toLowerCase(java.util.Locale.ROOT);
        if (!name.matches("[a-z0-9][a-z0-9._-]*")) {
            throw FdropException.badRequest("names use lower-case letters, digits, '.', '_' and '-'");
        }
        Set<AppMethod> methods = methods(in.methods());
        List<String> sources = sources(in.sources());
        String responsible = responsibleSub(in.responsible());
        String id = directory.create(name);
        String password = methods.contains(AppMethod.PASSWORD) ? randomPassword() : null;
        long now = System.currentTimeMillis();
        if (password != null) {
            directory.setPassword(id, password);
        }
        String desc = in.description() == null || in.description().isBlank() ? null
                : Names.label(in.description(), "description", 1000);
        db.inTransaction(() -> {
            users.upsert(id, name, null, cfg.limits.defaultAppQuota.bytes(), UserKind.APPLICATION);
            repo.insert(new AppAccount(id, desc, responsible, methods, sources, password == null ? null : now,
                    admin.sub(), now, null));
            return null;
        });
        Audit.log("app.create", "user", admin.username(), "account", name, "methods", methods);
        return new Created(view(find(id)), password);
    }

    public AppView update(Principal admin, String id, UpdateInput in) {
        DestinationService.requireAdmin(admin);
        AppAccount a = find(id);
        String username = username(id);
        AppAccount changed = new AppAccount(a.sub(),
                in.description() == null ? a.description()
                        : in.description().isBlank() ? null : Names.label(in.description(), "description", 1000),
                in.responsible() == null ? a.responsibleSub() : responsibleSub(in.responsible()),
                in.methods() == null ? a.allowedMethods() : methods(in.methods()),
                in.sources() == null ? a.allowedSources() : sources(in.sources()),
                a.passwordSetAt(), a.createdBy(), a.createdAt(), a.lastLoginAt());
        repo.update(changed);
        if (in.enabled() != null) {
            requireManagement();
            directory.setEnabled(id, in.enabled());
            if (!in.enabled()) {
                forget(username);
                userAdmin.endSessions(id, username);
            }
            Audit.log(in.enabled() ? "app.enable" : "app.disable", "user", admin.username(), "account", username);
        }
        if (in.methods() != null || in.sources() != null) {
            forget(username);
        }
        Audit.log("app.update", "user", admin.username(), "account", username, "methods", changed.allowedMethods(),
                "sources", String.join(",", changed.allowedSources()));
        return view(find(id));
    }

    /** A new generated password; the old one stops working at once. */
    public Created newPassword(Principal admin, String id) {
        DestinationService.requireAdmin(admin);
        requireManagement();
        find(id);
        String password = randomPassword();
        directory.setPassword(id, password);
        repo.setPasswordSetAt(id, System.currentTimeMillis());
        String username = username(id);
        forget(username);
        Audit.log("app.password", "user", admin.username(), "account", username);
        return new Created(view(find(id)), password);
    }

    public AppView addCertificate(Principal admin, String id, CertInput in) {
        DestinationService.requireAdmin(admin);
        find(id);
        long now = System.currentTimeMillis();
        AppClientCert c;
        if (in.pem() != null && !in.pem().isBlank()) {
            X509Certificate leaf = ClientCertificates.parsePem(in.pem()).get(0);
            c = new AppClientCert(Hashing.newId("crt"), id, CertMatch.PINNED, ClientCertificates.fingerprint(leaf),
                    ClientCertificates.displaySubject(leaf), null, leaf.getNotAfter().getTime(), CertState.ACTIVE, now);
        } else if (in.subject() != null && !in.subject().isBlank()) {
            c = new AppClientCert(Hashing.newId("crt"), id, CertMatch.SUBJECT, null,
                    ClientCertificates.normaliseSubject(in.subject()), null, null, CertState.ACTIVE, now);
        } else if (in.san() != null && !in.san().isBlank()) {
            c = new AppClientCert(Hashing.newId("crt"), id, CertMatch.SUBJECT, null, null,
                    ClientCertificates.normaliseSan(in.san()), null, CertState.ACTIVE, now);
        } else {
            throw FdropException.badRequest("give a PEM certificate, a subject or a SAN");
        }
        repo.addCert(c);
        Audit.log("app.certificate.add", "user", admin.username(), "account", username(id), "match", c.match(),
                "sha256", c.sha256(), "subject", c.subject(), "san", c.san());
        return view(find(id));
    }

    public AppView retireCertificate(Principal admin, String id, String certId) {
        DestinationService.requireAdmin(admin);
        if (!repo.setCertState(id, certId, CertState.RETIRED)) {
            throw FdropException.notFound("no such certificate");
        }
        Audit.log("app.certificate.retire", "user", admin.username(), "account", username(id), "certificate", certId);
        return view(find(id));
    }

    public AppView addSshKey(Principal admin, String id, String name, String publicKey, SshTools ssh) {
        DestinationService.requireAdmin(admin);
        find(id);
        SshTools.ParsedKey k = ssh.parsePublicKey(publicKey);
        String label = name == null || name.isBlank() ? k.type() : Names.label(name, "name", 200);
        try {
            users.addKey(new SshKey(Hashing.newId("ssh"), id, label, k.type(), k.normalized(), k.fingerprint(),
                    System.currentTimeMillis(), null));
        } catch (Db.DbException e) {
            if (e.isUniqueViolation()) {
                throw FdropException.conflict("that key is already added");
            }
            throw e;
        }
        Audit.log("app.sshkey.add", "user", admin.username(), "account", username(id), "fingerprint", k.fingerprint());
        return view(find(id));
    }

    public AppView deleteSshKey(Principal admin, String id, String keyId) {
        DestinationService.requireAdmin(admin);
        if (!users.deleteKey(id, keyId)) {
            throw FdropException.notFound("no such key");
        }
        Audit.log("app.sshkey.delete", "user", admin.username(), "account", username(id), "key", keyId);
        return view(find(id));
    }

    /** Removes the Keycloak user and everything fdrop holds for the account. It owns no folders. */
    public void delete(Principal admin, String id) {
        DestinationService.requireAdmin(admin);
        requireManagement();
        find(id);
        String username = username(id);
        forget(username);
        try {
            directory.logout(id);
        } catch (FdropException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
        userAdmin.endSessions(id, username);
        try {
            directory.delete(id);
        } catch (FdropException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
        userAdmin.purgeLocal(id, username);
        Audit.log("app.delete", "user", admin.username(), "account", username);
    }

    // ---- sign-in ----

    /** Why an application login was refused, for the audit log and {@code fdrop_app_auth_total}. */
    public enum Refusal { FAILURE, METHOD_NOT_ALLOWED, SOURCE_REFUSED }

    /** Thrown for a refused application login; callers answer 401 (HTTP) or fail the SSH method. */
    public static final class LoginRefused extends RuntimeException {
        private final Refusal reason;

        LoginRefused(Refusal reason, String detail) {
            super(detail, null, false, false);
            this.reason = reason;
        }

        public Refusal reason() {
            return reason;
        }
    }

    /**
     * HTTP Basic or an SFTP password. Returns the account, or throws {@link LoginRefused}. A successful check is
     * cached for five minutes under an HMAC of name and password, so large chunked uploads do not call Keycloak per
     * chunk; failures are never cached, so Keycloak's brute-force lockout still applies.
     */
    public Principal passwordLogin(String username, String password, String clientIp, String protocol,
                                   String client) {
        String key = cacheKey(username, password);
        CachedPassword cached = passwordCache.get(key);
        Principal p;
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            p = cached.principal();
        } else {
            passwordCache.remove(key);
            p = oidc.passwordLogin(username, password, null);
            if (p == null || !p.app()) {
                // people do not use HTTP Basic; their SFTP logins go through the normal path
                throw refuse(protocol, AppMethod.PASSWORD, Refusal.FAILURE, username, clientIp,
                        p == null ? "wrong name or password" : "not an application account");
            }
        }
        admit(p, AppMethod.PASSWORD, clientIp, protocol, null, client);
        passwordCache.put(key, new CachedPassword(p, p.username(), System.currentTimeMillis()
                + PASSWORD_CACHE.toMillis()));
        return p;
    }

    /**
     * A client certificate from the mTLS listener or a trusted proxy. A pinned registration matches the leaf's
     * fingerprint; a by-subject one needs the chain to lead to a trusted CA and the subject or a SAN to match.
     * Every failure looks the same to the client.
     */
    public Principal certificateLogin(List<X509Certificate> chain, String clientIp, String protocol, String client) {
        if (chain == null || chain.isEmpty()) {
            throw refuse(protocol, AppMethod.MTLS, Refusal.FAILURE, null, clientIp, "no client certificate");
        }
        X509Certificate leaf = chain.get(0);
        String fp = ClientCertificates.fingerprint(leaf);
        if (!ClientCertificates.current(leaf)) {
            throw refuse(protocol, AppMethod.MTLS, Refusal.FAILURE, null, clientIp, "certificate expired or not yet"
                    + " valid: " + fp);
        }
        Set<String> subs = new LinkedHashSet<>();
        repo.activeByFingerprint(fp).forEach(c -> subs.add(c.sub()));
        if (subs.isEmpty() && certs.chainsToTrustedCa(chain)) {
            repo.activeBySubject(ClientCertificates.subject(leaf), ClientCertificates.sans(leaf))
                    .forEach(c -> subs.add(c.sub()));
        }
        if (subs.size() != 1) {
            throw refuse(protocol, AppMethod.MTLS, Refusal.FAILURE, null, clientIp, (subs.isEmpty()
                    ? "certificate matches no application account: " : "certificate matches several accounts: ") + fp);
        }
        String sub = subs.iterator().next();
        Principal p = directory.active(sub).orElseThrow(() -> refuse(protocol, AppMethod.MTLS, Refusal.FAILURE,
                usernameOrNull(sub), clientIp, "account disabled or deleted"));
        admit(p, AppMethod.MTLS, clientIp, protocol, fp, client);
        return p;
    }

    /** The account with its current roles if it is enabled; key logins check this, cached for five minutes. */
    public Optional<Principal> active(String sub) {
        return directory.active(sub);
    }

    /** An SFTP password Keycloak already accepted for this account (SFTP checks passwords itself). */
    public void passwordChecked(Principal p, String clientIp, String protocol, String client) {
        admit(p, AppMethod.PASSWORD, clientIp, protocol, null, client);
    }

    /** An SFTP public key or certificate the SSH layer already matched to this account. */
    public void keyLogin(Principal p, String fingerprint, String clientIp, String client) {
        admit(p, AppMethod.PUBLICKEY, clientIp, "sftp", fingerprint, client);
    }

    /**
     * The checks every application login goes through: the account kind, the method, source addresses and
     * password age, on every request. The login itself (audit line, last sign-in, metric, removal of any second
     * factor someone set up in Keycloak) is recorded once per five minutes for the same account, method, address and
     * credential, since an HTTP client authenticates every request, chunks included.
     *
     * @param client the client's User-Agent or SSH version string, for the audit log
     */
    private void admit(Principal p, AppMethod method, String clientIp, String protocol, String fingerprint,
                       String client) {
        if (p.mixedRoles() || !p.app()) {
            throw refuse(protocol, method, Refusal.FAILURE, p.username(), clientIp, p.mixedRoles()
                    ? "holds fdrop-app together with fdrop-user or fdrop-admin" : "not an application account");
        }
        AppAccount a = ensure(p);
        if (!a.allowedMethods().contains(method)) {
            throw refuse(protocol, method, Refusal.METHOD_NOT_ALLOWED, p.username(), clientIp,
                    method + " is not allowed for this account");
        }
        if (!a.allowedSources().isEmpty() && !fromAllowedSource(a, clientIp)) {
            throw refuse(protocol, method, Refusal.SOURCE_REFUSED, p.username(), clientIp,
                    "address not in the account's allowed sources");
        }
        if (method == AppMethod.PASSWORD) {
            Duration max = cfg.auth.applications.passwordMaxAgeDuration();
            long setAt = a.passwordSetAt() == null ? System.currentTimeMillis() : a.passwordSetAt();
            if (a.passwordSetAt() == null) {
                repo.setPasswordSetAt(p.sub(), setAt);
            }
            if (!max.isZero() && setAt + max.toMillis() < System.currentTimeMillis()) {
                throw refuse(protocol, method, Refusal.FAILURE, p.username(), clientIp, "password expired");
            }
        }
        long now = System.currentTimeMillis();
        String seen = p.sub() + "|" + method + "|" + clientIp + "|" + fingerprint + "|" + protocol;
        Long until = recorded.get(seen);
        if (until != null && until > now) {
            return;
        }
        recorded.put(seen, now + PASSWORD_CACHE.toMillis());
        if (recorded.size() > 10_000) {
            recorded.values().removeIf(t -> t <= now);
        }
        removeSecondFactors(p);
        repo.touch(p.sub());
        metrics.appAuth(protocol, method.name().toLowerCase(java.util.Locale.ROOT), "success");
        Audit.log("login", "user", p.username(), "kind", "application", "ip", clientIp, "protocol",
                protocol.toUpperCase(java.util.Locale.ROOT), "method", method.name().toLowerCase(java.util.Locale.ROOT),
                "fingerprint", fingerprint, "client", client);
    }

    private LoginRefused refuse(String protocol, AppMethod method, Refusal reason, String username, String clientIp,
                                String detail) {
        metrics.appAuth(protocol, method.name().toLowerCase(java.util.Locale.ROOT),
                reason.name().toLowerCase(java.util.Locale.ROOT));
        Audit.log("login.failed", "user", username, "kind", "application", "ip", clientIp, "protocol",
                protocol.toUpperCase(java.util.Locale.ROOT), "method", method.name().toLowerCase(java.util.Locale.ROOT),
                "reason", detail);
        return new LoginRefused(reason, detail);
    }

    private static boolean fromAllowedSource(AppAccount a, String clientIp) {
        try {
            InetAddress addr = InetAddress.getByName(clientIp);
            return a.allowedSources().stream().anyMatch(c -> Cidr.contains(c, addr));
        } catch (java.net.UnknownHostException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The fdrop rows of an account seen for the first time, e.g. one added to the group directly in Keycloak: all
     * methods allowed, no source limits. Methods without registered credentials cannot succeed anyway.
     */
    public AppAccount ensure(Principal p) {
        // runs on every request: write only when the row is missing; last sign-in is kept on app_account
        if (users.find(p.sub()).isEmpty()) {
            users.upsert(p.sub(), p.username(), p.email(), cfg.limits.defaultAppQuota.bytes(), UserKind.APPLICATION);
        }
        Optional<AppAccount> a = repo.find(p.sub());
        if (a.isPresent()) {
            return a.get();
        }
        AppAccount created = new AppAccount(p.sub(), null, null, EnumSet.allOf(AppMethod.class), List.of(), null,
                null, System.currentTimeMillis(), null);
        try {
            repo.insert(created);
        } catch (Db.DbException e) {
            if (!e.isUniqueViolation()) {
                throw e;
            }
        }
        return repo.find(p.sub()).orElse(created);
    }

    private void removeSecondFactors(Principal p) {
        try {
            if (!twoFactor.factors(p.sub()).isEmpty()) {
                int n = twoFactor.removeAll(p.sub());
                Audit.log("two_factor.removed", "user", p.username(), "kind", "application", "removed", n,
                        "reason", "application accounts do not use second factors");
            }
        } catch (FdropException e) {
            log.warn("could not check second factors of application account {}: {}", p.username(), e.getMessage());
        }
    }

    private String cacheKey(String username, String password) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cacheKey, "HmacSHA256"));
            mac.update(username.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Drops cached password checks for an account, after its password, methods or status changed. */
    private void forget(String username) {
        passwordCache.values().removeIf(c -> c.username().equals(username));
        users.findByUsername(username).ifPresent(u -> recorded.keySet().removeIf(k -> k.startsWith(u.sub() + "|")));
    }

    // ---- expiry ----

    /**
     * Passwords and certificates that expire within each warning window, for
     * {@code fdrop_app_credentials_expiring}; logs and audits each one once per window.
     */
    public Map<List<String>, Long> expiring() {
        List<Duration> windows = cfg.auth.applications.expiryWarnings.stream().map(Durations::parse).toList();
        Map<List<String>, Long> counts = new HashMap<>();
        long now = System.currentTimeMillis();
        Duration max = cfg.auth.applications.passwordMaxAgeDuration();
        for (String label : cfg.auth.applications.expiryWarnings) {
            counts.put(List.of("password", label), 0L);
            counts.put(List.of("certificate", label), 0L);
        }
        if (!max.isZero()) {
            for (AppAccount a : repo.all()) {
                if (a.passwordSetAt() != null && a.allowedMethods().contains(AppMethod.PASSWORD)) {
                    count(counts, windows, "password", a.sub(), a.sub(), a.passwordSetAt() + max.toMillis(), now);
                }
            }
        }
        for (AppClientCert c : repo.activeCerts()) {
            if (c.notAfter() != null) {
                count(counts, windows, "certificate", c.sub(), c.id(), c.notAfter(), now);
            }
        }
        return counts;
    }

    private void count(Map<List<String>, Long> counts, List<Duration> windows, String type, String sub, String id,
                       long expiresAt, long now) {
        for (int i = 0; i < windows.size(); i++) {
            if (expiresAt - now <= windows.get(i).toMillis()) {
                String label = cfg.auth.applications.expiryWarnings.get(i);
                counts.merge(List.of(type, label), 1L, Long::sum);
                if (warned.add(type + ":" + id + ":" + label)) {
                    String who = usernameOrNull(sub);
                    log.warn("application account {}: {} expires {}", who, type, java.time.Instant.ofEpochMilli(
                            expiresAt));
                    Audit.log("app.credential.expiring", "account", who, "type", type, "expiresAt", expiresAt,
                            "responsible", repo.find(sub).map(AppAccount::responsibleSub).flatMap(users::find)
                                    .map(User::username).orElse(null));
                }
            }
        }
    }

    // ---- helpers ----

    private AppView view(AppAccount a) {
        User u = users.find(a.sub()).orElseThrow();
        boolean enabled = directoryEnabled(a.sub());
        List<CertView> certViews = repo.certs(a.sub()).stream().map(c -> new CertView(c.id(), c.match(), c.sha256(),
                c.subject(), c.san(), c.notAfter(), c.state(), c.createdAt())).toList();
        List<KeyView> keys = users.keys(a.sub()).stream().map(k -> new KeyView(k.id(), k.name(), k.keyType(),
                k.fingerprint(), k.createdAt(), k.lastUsedAt())).toList();
        Duration max = cfg.auth.applications.passwordMaxAgeDuration();
        Long passwordExpires = a.passwordSetAt() == null || max.isZero() ? null : a.passwordSetAt() + max.toMillis();
        Long nearest = passwordExpires != null && a.allowedMethods().contains(AppMethod.PASSWORD) ? passwordExpires
                : null;
        for (CertView c : certViews) {
            if (c.state() == CertState.ACTIVE && c.notAfter() != null && (nearest == null || c.notAfter() < nearest)) {
                nearest = c.notAfter();
            }
        }
        String responsible = a.responsibleSub() == null ? null
                : users.find(a.responsibleSub()).map(User::username).orElse(null);
        return new AppView(a.sub(), u.username(), a.description(), responsible, enabled, a.allowedMethods(),
                a.allowedSources(), a.passwordSetAt(), passwordExpires, certViews, keys, nearest, a.lastLoginAt(),
                a.createdAt(), u.usedBytes(), u.quotaBytes());
    }

    private boolean directoryEnabled(String sub) {
        try {
            return directory.active(sub).isPresent();
        } catch (FdropException e) {
            return true;
        }
    }

    private AppAccount find(String id) {
        return repo.find(id).orElseThrow(() -> FdropException.notFound("no such application account"));
    }

    private String username(String sub) {
        return users.find(sub).map(User::username).orElse(sub);
    }

    private String usernameOrNull(String sub) {
        return users.find(sub).map(User::username).orElse(null);
    }

    /** The responsible person: a standard user fdrop knows. Required on create; blank is refused. */
    private String responsibleSub(String username) {
        if (username == null || username.isBlank()) {
            throw FdropException.badRequest("choose the person responsible for this account");
        }
        User u = users.findByUsername(username.trim())
                .orElseThrow(() -> FdropException.badRequest("no user '" + username + "' (they must have signed in"
                        + " to fdrop once)"));
        if (u.kind() != UserKind.USER) {
            throw FdropException.badRequest("the responsible person must be a person, not an application account");
        }
        return u.sub();
    }

    private static Set<AppMethod> methods(Set<AppMethod> in) {
        if (in == null || in.isEmpty()) {
            throw FdropException.badRequest("allow at least one sign-in method");
        }
        return EnumSet.copyOf(in);
    }

    private static List<String> sources(List<String> in) {
        if (in == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : in) {
            String c = s == null ? "" : s.trim();
            if (c.isEmpty()) {
                continue;
            }
            String[] parts = c.split("/");
            try {
                InetAddress addr = InetAddress.getByName(parts[0]);
                int max = addr.getAddress().length * 8;
                if (parts.length > 2 || (parts.length == 2 && (Integer.parseInt(parts[1]) < 0
                        || Integer.parseInt(parts[1]) > max)) || !parts[0].matches("[0-9a-fA-F:.]+")) {
                    throw new IllegalArgumentException();
                }
            } catch (java.net.UnknownHostException | IllegalArgumentException e) {
                throw FdropException.badRequest("not an address or CIDR range: " + c);
            }
            out.add(c);
        }
        return out;
    }

    private void requireManagement() {
        if (!cfg.auth.userManagement.enabled) {
            throw FdropException.forbidden("user management is disabled (auth.userManagement.enabled=false);"
                    + " create and disable application accounts in Keycloak by adding users to "
                    + cfg.auth.applications.group);
        }
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
