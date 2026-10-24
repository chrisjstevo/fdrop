package io.fdrop.core;

import io.fdrop.core.auth.KeycloakAdmin.KcCredential;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.auth.TwoFactorService;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.service.AppAccountService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keycloak's users and credentials for tests: usernames starting {@code admin} are admins, {@code app} are
 * application accounts, everyone else is a standard user (matching {@link TestSupport}), and nobody has a second
 * factor until a test gives them one. Application accounts can be disabled, and created ones are remembered.
 */
public final class FakeDirectory implements TwoFactorService.Directory {

    private final Map<String, List<KcCredential>> creds = new ConcurrentHashMap<>();
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private final Map<String, String> passwords = new ConcurrentHashMap<>();

    /** Gives the user an authenticator app (one-time code {@code 123456} in {@link FakeOidc}). */
    public FakeDirectory withOtp(String username) {
        add(username, "otp");
        return this;
    }

    public FakeDirectory withSecurityKey(String username) {
        add(username, "webauthn");
        return this;
    }

    public void disable(String username) {
        disabled.add("sub-" + username);
    }

    /** The last password fdrop set for an application account. */
    public String password(String username) {
        return passwords.get("sub-" + username);
    }

    private void add(String username, String type) {
        List<KcCredential> list = creds.computeIfAbsent("sub-" + username, k -> new ArrayList<>());
        list.add(new KcCredential(type + "-" + (list.size() + 1), type, type, System.currentTimeMillis(), null));
    }

    @Override
    public Optional<Principal> findByUsername(String username) {
        return disabled.contains("sub-" + username) ? Optional.empty() : Optional.of(TestSupport.byName(username));
    }

    @Override
    public List<KcCredential> credentials(String sub) {
        return List.copyOf(creds.getOrDefault(sub, List.of()));
    }

    @Override
    public void deleteCredential(String sub, String credentialId) {
        List<KcCredential> list = creds.get(sub);
        if (list != null) {
            list.removeIf(c -> c.id().equals(credentialId));
        }
    }

    /** Application accounts as Keycloak would hold them; ids are {@code sub-<name>} like everyone else's. */
    public AppAccountService.Directory applications() {
        return new AppAccountService.Directory() {
            @Override
            public String create(String username) {
                if (!username.startsWith("app")) {
                    throw FdropException.badRequest("test application names start with 'app'");
                }
                return "sub-" + username;
            }

            @Override
            public void setPassword(String id, String password) {
                passwords.put(id, password);
            }

            @Override
            public void setEnabled(String id, boolean enabled) {
                if (enabled) {
                    disabled.remove(id);
                } else {
                    disabled.add(id);
                }
            }

            @Override
            public Optional<Principal> active(String id) {
                return disabled.contains(id) ? Optional.empty()
                        : Optional.of(TestSupport.byName(id.substring("sub-".length())));
            }

            @Override
            public void logout(String id) {
            }

            @Override
            public void delete(String id) {
                disabled.add(id);
            }
        };
    }
}
