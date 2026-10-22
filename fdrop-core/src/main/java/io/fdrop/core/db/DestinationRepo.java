package io.fdrop.core.db;

import io.fdrop.core.model.Enums.KeyState;
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.model.Records.ForwardClientKey;
import io.fdrop.core.model.Records.ForwardDestination;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Admin-defined forward destinations, their client keys, and who they are assigned to. */
public final class DestinationRepo {

    private final Db db;

    public DestinationRepo(Db db) {
        this.db = db;
    }

    private static ForwardDestination dest(ResultSet rs) throws SQLException {
        return new ForwardDestination(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("host"), rs.getInt("port"), rs.getString("remote_dir"), rs.getString("username"),
                ServerAuth.valueOf(rs.getString("server_auth")), rs.getString("host_key_sha256"),
                rs.getString("host_ca_pubkey"), rs.getBoolean("overwrite"), rs.getBoolean("enabled"),
                rs.getString("created_by"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static ForwardClientKey key(ResultSet rs) throws SQLException {
        return new ForwardClientKey(rs.getString("id"), rs.getString("destination_id"), rs.getString("public_key"),
                rs.getBytes("private_key_enc"), rs.getString("user_cert"), KeyState.valueOf(rs.getString("state")),
                rs.getLong("created_at"));
    }

    public List<ForwardDestination> all() {
        return db.query("SELECT * FROM forward_destination ORDER BY name", DestinationRepo::dest);
    }

    public Optional<ForwardDestination> find(String id) {
        return db.queryOne("SELECT * FROM forward_destination WHERE id = ?", DestinationRepo::dest, id);
    }

    public List<ForwardDestination> assignedTo(String sub) {
        return db.query("SELECT d.* FROM forward_destination d JOIN destination_assignment a"
                + " ON a.destination_id = d.id WHERE a.user_sub = ? ORDER BY d.name", DestinationRepo::dest, sub);
    }

    public boolean isAssigned(String destinationId, String sub) {
        return db.queryLong("SELECT COUNT(*) FROM destination_assignment WHERE destination_id = ? AND user_sub = ?",
                destinationId, sub) > 0;
    }

    public List<String> assignees(String destinationId) {
        return db.query("SELECT user_sub FROM destination_assignment WHERE destination_id = ?",
                rs -> rs.getString(1), destinationId);
    }

    public void insert(ForwardDestination d) {
        db.update("INSERT INTO forward_destination (id, name, description, host, port, remote_dir, username,"
                        + " server_auth, host_key_sha256, host_ca_pubkey, overwrite, enabled, created_by, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", d.id(), d.name(),
                d.description(), d.host(), d.port(), d.remoteDir(), d.username(), d.serverAuth(),
                d.hostKeySha256(), d.hostCaPublicKey(), d.overwrite(), d.enabled(), d.createdBy(), d.createdAt(),
                d.updatedAt());
    }

    public void update(ForwardDestination d) {
        db.update("UPDATE forward_destination SET name = ?, description = ?, host = ?, port = ?, remote_dir = ?,"
                        + " username = ?, server_auth = ?, host_key_sha256 = ?, host_ca_pubkey = ?, overwrite = ?,"
                        + " enabled = ?, updated_at = ? WHERE id = ?", d.name(), d.description(), d.host(), d.port(),
                d.remoteDir(), d.username(), d.serverAuth(), d.hostKeySha256(), d.hostCaPublicKey(), d.overwrite(),
                d.enabled(), System.currentTimeMillis(), d.id());
    }

    public void delete(String id) {
        db.update("DELETE FROM forward_destination WHERE id = ?", id);
    }

    public void assign(String destinationId, String sub) {
        if (!isAssigned(destinationId, sub)) {
            db.update("INSERT INTO destination_assignment (destination_id, user_sub) VALUES (?, ?)", destinationId, sub);
        }
    }

    public boolean unassign(String destinationId, String sub) {
        return db.update("DELETE FROM destination_assignment WHERE destination_id = ? AND user_sub = ?",
                destinationId, sub) > 0;
    }

    public void removeAssignments(String sub) {
        db.update("DELETE FROM destination_assignment WHERE user_sub = ?", sub);
    }

    public List<ForwardClientKey> keys(String destinationId) {
        return db.query("SELECT * FROM forward_client_key WHERE destination_id = ? ORDER BY created_at",
                DestinationRepo::key, destinationId);
    }

    public Optional<ForwardClientKey> key(String keyId) {
        return db.queryOne("SELECT * FROM forward_client_key WHERE id = ?", DestinationRepo::key, keyId);
    }

    public void insertKey(ForwardClientKey k) {
        db.update("INSERT INTO forward_client_key (id, destination_id, public_key, private_key_enc, user_cert, state,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", k.id(), k.destinationId(), k.publicKey(),
                k.privateKeyEnc(), k.userCert(), k.state(), k.createdAt());
    }

    public void setKeyState(String keyId, KeyState state) {
        db.update("UPDATE forward_client_key SET state = ? WHERE id = ?", state, keyId);
    }

    public void setKeyCert(String keyId, String cert) {
        db.update("UPDATE forward_client_key SET user_cert = ? WHERE id = ?", cert, keyId);
    }
}
