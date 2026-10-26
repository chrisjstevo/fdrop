package io.fdrop.core.db;

import io.fdrop.core.model.Enums.ExpirySource;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Records.FileExpiry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** File expiries, folder and home expiry settings, and pending SFTP control values. */
public final class ExpiryRepo {

    /** A folder's or home's settings, in canonical expiry text; null when not set. */
    public record Settings(String defaultExpiry, String maxExpiry) {
    }

    private final Db db;

    public ExpiryRepo(Db db) {
        this.db = db;
    }

    private static FileExpiry row(ResultSet rs) throws SQLException {
        String via = rs.getString("set_via");
        return new FileExpiry(rs.getString("id"), rs.getString("storage_key"), rs.getString("parent_key"),
                rs.getString("user_sub"), rs.getString("folder_id"), rs.getLong("expires_at"),
                rs.getString("expires_in"), ExpirySource.valueOf(rs.getString("source")), rs.getString("set_by_sub"),
                via == null ? null : Via.valueOf(via), rs.getLong("set_at"), rs.getString("upload_record_id"),
                rs.getInt("attempts"), rs.getString("last_error"), rs.getLong("next_attempt_at"));
    }

    public static String parentOf(String key) {
        int i = key.lastIndexOf('/');
        return i < 0 ? "" : key.substring(0, i);
    }

    public Optional<FileExpiry> find(String key) {
        return db.queryOne("SELECT * FROM file_expiry WHERE storage_key = ?", ExpiryRepo::row, key);
    }

    /** Expiries of the files directly in a folder, by storage key. */
    public Map<String, FileExpiry> children(String parentKey) {
        Map<String, FileExpiry> out = new HashMap<>();
        for (FileExpiry e : db.query("SELECT * FROM file_expiry WHERE parent_key = ?", ExpiryRepo::row, parentKey)) {
            out.put(e.storageKey(), e);
        }
        return out;
    }

    /** Sets a file's expiry, replacing any it had. */
    public void put(FileExpiry e) {
        db.inTransaction(() -> {
            db.update("DELETE FROM file_expiry WHERE storage_key = ?", e.storageKey());
            db.update("INSERT INTO file_expiry (id, storage_key, parent_key, user_sub, folder_id, expires_at,"
                            + " expires_in, source, set_by_sub, set_via, set_at, upload_record_id, attempts,"
                            + " next_attempt_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)", e.id(),
                    e.storageKey(), parentOf(e.storageKey()), e.userSub(), e.folderId(), e.expiresAt(),
                    e.expiresIn(), e.source(), e.setBySub(), e.setVia(), e.setAt(), e.uploadRecordId());
            return null;
        });
    }

    public boolean delete(String key) {
        return db.update("DELETE FROM file_expiry WHERE storage_key = ?", key) > 0;
    }

    /** Removes the expiry of a file, or of everything beneath a folder. */
    public int deleteUnder(String key) {
        return db.update("DELETE FROM file_expiry WHERE storage_key = ? OR storage_key LIKE ?", key,
                escapeLike(key) + "/%");
    }

    /** Expiries of a file, or of everything beneath a folder, moved from one key to another. */
    public List<FileExpiry> move(String fromKey, String toKey, String toFolderId, String toUserSub) {
        return db.inTransaction(() -> {
            List<FileExpiry> rows = db.query("SELECT * FROM file_expiry WHERE storage_key = ? OR storage_key LIKE ?",
                    ExpiryRepo::row, fromKey, escapeLike(fromKey) + "/%");
            for (FileExpiry r : rows) {
                String newKey = toKey + r.storageKey().substring(fromKey.length());
                db.update("UPDATE file_expiry SET storage_key = ?, parent_key = ?, folder_id = ?, user_sub = ?"
                        + " WHERE id = ?", newKey, parentOf(newKey), toFolderId, toUserSub, r.id());
            }
            return db.query("SELECT * FROM file_expiry WHERE storage_key = ? OR storage_key LIKE ?", ExpiryRepo::row,
                    toKey, escapeLike(toKey) + "/%");
        });
    }

    /** Rows past their expiry that nobody holds a live claim on, oldest first. */
    public List<FileExpiry> due(long now, int limit) {
        return db.query("SELECT * FROM file_expiry WHERE expires_at <= ? AND next_attempt_at <= ?"
                + " AND (claimed_until IS NULL OR claimed_until < ?) ORDER BY expires_at LIMIT ?", ExpiryRepo::row,
                now, now, now, limit);
    }

    /** Claims one row for deletion; false if another node holds it. */
    public boolean claim(String id, String node, long until, long now) {
        return db.update("UPDATE file_expiry SET claimed_by = ?, claimed_until = ? WHERE id = ?"
                + " AND (claimed_until IS NULL OR claimed_until < ?)", node, until, id, now) > 0;
    }

    /** Deletes a row this node claimed; false if the claim was lost or the row changed meanwhile. */
    public boolean deleteClaimed(String id, String node) {
        return db.update("DELETE FROM file_expiry WHERE id = ? AND claimed_by = ?", id, node) > 0;
    }

    public void release(String id, String node) {
        db.update("UPDATE file_expiry SET claimed_by = NULL, claimed_until = NULL WHERE id = ? AND claimed_by = ?",
                id, node);
    }

    public void fail(String id, String node, String error, long nextAttemptAt) {
        db.update("UPDATE file_expiry SET attempts = attempts + 1, last_error = ?, next_attempt_at = ?,"
                        + " claimed_by = NULL, claimed_until = NULL WHERE id = ? AND claimed_by = ?",
                error == null ? null : error.length() > 900 ? error.substring(0, 900) : error, nextAttemptAt, id, node);
    }

    public long count() {
        return db.queryLong("SELECT COUNT(*) FROM file_expiry");
    }

    public long overdue(long before) {
        return db.queryLong("SELECT COUNT(*) FROM file_expiry WHERE expires_at < ?", before);
    }

    public List<FileExpiry> inFolder(String folderId) {
        return db.query("SELECT * FROM file_expiry WHERE folder_id = ?", ExpiryRepo::row, folderId);
    }

    // ---- folder and home settings ----

    public Settings folderSettings(String folderId) {
        return db.queryOne("SELECT default_expiry, max_expiry FROM folder WHERE id = ?",
                rs -> new Settings(rs.getString(1), rs.getString(2)), folderId).orElse(new Settings(null, null));
    }

    public void setFolderSettings(String folderId, Settings s) {
        db.update("UPDATE folder SET default_expiry = ?, max_expiry = ? WHERE id = ?", s.defaultExpiry(),
                s.maxExpiry(), folderId);
    }

    public String homeDefault(String sub) {
        List<String> rows = db.query("SELECT home_default_expiry FROM users WHERE sub = ?", rs -> rs.getString(1), sub);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void setHomeDefault(String sub, String expiry) {
        db.update("UPDATE users SET home_default_expiry = ? WHERE sub = ?", expiry, sub);
    }

    // ---- pending SFTP control values ----

    public void putPending(String sub, String key, String expiresIn, long now) {
        db.inTransaction(() -> {
            db.update("DELETE FROM sftp_pending_expiry WHERE user_sub = ? AND storage_key = ?", sub, key);
            db.update("INSERT INTO sftp_pending_expiry (user_sub, storage_key, expires_in, created_at)"
                    + " VALUES (?, ?, ?, ?)", sub, key, expiresIn, now);
            return null;
        });
    }

    /** A pending value written at or after {@code since}, if any. */
    public Optional<String> pending(String sub, String key, long since) {
        return db.queryOne("SELECT expires_in FROM sftp_pending_expiry WHERE user_sub = ? AND storage_key = ?"
                + " AND created_at >= ?", rs -> rs.getString(1), sub, key, since);
    }

    public boolean deletePending(String sub, String key) {
        return db.update("DELETE FROM sftp_pending_expiry WHERE user_sub = ? AND storage_key = ?", sub, key) > 0;
    }

    public int deletePendingBefore(long cutoff) {
        return db.update("DELETE FROM sftp_pending_expiry WHERE created_at < ?", cutoff);
    }

    private static String escapeLike(String s) {
        return s.replace("%", "\\%").replace("_", "\\_");
    }
}
