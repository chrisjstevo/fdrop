package io.fdrop.core;

import io.fdrop.core.auth.KeycloakAdmin.KcCredential;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.auth.TwoFactorService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keycloak's users and credentials for tests: usernames starting {@code admin} are admins, everyone else is a
 * standard user (matching {@link TestSupport}), and nobody has a second factor until a test gives them one.
 */
public final class FakeDirectory implements TwoFactorService.Directory {

    private final Map<String, List<KcCredential>> creds = new ConcurrentHashMap<>();

    /** Gives the user an authenticator app (one-time code {@code 123456} in {@link FakeOidc}). */
    public FakeDirectory withOtp(String username) {
        add(username, "otp");
        return this;
    }

    public FakeDirectory withSecurityKey(String username) {
        add(username, "webauthn");
        return this;
    }

    private void add(String username, String type) {
        List<KcCredential> list = creds.computeIfAbsent("sub-" + username, k -> new ArrayList<>());
        list.add(new KcCredential(type + "-" + (list.size() + 1), type, type, System.currentTimeMillis(), null));
    }

    @Override
    public Optional<Principal> findByUsername(String username) {
        return Optional.of(username.startsWith("admin") ? TestSupport.admin(username) : TestSupport.user(username));
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
}
