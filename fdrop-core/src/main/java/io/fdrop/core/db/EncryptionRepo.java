package io.fdrop.core.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Re-encryption jobs, their items, and the encryption inventory. */
public final class EncryptionRepo {

    /** A re-encryption job. {@code scope} is KEY (objects under {@code fromKeyId}) or PLAINTEXT. */
    public record Job(String id, String scope, String fromKeyId, String state, long objectsTotal, long objectsDone,
                      long objectsFailed, long bytesDone, String startedBy, long startedAt, Long finishedAt) {
    }

    public record Counts(long objects, long bytes, long countedAt) {
    }

    private final Db db;

    public EncryptionRepo(Db db) {
        this.db = db;
    }

    private static Job job(ResultSet rs) throws SQLException {
        return new Job(rs.getString("id"), rs.getString("scope"), rs.getString("from_key_id"), rs.getString("state"),
                rs.getLong("objects_total"), rs.getLong("objects_done"), rs.getLong("objects_failed"),
                rs.getLong("bytes_done"), rs.getString("started_by"), rs.getLong("started_at"),
                Db.getLong(rs, "finished_at"));
    }

    public void insertJob(Job j) {
        db.update("INSERT INTO reencrypt_job (id, scope, from_key_id, state, objects_total, objects_done,"
                        + " objects_failed, bytes_done, started_by, started_at) VALUES (?, ?, ?, ?, 0, 0, 0, 0, ?, ?)",
                j.id(), j.scope(), j.fromKeyId(), j.state(), j.startedBy(), j.startedAt());
    }

    /** The job that is scanning or running, if any. */
    public Optional<Job> active() {
        return db.queryOne("SELECT * FROM reencrypt_job WHERE state IN ('SCANNING', 'RUNNING') ORDER BY started_at"
                + " DESC LIMIT 1", EncryptionRepo::job);
    }

    public Optional<Job> latest() {
        return db.queryOne("SELECT * FROM reencrypt_job ORDER BY started_at DESC LIMIT 1", EncryptionRepo::job);
    }

    public Optional<Job> find(String id) {
        return db.queryOne("SELECT * FROM reencrypt_job WHERE id = ?", EncryptionRepo::job, id);
    }

    public boolean setState(String id, String from, String to, Long finishedAt) {
        return db.update("UPDATE reencrypt_job SET state = ?, finished_at = ? WHERE id = ? AND state = ?", to,
                finishedAt, id, from) > 0;
    }

    public void addItem(String jobId, String key) {
        db.update("INSERT INTO reencrypt_item (job_id, storage_key, state) VALUES (?, ?, 'PENDING')", jobId, key);
        db.update("UPDATE reencrypt_job SET objects_total = objects_total + 1 WHERE id = ?", jobId);
    }

    /** Pending items nobody holds a live claim on. */
    public List<String> claimable(String jobId, long now, int limit) {
        return db.query("SELECT storage_key FROM reencrypt_item WHERE job_id = ? AND state = 'PENDING'"
                + " AND (claimed_until IS NULL OR claimed_until < ?) LIMIT ?", rs -> rs.getString(1), jobId, now, limit);
    }

    public boolean claim(String jobId, String key, String node, long until, long now) {
        return db.update("UPDATE reencrypt_item SET claimed_by = ?, claimed_until = ? WHERE job_id = ?"
                + " AND storage_key = ? AND state = 'PENDING' AND (claimed_until IS NULL OR claimed_until < ?)",
                node, until, jobId, key, now) > 0;
    }

    public void finishItem(String jobId, String key, boolean ok, long bytes, String error) {
        db.inTransaction(() -> {
            db.update("UPDATE reencrypt_item SET state = ?, error = ?, claimed_by = NULL, claimed_until = NULL"
                    + " WHERE job_id = ? AND storage_key = ?", ok ? "DONE" : "FAILED", error, jobId, key);
            db.update(ok ? "UPDATE reencrypt_job SET objects_done = objects_done + 1, bytes_done = bytes_done + ?"
                    + " WHERE id = ?" : "UPDATE reencrypt_job SET objects_failed = objects_failed + 1,"
                    + " bytes_done = bytes_done + ? WHERE id = ?", bytes, jobId);
            return null;
        });
    }

    /** Items not yet done or failed: pending, claimed or not. */
    public long open(String jobId) {
        return db.queryLong("SELECT COUNT(*) FROM reencrypt_item WHERE job_id = ? AND state = 'PENDING'", jobId);
    }

    public Map<String, Long> itemStates(String jobId) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : List.of("PENDING", "DONE", "FAILED")) {
            out.put(s.toLowerCase(java.util.Locale.ROOT), db.queryLong("SELECT COUNT(*) FROM reencrypt_item"
                    + " WHERE job_id = ? AND state = ?", jobId, s));
        }
        return out;
    }

    // ---- inventory ----

    public void replaceInventory(Map<String, long[]> byKeyId, long now) {
        db.inTransaction(() -> {
            db.update("DELETE FROM encryption_inventory");
            for (Map.Entry<String, long[]> e : byKeyId.entrySet()) {
                db.update("INSERT INTO encryption_inventory (key_id, objects, bytes, counted_at) VALUES (?, ?, ?, ?)",
                        e.getKey(), e.getValue()[0], e.getValue()[1], now);
            }
            return null;
        });
    }

    public Map<String, Counts> inventory() {
        Map<String, Counts> out = new LinkedHashMap<>();
        for (Object[] r : db.query("SELECT key_id, objects, bytes, counted_at FROM encryption_inventory ORDER BY"
                + " key_id", rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)})) {
            out.put((String) r[0], new Counts((Long) r[1], (Long) r[2], (Long) r[3]));
        }
        return out;
    }
}
