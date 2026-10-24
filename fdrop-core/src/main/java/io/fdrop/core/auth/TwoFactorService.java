package io.fdrop.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fdrop.core.audit.Audit;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who must sign in with a second factor, and the factors each user has in Keycloak. Keycloak asks for and checks
 * the factors; fdrop only reads which ones a user has (cached for five minutes) and removes them on request.
 */
public class TwoFactorService {

    public static final String PROBLEM_TYPE = "two-factor-required";

    public enum Policy {
        NONE, ADMINS, ALL;

        static Policy parse(String s) {
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("auth.twoFactor.require must be none, admins or all, not " + s);
            }
        }
    }

    /** What fdrop needs from Keycloak; the Admin REST API in production, a fake in tests. */
    public interface Directory {
        Optional<Principal> findByUsername(String username);

        List<KeycloakAdmin.KcCredential> credentials(String sub);

        void deleteCredential(String sub, String credentialId);
    }

    /**
     * A second factor as the Account page shows it.
     *
     * @param kind      {@code otp}, {@code security-key} or {@code recovery-codes}
     * @param remaining codes left, for recovery codes only
     */
    public record Factor(String id, String kind, String label, long createdAt, Integer remaining) {
    }

    private record Entry(List<Factor> factors, long expiresAt) {
    }

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Policy policy;
    private final FdropConfig.TwoFactor cfg;
    private volatile Directory directory;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public TwoFactorService(FdropConfig.TwoFactor cfg, Directory directory) {
        this.cfg = cfg;
        this.policy = Policy.parse(cfg.require);
        this.directory = directory;
    }

    /** Production directory: the Keycloak Admin REST API, with role names from config. */
    public static Directory keycloak(KeycloakAdmin admin, FdropConfig.Oidc oidc) {
        return new Directory() {
            @Override
            public Optional<Principal> findByUsername(String username) {
                return admin.findByUsername(username).filter(KeycloakAdmin.KcUser::enabled).map(u -> {
                    List<String> roles = admin.realmRoles(u.id());
                    return Principal.of(u.id(), u.username(), u.email(), roles, oidc);
                });
            }

            @Override
            public List<KeycloakAdmin.KcCredential> credentials(String sub) {
                return admin.credentials(sub);
            }

            @Override
            public void deleteCredential(String sub, String credentialId) {
                admin.deleteCredential(sub, credentialId);
            }
        };
    }

    /** Replaces the directory; for tests that run without Keycloak. */
    public void useDirectory(Directory d) {
        this.directory = d;
        cache.clear();
    }

    public Policy policy() {
        return policy;
    }

    public FdropConfig.TwoFactor config() {
        return cfg;
    }

    /** True when the ACR value from a token means Keycloak checked a second factor. */
    public boolean isTwoFactorAcr(String acr) {
        return acr != null && acr.equals(cfg.acr);
    }

    /** The policy alone: does this user have to use a second factor, whether or not they have one yet? */
    public boolean policyRequires(Principal p) {
        if (p.app()) {
            return false;
        }
        return switch (policy) {
            case NONE -> false;
            case ADMINS -> p.admin();
            case ALL -> p.user() || p.admin();
        };
    }

    /** The policy, or a factor the user set up themselves: such a user is always asked for it. */
    public boolean needed(Principal p) {
        if (p.app()) {
            // application accounts never use a second factor, whatever the policy; any factor found is removed
            return false;
        }
        return policyRequires(p) || hasFactor(p.sub());
    }

    public boolean hasFactor(String sub) {
        return factors(sub).stream().anyMatch(f -> !f.kind().equals("recovery-codes"));
    }

    public boolean hasOtp(String sub) {
        return factors(sub).stream().anyMatch(f -> f.kind().equals("otp"));
    }

    public Optional<Principal> findByUsername(String username) {
        return directory.findByUsername(username);
    }

    public List<Factor> factors(String sub) {
        Entry e = cache.get(sub);
        if (e != null && e.expiresAt() > System.currentTimeMillis()) {
            return e.factors();
        }
        List<Factor> out = new ArrayList<>();
        for (KeycloakAdmin.KcCredential c : directory.credentials(sub)) {
            String kind = kind(c.type());
            if (kind != null) {
                out.add(new Factor(c.id(), kind, c.userLabel(), c.createdDate(),
                        kind.equals("recovery-codes") ? remainingCodes(c.credentialData()) : null));
            }
        }
        List<Factor> factors = List.copyOf(out);
        cache.put(sub, new Entry(factors, System.currentTimeMillis() + TTL.toMillis()));
        return factors;
    }

    /** Forget cached factors, after Keycloak may have changed them (an enrolment, a removal, a reset). */
    public void invalidate(String sub) {
        cache.remove(sub);
    }

    /**
     * Removes one of the caller's own factors. Needs a two-factor session, and refuses to remove the last
     * factor of a user the policy covers.
     */
    public void removeOwn(Principal p, boolean twoFactorSession, String factorId) {
        if (!twoFactorSession) {
            throw required("sign in with your second factor before removing one");
        }
        invalidate(p.sub());
        List<Factor> factors = factors(p.sub());
        Factor f = factors.stream().filter(x -> x.id().equals(factorId)).findFirst()
                .orElseThrow(() -> FdropException.notFound("no such factor"));
        long othersLeft = factors.stream().filter(x -> !x.id().equals(factorId))
                .filter(x -> !x.kind().equals("recovery-codes")).count();
        if (!f.kind().equals("recovery-codes") && othersLeft == 0 && policyRequires(p)) {
            throw FdropException.conflict("you must keep at least one second factor");
        }
        directory.deleteCredential(p.sub(), factorId);
        invalidate(p.sub());
        Audit.log("two_factor.removed", "user", p.username(), "kind", f.kind());
    }

    /** Deletes every second factor a user has (an admin reset). Returns how many were removed. */
    public int removeAll(String sub) {
        invalidate(sub);
        int n = 0;
        for (Factor f : factors(sub)) {
            directory.deleteCredential(sub, f.id());
            n++;
        }
        invalidate(sub);
        return n;
    }

    /** A {@code 403} whose problem type tells the UI to sign in again with a second factor. */
    public static FdropException required(String detail) {
        return new FdropException(403, "Two-factor sign-in required", detail).withType(PROBLEM_TYPE);
    }

    static String kind(String credentialType) {
        return switch (credentialType) {
            case "otp" -> "otp";
            case "webauthn", "webauthn-passwordless" -> "security-key";
            case "recovery-authn-codes" -> "recovery-codes";
            default -> null;
        };
    }

    static Integer remainingCodes(String credentialData) {
        if (credentialData == null) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(credentialData).get("remainingCodes");
            return n == null || !n.isNumber() ? null : n.asInt();
        } catch (IOException e) {
            return null;
        }
    }
}
