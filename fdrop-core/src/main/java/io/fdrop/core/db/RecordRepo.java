package io.fdrop.core.db;

import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.RecordStatus;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Enums.WentTo;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.model.Records.UploadRecord;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The My uploads history and forward jobs. */
public final class RecordRepo {

    private final Db db;

    public RecordRepo(Db db) {
        this.db = db;
    }

    private static UploadRecord record(ResultSet rs) throws SQLException {
        return new UploadRecord(rs.getString("id"), rs.getString("user_sub"), rs.getString("file_name"),
                rs.getLong("size"), WentTo.valueOf(rs.getString("went_to")), rs.getString("folder_id"),
                rs.getString("path_at_upload"), rs.getString("path_now"), rs.getString("storage_key"),
                RecordStatus.valueOf(rs.getString("status")), Via.valueOf(rs.getString("via")),
                rs.getString("client_ip"), rs.getString("start_ip"), rs.getLong("completed_at"),
                Db.getLong(rs, "expires_at"));
    }

    private static ForwardJob job(ResultSet rs) throws SQLException {
        return new ForwardJob(rs.getString("id"), rs.getString("folder_id"), rs.getString("destination_id"),
                rs.getString("upload_record_id"), rs.getString("storage_key"), rs.getString("file_name"),
                rs.getLong("size"), JobState.valueOf(rs.getString("state")), rs.getInt("attempts"),
                rs.getString("last_error"), rs.getLong("next_attempt_at"), Db.getLong(rs, "first_failed_at"),
                rs.getLong("created_at"), rs.getLong("updated_at"), Db.getLong(rs, "sent_at"));
    }

    // ---- upload records ----

    public void insert(UploadRecord r) {
        db.update("INSERT INTO upload_record (id, user_sub, file_name, size, went_to, folder_id, path_at_upload,"
                        + " path_now, storage_key, status, via, client_ip, start_ip, completed_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", r.id(), r.userSub(), r.fileName(),
                r.size(), r.wentTo(), r.folderId(), r.pathAtUpload(), r.currentPath(), r.storageKey(), r.status(),
                r.via(), r.clientIp(), r.startIp(), r.completedAt(), r.expiresAt());
    }

    public Optional<UploadRecord> find(String id) {
        return db.queryOne("SELECT * FROM upload_record WHERE id = ?", RecordRepo::record, id);
    }

    /** Filter for the My uploads page. All fields optional. */
    public record Filter(WentTo wentTo, boolean foldersOnly, String q, Long from, Long to, String sort,
                         String cursor, int limit) {
    }

    public record Page(List<UploadRecord> rows, long totalCount, long totalBytes, String nextCursor) {
    }

    public Page page(String sub, Filter f) {
        StringBuilder where = new StringBuilder(" WHERE user_sub = ?");
        List<Object> args = new ArrayList<>();
        args.add(sub);
        if (f.wentTo() == WentTo.FORWARD) {
            where.append(" AND went_to = 'FORWARD'");
        } else if (f.foldersOnly()) {
            where.append(" AND went_to <> 'FORWARD'");
        }
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().trim().toLowerCase().replace("%", "");
            where.append(" AND (LOWER(file_name) LIKE ? OR client_ip LIKE ? OR start_ip LIKE ?)");
            args.add("%" + q + "%");
            args.add(q + "%");
            args.add(q + "%");
        }
        if (f.from() != null) {
            where.append(" AND completed_at >= ?");
            args.add(f.from());
        }
        if (f.to() != null) {
            where.append(" AND completed_at < ?");
            args.add(f.to());
        }
        Object[] baseArgs = args.toArray();
        long count = db.queryLong("SELECT COUNT(*) FROM upload_record" + where, baseArgs);
        long bytes = db.queryLong("SELECT COALESCE(SUM(size), 0) FROM upload_record" + where, baseArgs);

        String order = switch (f.sort() == null ? "date_desc" : f.sort()) {
            case "date_asc" -> "completed_at ASC, id ASC";
            case "size_desc" -> "size DESC, id DESC";
            case "size_asc" -> "size ASC, id ASC";
            case "name_asc" -> "LOWER(file_name) ASC, id ASC";
            case "name_desc" -> "LOWER(file_name) DESC, id DESC";
            default -> "completed_at DESC, id DESC";
        };
        // Keyset paging only for the default order, so new uploads never shift pages; other orders use offsets.
        int offset = 0;
        StringBuilder pageWhere = new StringBuilder(where);
        List<Object> pageArgs = new ArrayList<>(args);
        if (f.cursor() != null && !f.cursor().isBlank()) {
            if (order.startsWith("completed_at DESC")) {
                String[] parts = f.cursor().split(":", 2);
                pageWhere.append(" AND (completed_at < ? OR (completed_at = ? AND id < ?))");
                long t = Long.parseLong(parts[0]);
                pageArgs.add(t);
                pageArgs.add(t);
                pageArgs.add(parts[1]);
            } else {
                offset = Integer.parseInt(f.cursor());
            }
        }
        pageArgs.add(f.limit() + 1);
        pageArgs.add(offset);
        List<UploadRecord> rows = db.query("SELECT * FROM upload_record" + pageWhere + " ORDER BY " + order
                + " LIMIT ? OFFSET ?", RecordRepo::record, pageArgs.toArray());
        String next = null;
        if (rows.size() > f.limit()) {
            rows = new ArrayList<>(rows.subList(0, f.limit()));
            UploadRecord last = rows.get(rows.size() - 1);
            next = order.startsWith("completed_at DESC") ? last.completedAt() + ":" + last.id()
                    : String.valueOf(offset + f.limit());
        }
        return new Page(rows, count, bytes, next);
    }

    public void setStatus(String id, RecordStatus status) {
        db.update("UPDATE upload_record SET status = ? WHERE id = ?", status, id);
    }

    /** A file at {@code key} (or anything beneath it, for a folder) was deleted. */
    public void markDeleted(String key) {
        db.update("UPDATE upload_record SET status = 'DELETED', storage_key = NULL"
                        + " WHERE (storage_key = ? OR storage_key LIKE ?) AND status IN ('STORED', 'MOVED')",
                key, escapeLike(key) + "/%");
    }

    /** A file or folder moved from {@code fromKey} to {@code toKey}; virtual paths move with it. */
    public void markMoved(String fromKey, String toKey, String fromPath, String toPath) {
        List<UploadRecord> affected = db.query("SELECT * FROM upload_record WHERE (storage_key = ? OR storage_key"
                        + " LIKE ?) AND status IN ('STORED', 'MOVED')", RecordRepo::record, fromKey,
                escapeLike(fromKey) + "/%");
        for (UploadRecord r : affected) {
            String newKey = toKey + r.storageKey().substring(fromKey.length());
            String cur = r.currentPath() == null ? fromPath : r.currentPath();
            String newPath = cur.startsWith(fromPath) ? toPath + cur.substring(fromPath.length()) : toPath;
            db.update("UPDATE upload_record SET storage_key = ?, path_now = ?, status = 'MOVED' WHERE id = ?",
                    newKey, newPath, r.id());
        }
    }

    /** The expiry recorded for an upload changed (null when cleared). */
    public void setExpiresAt(String id, Long expiresAt) {
        db.update("UPDATE upload_record SET expires_at = ? WHERE id = ?", expiresAt, id);
    }

    /** The file at {@code key} expired and was deleted. */
    public void markExpired(String key) {
        db.update("UPDATE upload_record SET status = 'EXPIRED', storage_key = NULL WHERE storage_key = ?"
                + " AND status IN ('STORED', 'MOVED')", key);
    }

    /** The newest record still pointing at a stored file, to keep its expiry in step. */
    public Optional<UploadRecord> currentFor(String key) {
        return db.queryOne("SELECT * FROM upload_record WHERE storage_key = ? AND status IN ('STORED', 'MOVED',"
                + " 'SENT') ORDER BY completed_at DESC LIMIT 1", RecordRepo::record, key);
    }

    public int deleteOlderThan(long cutoff) {
        return db.update("DELETE FROM upload_record WHERE completed_at < ?", cutoff);
    }

    public void deleteForUser(String sub) {
        db.update("DELETE FROM upload_record WHERE user_sub = ?", sub);
    }

    private static String escapeLike(String s) {
        return s.replace("%", "\\%").replace("_", "\\_");
    }

    // ---- forward jobs ----

    public void insertJob(ForwardJob j) {
        db.update("INSERT INTO forward_job (id, folder_id, destination_id, upload_record_id, storage_key, file_name,"
                        + " size, state, attempts, last_error, next_attempt_at, first_failed_at, created_at, updated_at,"
                        + " sent_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", j.id(), j.folderId(),
                j.destinationId(), j.uploadRecordId(), j.storageKey(), j.fileName(), j.size(), j.state(),
                j.attempts(), j.lastError(), j.nextAttemptAt(), j.firstFailedAt(), j.createdAt(), j.updatedAt(),
                j.sentAt());
    }

    public Optional<ForwardJob> job(String id) {
        return db.queryOne("SELECT * FROM forward_job WHERE id = ?", RecordRepo::job, id);
    }

    public List<ForwardJob> jobsForFolder(String folderId, int limit) {
        return db.query("SELECT * FROM forward_job WHERE folder_id = ? ORDER BY created_at DESC LIMIT ?",
                RecordRepo::job, folderId, limit);
    }

    /** Due jobs in arrival order. */
    public List<ForwardJob> dueJobs(long now, int limit) {
        return db.query("SELECT * FROM forward_job WHERE state IN ('QUEUED', 'RETRY_WAIT') AND next_attempt_at <= ?"
                + " ORDER BY created_at LIMIT ?", RecordRepo::job, now, limit);
    }

    /** Claims a job for sending; false if another worker got it first. */
    public boolean claim(String id, JobState from) {
        return db.update("UPDATE forward_job SET state = 'SENDING', attempts = attempts + 1, updated_at = ?"
                + " WHERE id = ? AND state = ?", System.currentTimeMillis(), id, from) > 0;
    }

    public void updateJob(String id, JobState state, String error, long nextAttemptAt, Long firstFailedAt,
                          Long sentAt) {
        db.update("UPDATE forward_job SET state = ?, last_error = ?, next_attempt_at = ?, first_failed_at = ?,"
                        + " sent_at = ?, updated_at = ? WHERE id = ?", state, error, nextAttemptAt, firstFailedAt,
                sentAt, System.currentTimeMillis(), id);
        db.update("UPDATE upload_record SET status = ? WHERE id = (SELECT upload_record_id FROM forward_job"
                + " WHERE id = ?)", state, id);
    }

    /** Pauses or resumes every unfinished job in a folder (assignment removed / restored). */
    public void pauseFolder(String folderId, boolean pause) {
        if (pause) {
            db.update("UPDATE upload_record SET status = 'PAUSED' WHERE id IN (SELECT upload_record_id FROM"
                    + " forward_job WHERE folder_id = ? AND state IN ('QUEUED', 'RETRY_WAIT'))", folderId);
            db.update("UPDATE forward_job SET state = 'PAUSED', updated_at = ? WHERE folder_id = ?"
                    + " AND state IN ('QUEUED', 'RETRY_WAIT')", System.currentTimeMillis(), folderId);
        } else {
            db.update("UPDATE upload_record SET status = 'QUEUED' WHERE id IN (SELECT upload_record_id FROM"
                    + " forward_job WHERE folder_id = ? AND state = 'PAUSED')", folderId);
            db.update("UPDATE forward_job SET state = 'QUEUED', next_attempt_at = ?, updated_at = ?"
                    + " WHERE folder_id = ? AND state = 'PAUSED'", 0L, System.currentTimeMillis(), folderId);
        }
    }

    public long countJobs(JobState state, String destinationId) {
        return db.queryLong("SELECT COUNT(*) FROM forward_job WHERE state = ? AND destination_id = ?", state,
                destinationId);
    }

    /** Jobs that were sent but whose file is kept: delete files older than their folder's keep days. */
    public List<ForwardJob> sentBefore(String folderId, long cutoff) {
        return db.query("SELECT * FROM forward_job WHERE folder_id = ? AND state = 'SENT' AND sent_at < ?",
                RecordRepo::job, folderId, cutoff);
    }

    /** Recovers jobs left SENDING by a crash. */
    public int resetStuckSending(long before) {
        return db.update("UPDATE forward_job SET state = 'RETRY_WAIT', next_attempt_at = 0 WHERE state = 'SENDING'"
                + " AND updated_at < ?", before);
    }
}
