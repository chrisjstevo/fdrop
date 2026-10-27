package io.fdrop.core.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The search change feed and the shared text extraction records. */
public final class SearchRepo {

    public enum Op { INDEX, DELETE, MOVE }

    public record Change(long seq, Op op, String storageKey, String toKey, long at) {
    }

    /** One file version's extraction: {@code state} PENDING, OK, UNSUPPORTED, TOO_LARGE, PROTECTED or FAILED. */
    public record Extract(String storageKey, String version, String state, String textKey, String type,
                          String claimedBy, Long claimedUntil, int attempts) {
    }

    private final Db db;

    public SearchRepo(Db db) {
        this.db = db;
    }

    private static Change change(ResultSet rs) throws SQLException {
        return new Change(rs.getLong("seq"), Op.valueOf(rs.getString("op")), rs.getString("storage_key"),
                rs.getString("to_key"), rs.getLong("at"));
    }

    public void append(Op op, String key, String toKey) {
        db.update("INSERT INTO search_change (op, storage_key, to_key, at) VALUES (?, ?, ?, ?)", op, key, toKey,
                System.currentTimeMillis());
    }

    public List<Change> after(long seq, int limit) {
        return db.query("SELECT * FROM search_change WHERE seq > ? ORDER BY seq LIMIT ?", SearchRepo::change, seq,
                limit);
    }

    public long latest() {
        return db.queryLong("SELECT COALESCE(MAX(seq), 0) FROM search_change");
    }

    /** The smallest seq still in the feed, or 0 when it is empty. */
    public long oldest() {
        return db.queryLong("SELECT COALESCE(MIN(seq), 0) FROM search_change");
    }

    /** When the oldest change after {@code seq} was made, if there is one. */
    public Optional<Long> oldestAtAfter(long seq) {
        return db.queryOne("SELECT at FROM search_change WHERE seq > ? ORDER BY seq LIMIT 1", rs -> rs.getLong(1),
                seq);
    }

    public int prune(long before) {
        return db.update("DELETE FROM search_change WHERE at < ?", before);
    }

    // ---- extraction ----

    private static Extract extract(ResultSet rs) throws SQLException {
        return new Extract(rs.getString("storage_key"), rs.getString("version"), rs.getString("state"),
                rs.getString("text_key"), rs.getString("type"), rs.getString("claimed_by"),
                Db.getLong(rs, "claimed_until"), rs.getInt("attempts"));
    }

    public Optional<Extract> extract(String key, String version) {
        return db.queryOne("SELECT * FROM search_extract WHERE storage_key = ? AND version = ?", SearchRepo::extract,
                key, version);
    }

    /** Claims a file version for extraction; false when another node holds a live claim or it is done. */
    public boolean claim(String key, String version, String node, long until, long now) {
        return db.inTransaction(() -> {
            Optional<Extract> e = extract(key, version);
            if (e.isEmpty()) {
                try {
                    db.update("INSERT INTO search_extract (storage_key, version, state, claimed_by, claimed_until,"
                            + " attempts, updated_at) VALUES (?, ?, 'PENDING', ?, ?, 1, ?)", key, version, node, until,
                            now);
                    return true;
                } catch (Db.DbException ex) {
                    if (ex.isUniqueViolation()) {
                        return false;
                    }
                    throw ex;
                }
            }
            return db.update("UPDATE search_extract SET claimed_by = ?, claimed_until = ?, attempts = attempts + 1,"
                    + " updated_at = ? WHERE storage_key = ? AND version = ? AND state = 'PENDING'"
                    + " AND (claimed_until IS NULL OR claimed_until < ?)", node, until, now, key, version, now) > 0;
        });
    }

    public void finish(String key, String version, String state, String textKey, String type) {
        db.update("UPDATE search_extract SET state = ?, text_key = ?, type = ?, claimed_by = NULL,"
                + " claimed_until = NULL, updated_at = ? WHERE storage_key = ? AND version = ?", state, textKey, type,
                System.currentTimeMillis(), key, version);
    }

    /** Extraction records of a file or everything under a folder, to delete with it. */
    public List<Extract> under(String key) {
        return db.query("SELECT * FROM search_extract WHERE storage_key = ? OR storage_key LIKE ?",
                SearchRepo::extract, key, key.replace("%", "\\%").replace("_", "\\_") + "/%");
    }

    public void deleteExtract(String key, String version) {
        db.update("DELETE FROM search_extract WHERE storage_key = ? AND version = ?", key, version);
    }

    public Map<String, Long> extractCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] r : db.query("SELECT state, COUNT(*) FROM search_extract GROUP BY state ORDER BY state",
                rs -> new Object[]{rs.getString(1), rs.getLong(2)})) {
            out.put(((String) r[0]).toLowerCase(java.util.Locale.ROOT), (Long) r[1]);
        }
        return out;
    }
}
