package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.KeycloakAdmin;
import io.fdrop.core.auth.KeycloakAdmin.KcUser;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.auth.TwoFactorService;
import io.fdrop.core.auth.UserStatusCache;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.DestinationRepo;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.UploadRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.UploadSession;
import io.fdrop.core.model.Records.User;
import io.fdrop.core.storage.StorageBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Admin user management through the Keycloak Admin REST API. Users live in Keycloak; fdrop's own row (quota, SSH
 * keys, folders) is created at first login.
 */
public final class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A user as the admin list shows it. */
    public record UserView(String id, String username, String email, String firstName, String lastName,
                           boolean enabled, boolean admin, boolean standard, Long usedBytes, Long quotaBytes,
                           Long lastLoginAt, long createdAt, Boolean twoFactor) {
    }

    /** What creating a user produced. */
    public record Created(UserView user, String temporaryPassword, boolean emailSent) {
    }

    private final FdropConfig cfg;
    private final KeycloakAdmin keycloak;
    private final UserStatusCache status;
    private final TwoFactorService twoFactor;
    private final UserRepo users;
    private final FolderRepo folders;
    private final DestinationRepo destinations;
    private final UploadRepo uploads;
    private final RecordRepo records;
    private final StorageBackend storage;
    private final FolderService folderService;
    private final List<SessionTerminator> terminators = new CopyOnWriteArrayList<>();

    public UserAdminService(FdropConfig cfg, KeycloakAdmin keycloak, UserStatusCache status,
                            TwoFactorService twoFactor, UserRepo users, FolderRepo folders,
                            DestinationRepo destinations, UploadRepo uploads, RecordRepo records,
                            StorageBackend storage, FolderService folderService) {
        this.cfg = cfg;
        this.keycloak = keycloak;
        this.status = status;
        this.twoFactor = twoFactor;
        this.users = users;
        this.folders = folders;
        this.destinations = destinations;
        this.uploads = uploads;
        this.records = records;
        this.storage = storage;
        this.folderService = folderService;
    }

    public void addTerminator(SessionTerminator t) {
        terminators.add(t);
    }

    public boolean managementEnabled() {
        return cfg.auth.userManagement.enabled;
    }

    public List<UserView> list(Principal admin, String search, int first, int max) {
        DestinationService.requireAdmin(admin);
        List<UserView> out = new ArrayList<>();
        for (KcUser u : keycloak.listUsers(search, first, Math.min(max, 100))) {
            List<String> roles = keycloak.realmRoles(u.id());
            Optional<User> local = users.find(u.id());
            out.add(new UserView(u.id(), u.username(), u.email(), u.firstName(), u.lastName(), u.enabled(),
                    roles.contains(cfg.auth.oidc.adminRole), roles.contains(cfg.auth.oidc.userRole),
                    local.map(User::usedBytes).orElse(null), local.map(User::quotaBytes).orElse(null),
                    local.map(User::lastLoginAt).orElse(null), u.createdTimestamp(), hasFactor(u.id())));
        }
        return out;
    }

    public Created create(Principal admin, String username, String email, String firstName, String lastName,
                          boolean makeAdmin) {
        DestinationService.requireAdmin(admin);
        requireManagement();
        String name = Names.label(username, "username", 255).toLowerCase();
        String id = keycloak.createUser(name, email, firstName, lastName);
        List<String> roles = new ArrayList<>(List.of(cfg.auth.oidc.userRole));
        if (makeAdmin) {
            roles.add(cfg.auth.oidc.adminRole);
        }
        keycloak.assignRealmRoles(id, roles);
        String temp = null;
        boolean emailed = false;
        if (email != null && !email.isBlank() && keycloak.realmHasSmtp()) {
            try {
                keycloak.sendUpdatePasswordEmail(id);
                emailed = true;
            } catch (FdropException e) {
                log.warn("execute-actions email for {} failed, falling back to a temporary password", name);
            }
        }
        if (!emailed) {
            temp = randomPassword();
            keycloak.setTemporaryPassword(id, temp);
        }
        Audit.log("user.create", "user", admin.username(), "created", name, "admin", makeAdmin);
        UserView v = new UserView(id, name, email, firstName, lastName, true, makeAdmin, true, null, null, null,
                System.currentTimeMillis(), false);
        return new Created(v, temp, emailed);
    }

    /**
     * Deletes a user in the order the spec sets: Keycloak logout, drop live SFTP and web sessions, delete in
     * Keycloak, then fdrop's own data. Owned folders pass to the admin who deleted the user.
     */
    public void delete(Principal admin, String id) {
        DestinationService.requireAdmin(admin);
        requireManagement();
        if (id.equals(admin.sub())) {
            throw FdropException.badRequest("you cannot delete yourself");
        }
        KcUser target = keycloak.getUser(id).orElseThrow(() -> FdropException.notFound("no such user"));
        List<String> roles = keycloak.realmRoles(id);
        if (roles.contains(cfg.auth.oidc.adminRole)
                && keycloak.usersWithRole(cfg.auth.oidc.adminRole).size() <= 1) {
            throw FdropException.conflict("cannot delete the last admin");
        }
        keycloak.logoutUser(id);
        endSessions(id, target.username());
        keycloak.deleteUser(id);
        status.invalidate(id);

        users.upsert(admin.sub(), admin.username(), admin.email(), cfg.limits.defaultQuota.bytes());
        for (Folder f : folders.ownedBy(id)) {
            folderService.transfer(f, admin.sub());
            folders.removeMember(f.id(), id);
        }
        folders.removeMemberships(id);
        destinations.removeAssignments(id);
        for (UploadSession s : uploads.openFor(id)) {
            try {
                if (s.backendUploadId() != null) {
                    storage.resumeMultipart(io.fdrop.core.storage.StoragePath.of(s.storageKey()), s.backendUploadId())
                            .abort();
                }
            } catch (IOException | RuntimeException e) {
                log.warn("could not abort upload {}: {}", s.id(), e.toString());
            }
        }
        uploads.deleteForUser(id);
        records.deleteForUser(id);
        try {
            storage.delete(PathResolver.homeKey(id));
        } catch (IOException e) {
            log.warn("could not delete home of {}: {}", target.username(), e.toString());
        }
        users.delete(id);
        Audit.log("user.delete", "user", admin.username(), "deleted", target.username());
    }

    /**
     * Removes every second factor of a user who has lost their device, then ends their Keycloak, browser and
     * SFTP sessions. If the policy covers them they set up a factor again at their next sign-in.
     */
    public int resetTwoFactor(Principal admin, String id) {
        DestinationService.requireAdmin(admin);
        if (id.equals(admin.sub())) {
            throw FdropException.badRequest("you cannot reset your own two-factor; ask another admin");
        }
        KcUser target = keycloak.getUser(id).orElseThrow(() -> FdropException.notFound("no such user"));
        int removed = twoFactor.removeAll(id);
        keycloak.logoutUser(id);
        endSessions(id, target.username());
        Audit.log("two_factor.reset", "user", admin.username(), "target", target.username(), "removed", removed);
        return removed;
    }

    private Boolean hasFactor(String id) {
        try {
            return twoFactor.hasFactor(id);
        } catch (FdropException e) {
            return null;
        }
    }

    private void endSessions(String id, String username) {
        for (SessionTerminator t : terminators) {
            try {
                t.terminate(id);
            } catch (RuntimeException e) {
                log.warn("ending sessions of {} failed: {}", username, e.toString());
            }
        }
    }

    private void requireManagement() {
        if (!cfg.auth.userManagement.enabled) {
            throw FdropException.forbidden("user management is disabled (auth.userManagement.enabled=false);"
                    + " manage users in Keycloak");
        }
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
