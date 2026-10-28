package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.db.DestinationRepo;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.KeyState;
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.ForwardClientKey;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.model.Records.User;
import io.fdrop.core.util.Hashing;
import io.fdrop.core.util.SecretBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Forward destinations: defined by admins, assigned to users, picked by users from a drop-down. Every forward
 * connection is mutually authenticated: fdrop checks the server (pinned host key or trusted host CA) and the server
 * checks fdrop (fdrop's own key for the destination, optionally with a user certificate). No passwords.
 */
public final class DestinationService {

    /** The fields an admin edits. */
    public record DestinationInput(String name, String description, String host, Integer port, String remoteDir,
                                   String username, ServerAuth serverAuth, String hostKeySha256,
                                   String hostCaPublicKey, Boolean overwrite, Boolean enabled) {
    }

    /** A client key without its private half. */
    public record KeyView(String id, String publicKey, String fingerprint, boolean hasCertificate, KeyState state,
                          long createdAt) {
    }

    /** A destination as the admin screen shows it. */
    public record AdminView(ForwardDestination destination, List<KeyView> keys, List<String> assignedUsers,
                            List<String> usedByFolders) {
    }

    /** A destination as a standard user's drop-down shows it. */
    public record UserView(String id, String name, String description) {
    }

    private final DestinationRepo repo;
    private final FolderRepo folders;
    private final UserRepo users;
    private final RecordRepo records;
    private final SshTools ssh;
    private final SecretBox secrets;
    private final HostPolicy hosts;

    public DestinationService(DestinationRepo repo, FolderRepo folders, UserRepo users, RecordRepo records,
                              SshTools ssh, SecretBox secrets, HostPolicy hosts) {
        this.repo = repo;
        this.folders = folders;
        this.users = users;
        this.records = records;
        this.ssh = ssh;
        this.secrets = secrets;
        this.hosts = hosts;
    }

    public SecretBox secrets() {
        return secrets;
    }

    // ---- standard users ----

    public List<UserView> forUser(Principal p) {
        if (!p.isPerson()) {
            return List.of(); // application accounts are never assigned destinations
        }
        return repo.assignedTo(p.sub()).stream().filter(ForwardDestination::enabled)
                .map(d -> new UserView(d.id(), d.name(), d.description())).toList();
    }

    // ---- admin ----

    public List<AdminView> list(Principal admin) {
        requireAdmin(admin);
        List<AdminView> out = new ArrayList<>();
        for (ForwardDestination d : repo.all()) {
            out.add(view(d));
        }
        return out;
    }

    public AdminView get(Principal admin, String id) {
        requireAdmin(admin);
        return view(find(id));
    }

    private AdminView view(ForwardDestination d) {
        List<KeyView> keys = new ArrayList<>();
        for (ForwardClientKey k : repo.keys(d.id())) {
            keys.add(new KeyView(k.id(), k.publicKey(), ssh.parsePublicKey(k.publicKey()).fingerprint(),
                    k.userCert() != null, k.state(), k.createdAt()));
        }
        List<String> assigned = repo.assignees(d.id()).stream()
                .map(sub -> users.find(sub).map(User::username).orElse(sub)).sorted().toList();
        List<String> used = folders.usingDestination(d.id()).stream().map(Folder::name).toList();
        return new AdminView(d, keys, assigned, used);
    }

    public AdminView create(Principal admin, DestinationInput in) {
        requireAdmin(admin);
        requireSecrets();
        ForwardDestination d = validate(new ForwardDestination(Hashing.newId("dst"), null, null, null, 22, null, null,
                ServerAuth.HOST_KEY, null, null, false, true, admin.sub(), System.currentTimeMillis(),
                System.currentTimeMillis()), in);
        repo.insert(d);
        newKey(d.id(), KeyState.ACTIVE);
        Audit.log("destination.create", "user", admin.username(), "destination", d.id(), "host", d.host());
        return view(d);
    }

    public AdminView update(Principal admin, String id, DestinationInput in) {
        requireAdmin(admin);
        ForwardDestination before = find(id);
        ForwardDestination d = validate(before, in);
        repo.update(d);
        if (before.enabled() != d.enabled()) {
            for (Folder f : folders.usingDestination(id)) {
                records.pauseDestination(f.id(), id, !d.enabled());
            }
        }
        Audit.log("destination.update", "user", admin.username(), "destination", id, "enabled", d.enabled());
        return view(d);
    }

    public void delete(Principal admin, String id) {
        requireAdmin(admin);
        find(id);
        List<Folder> using = folders.usingDestination(id);
        if (!using.isEmpty()) {
            throw FdropException.conflict("destination is used by " + using.size()
                    + " forward folder(s); disable it instead");
        }
        long open = records.countOpenJobs(id);
        if (open > 0) {
            throw FdropException.conflict(open + " file(s) are still waiting to be sent to this destination;"
                    + " disable it instead");
        }
        repo.delete(id);
        Audit.log("destination.delete", "user", admin.username(), "destination", id);
    }

    private ForwardDestination validate(ForwardDestination base, DestinationInput in) {
        String name = in.name() != null ? Names.label(in.name(), "name", 200) : base.name();
        String desc = in.description() != null ? in.description().strip() : base.description();
        String host = in.host() != null ? Names.label(in.host(), "host", 255) : base.host();
        int port = in.port() != null ? in.port() : base.port();
        String dir = in.remoteDir() != null ? Names.label(in.remoteDir(), "remote directory", 1000) : base.remoteDir();
        String username = in.username() != null ? Names.label(in.username(), "username", 255) : base.username();
        ServerAuth auth = in.serverAuth() != null ? in.serverAuth() : base.serverAuth();
        String fp = in.hostKeySha256() != null ? in.hostKeySha256().strip() : base.hostKeySha256();
        String ca = in.hostCaPublicKey() != null ? in.hostCaPublicKey().strip() : base.hostCaPublicKey();
        boolean overwrite = in.overwrite() != null ? in.overwrite() : base.overwrite();
        boolean enabled = in.enabled() != null ? in.enabled() : base.enabled();
        if (name == null || host == null || dir == null || username == null) {
            throw FdropException.badRequest("name, host, remote directory and username are required");
        }
        if (port < 1 || port > 65535) {
            throw FdropException.badRequest("port must be 1-65535");
        }
        hosts.check(host);
        if (auth == ServerAuth.HOST_KEY) {
            if (fp == null || !fp.startsWith("SHA256:")) {
                throw FdropException.badRequest("pin the server's host key: run a test connection and confirm its"
                        + " SHA256 fingerprint");
            }
            ca = null;
        } else {
            if (ca == null || ca.isBlank()) {
                throw FdropException.badRequest("give the host CA public key");
            }
            ca = ssh.parsePublicKey(ca).normalized();
            fp = null;
        }
        return new ForwardDestination(base.id(), name, desc, host, port, dir, username, auth, fp, ca, overwrite,
                enabled, base.createdBy(), base.createdAt(), System.currentTimeMillis());
    }

    /** Connects to an unsaved host to learn its host key, before the admin pins it. */
    public SshTools.TestResult probe(Principal admin, String host, int port) {
        requireAdmin(admin);
        hosts.check(host);
        return ssh.probe(host, port, null, null, null, null, null);
    }

    /** A full test with mutual authentication, using the newest non-retired key or the one given. */
    public SshTools.TestResult test(Principal admin, String id, String keyId) {
        requireAdmin(admin);
        ForwardDestination d = find(id);
        hosts.check(d.host());
        ForwardClientKey k = keyId != null ? repo.key(keyId).filter(x -> x.destinationId().equals(id))
                .orElseThrow(() -> FdropException.notFound("no such key"))
                : usableKey(id).orElseThrow(() -> FdropException.conflict("destination has no usable key"));
        return ssh.probe(d.host(), d.port(), d.username(), k.publicKey(), secrets.open(k.privateKeyEnc()),
                k.userCert(), d);
    }

    public KeyView generateKey(Principal admin, String id) {
        requireAdmin(admin);
        find(id);
        requireSecrets();
        ForwardClientKey k = newKey(id, KeyState.PENDING);
        Audit.log("destination.key.generate", "user", admin.username(), "destination", id, "key", k.id());
        return new KeyView(k.id(), k.publicKey(), ssh.parsePublicKey(k.publicKey()).fingerprint(), false, k.state(),
                k.createdAt());
    }

    public void setKeyState(Principal admin, String id, String keyId, KeyState state) {
        requireAdmin(admin);
        ForwardClientKey k = repo.key(keyId).filter(x -> x.destinationId().equals(id))
                .orElseThrow(() -> FdropException.notFound("no such key"));
        if (state == KeyState.RETIRED && k.state() == KeyState.ACTIVE
                && repo.keys(id).stream().filter(x -> x.state() == KeyState.ACTIVE).count() == 1) {
            throw FdropException.conflict("activate another key before retiring the last active one");
        }
        repo.setKeyState(keyId, state);
        Audit.log("destination.key." + state.name().toLowerCase(), "user", admin.username(), "destination", id,
                "key", keyId);
    }

    public void setKeyCertificate(Principal admin, String id, String keyId, String cert) {
        requireAdmin(admin);
        ForwardClientKey k = repo.key(keyId).filter(x -> x.destinationId().equals(id))
                .orElseThrow(() -> FdropException.notFound("no such key"));
        if (cert == null || cert.isBlank()) {
            repo.setKeyCert(keyId, null);
            return;
        }
        ssh.checkUserCertificate(cert.strip(), k.publicKey());
        repo.setKeyCert(keyId, cert.strip());
        Audit.log("destination.key.certificate", "user", admin.username(), "destination", id, "key", keyId);
    }

    public void assign(Principal admin, String id, String username) {
        requireAdmin(admin);
        find(id);
        User u = users.findByUsername(username).orElseThrow(() -> FdropException.notFound("no user '" + username
                + "' (they must have logged in to fdrop once)"));
        if (u.kind() == io.fdrop.core.model.Enums.UserKind.APPLICATION) {
            throw FdropException.badRequest("application accounts are never assigned destinations");
        }
        repo.assign(id, u.sub());
        for (Folder f : folders.usingDestination(id)) {
            if (f.ownerSub().equals(u.sub())) {
                records.pauseDestination(f.id(), id, false);
            }
        }
        Audit.log("destination.assign", "user", admin.username(), "destination", id, "assignee", username);
    }

    public void unassign(Principal admin, String id, String username) {
        requireAdmin(admin);
        User u = users.findByUsername(username).orElseThrow(() -> FdropException.notFound("no such user"));
        if (!repo.unassign(id, u.sub())) {
            throw FdropException.notFound("not assigned");
        }
        for (Folder f : folders.usingDestination(id)) {
            if (f.ownerSub().equals(u.sub())) {
                records.pauseDestination(f.id(), id, true);
            }
        }
        Audit.log("destination.unassign", "user", admin.username(), "destination", id, "assignee", username);
    }

    /** The key forward workers log in with: the newest active one. */
    public Optional<ForwardClientKey> usableKey(String destinationId) {
        return repo.keys(destinationId).stream().filter(k -> k.state() == KeyState.ACTIVE)
                .max(Comparator.comparingLong(ForwardClientKey::createdAt));
    }

    public Optional<ForwardDestination> destination(String id) {
        return repo.find(id);
    }

    private ForwardClientKey newKey(String destinationId, KeyState state) {
        SshTools.GeneratedKey g = ssh.generateEd25519("fdrop-" + destinationId);
        ForwardClientKey k = new ForwardClientKey(Hashing.newId("key"), destinationId, g.publicKey(),
                secrets.seal(g.privateKeyOpenSsh()), null, state, System.currentTimeMillis());
        repo.insertKey(k);
        return k;
    }

    private ForwardDestination find(String id) {
        return repo.find(id).orElseThrow(() -> FdropException.notFound("no such destination"));
    }

    private void requireSecrets() {
        if (secrets == null) {
            throw FdropException.unavailable("forward.encryptionKey is not set, so destination keys cannot be stored");
        }
    }

    public static void requireAdmin(Principal p) {
        if (!p.admin() || p.mixedRoles()) {
            throw FdropException.forbidden("admin role required");
        }
    }
}
