package io.fdrop.core.db;

import io.fdrop.core.model.Enums.UserKind;
import io.fdrop.core.model.Records.SshKey;
import io.fdrop.core.model.Records.User;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Users and their SSH keys. */
public final class UserRepo {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserRepo.class);

    private final Db db;

    public UserRepo(Db db) {
        this.db = db;
    }

    private static User user(ResultSet rs) throws SQLException {
        return new User(rs.getString("sub"), rs.getString("username"), rs.getString("email"),
                rs.getLong("quota_bytes"), rs.getLong("used_bytes"), rs.getLong("created_at"),
                Db.getLong(rs, "last_login_at"), UserKind.valueOf(rs.getString("kind")));
    }

    private static SshKey key(ResultSet rs) throws SQLException {
        return new SshKey(rs.getString("id"), rs.getString("user_sub"), rs.getString("name"),
                rs.getString("key_type"), rs.getString("public_key"), rs.getString("fingerprint"),
                rs.getLong("created_at"), Db.getLong(rs, "last_used_at"));
    }

    public Optional<User> find(String sub) {
        return db.queryOne("SELECT * FROM users WHERE sub = ?", UserRepo::user, sub);
    }

    public Optional<User> findByUsername(String username) {
        return db.queryOne("SELECT * FROM users WHERE username = ?", UserRepo::user, username);
    }

    /**
     * Creates the row on first sight and keeps username and email in step with Keycloak. The kind and quota are
     * set only when the row is created. Usernames are unique in Keycloak, so another row still holding this
     * username belongs to an account deleted there (or, in development, a realm imported again with new ids): it is
     * renamed {@code <username>~<first 8 characters of its sub>}, so that lookups by username, such as adding a
     * member or assigning a destination, find the account signing in now.
     */
    public User upsert(String sub, String username, String email, long defaultQuota, UserKind kind) {
        return db.inTransaction(() -> {
            Optional<User> existing = find(sub);
            long now = System.currentTimeMillis();
            int renamed = db.update("UPDATE users SET username = username || '~' || SUBSTRING(sub, 1, 8)"
                    + " WHERE username = ? AND sub <> ?", username, sub);
            if (renamed > 0) {
                log.warn("username {} now belongs to Keycloak user {}; the older fdrop account with that name was"
                        + " renamed", username, sub);
            }
            if (existing.isEmpty()) {
                db.update("INSERT INTO users (sub, username, email, quota_bytes, used_bytes, created_at, last_login_at,"
                        + " kind) VALUES (?, ?, ?, ?, 0, ?, ?, ?)", sub, username, email, defaultQuota, now, now, kind);
            } else {
                db.update("UPDATE users SET username = ?, email = ?, last_login_at = ? WHERE sub = ?",
                        username, email, now, sub);
            }
            return find(sub).orElseThrow();
        });
    }

    public List<User> search(String prefix, int limit) {
        String p = prefix.toLowerCase().replace("%", "") + "%";
        return db.query("SELECT * FROM users WHERE LOWER(username) LIKE ? ORDER BY username LIMIT ?",
                UserRepo::user, p, limit);
    }

    public long count() {
        return db.queryLong("SELECT COUNT(*) FROM users");
    }

    public long count(UserKind kind) {
        return db.queryLong("SELECT COUNT(*) FROM users WHERE kind = ?", kind);
    }

    public long totalUsed() {
        return db.queryLong("SELECT COALESCE(SUM(used_bytes), 0) FROM users");
    }

    /** Adds {@code delta} (may be negative) to the user's usage, never going below zero. */
    public void addUsage(String sub, long delta) {
        db.update("UPDATE users SET used_bytes = CASE WHEN used_bytes + ? < 0 THEN 0 ELSE used_bytes + ? END"
                + " WHERE sub = ?", delta, delta, sub);
    }

    public void setUsage(String sub, long used) {
        db.update("UPDATE users SET used_bytes = ? WHERE sub = ?", used, sub);
    }

    public void delete(String sub) {
        db.update("DELETE FROM users WHERE sub = ?", sub);
    }

    public List<SshKey> keys(String sub) {
        return db.query("SELECT * FROM ssh_keys WHERE user_sub = ? ORDER BY created_at", UserRepo::key, sub);
    }

    public Optional<SshKey> keyByFingerprint(String username, String fingerprint) {
        return db.queryOne("SELECT k.* FROM ssh_keys k JOIN users u ON u.sub = k.user_sub"
                + " WHERE u.username = ? AND k.fingerprint = ?", UserRepo::key, username, fingerprint);
    }

    public void addKey(SshKey k) {
        db.update("INSERT INTO ssh_keys (id, user_sub, name, key_type, public_key, fingerprint, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)", k.id(), k.userSub(), k.name(), k.keyType(), k.publicKey(),
                k.fingerprint(), k.createdAt());
    }

    public boolean deleteKey(String sub, String id) {
        return db.update("DELETE FROM ssh_keys WHERE user_sub = ? AND id = ?", sub, id) > 0;
    }

    public void touchKey(String id) {
        db.update("UPDATE ssh_keys SET last_used_at = ? WHERE id = ?", System.currentTimeMillis(), id);
    }
}
