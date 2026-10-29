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
                rs.getLong("created_at"), rs.getLong("updated_at"), Db.getLong(rs, "sent_at"),
                rs.getString("cancelled_by"), rs.getString("cancel_reason"));
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

    /** Upload record statuses that follow forward jobs; any other status (deleted, expired) is left alone. */
    private static final String FORWARD_STATUSES = "('QUEUED', 'SENDING', 'RETRY_WAIT', 'PAUSED', 'FAILED', 'SENT',"
            + " 'CANCELLED')";

    public void insertJob(ForwardJob j) {
        db.update("INSERT INTO forward_job (id, folder_id, destination_id, upload_record_id, storage_key, file_name,"
                        + " size, state, attempts, last_error, next_attempt_at, first_failed_at, created_at, updated_at,"
                        + " sent_at, cancelled_by, cancel_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?)", j.id(), j.folderId(), j.destinationId(), j.uploadRecordId(), j.storageKey(),
                j.fileName(), j.size(), j.state(), j.attempts(), j.lastError(), j.nextAttemptAt(), j.firstFailedAt(),
                j.createdAt(), j.updatedAt(), j.sentAt(), j.cancelledBy(), j.cancelReason());
    }

    public Optional<ForwardJob> job(String id) {
        return db.queryOne("SELECT * FROM forward_job WHERE id = ?", RecordRepo::job, id);
    }

    public List<ForwardJob> jobsForFolder(String folderId, int limit) {
        return db.query("SELECT * FROM forward_job WHERE folder_id = ? ORDER BY created_at DESC, destination_id"
                + " LIMIT ?", RecordRepo::job, folderId, limit);
    }

    /** Files a folder has sent to one destination, newest first: what its destination tab marks "Sent by fdrop". */
    public List<ForwardJob> sentTo(String folderId, String destinationId, int limit) {
        return db.query("SELECT * FROM forward_job WHERE folder_id = ? AND destination_id = ? AND state = 'SENT'"
                + " ORDER BY sent_at DESC LIMIT ?", RecordRepo::job, folderId, destinationId, limit);
    }

    /** Every job of one uploaded file: one per destination. */
    public List<ForwardJob> jobsForRecord(String uploadRecordId) {
        return db.query("SELECT * FROM forward_job WHERE upload_record_id = ? ORDER BY created_at, destination_id",
                RecordRepo::job, uploadRecordId);
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

    /**
     * Moves a job to its next state and the file's upload record to the overall status of all its jobs. Returns
     * true when this change finished the file: every one of its jobs is now sent or cancelled.
     */
    public boolean updateJob(String id, JobState state, String error, long nextAttemptAt, Long firstFailedAt,
                             Long sentAt) {
        db.update("UPDATE forward_job SET state = ?, last_error = ?, next_attempt_at = ?, first_failed_at = ?,"
                        + " sent_at = ?, updated_at = ? WHERE id = ?", state, error, nextAttemptAt, firstFailedAt,
                sentAt, System.currentTimeMillis(), id);
        return job(id).map(j -> refreshRecord(j.uploadRecordId())).orElse(false);
    }

    /** The owner gives up on a failed job's destination. Returns true when that finished the file. */
    public boolean cancelJob(String id, String bySub, String reason) {
        int n = db.update("UPDATE forward_job SET state = 'CANCELLED', cancelled_by = ?, cancel_reason = ?,"
                + " updated_at = ? WHERE id = ? AND state = 'FAILED'", bySub, reason, System.currentTimeMillis(), id);
        return n > 0 && job(id).map(j -> refreshRecord(j.uploadRecordId())).orElse(false);
    }

    /** A forward folder was deleted: every job not yet sent or cancelled is cancelled. */
    public int cancelOpenJobs(String folderId, String bySub, String reason) {
        return db.update("UPDATE forward_job SET state = 'CANCELLED', cancelled_by = ?, cancel_reason = ?,"
                + " updated_at = ? WHERE folder_id = ? AND state NOT IN ('SENT', 'CANCELLED')", bySub, reason,
                System.currentTimeMillis(), folderId);
    }

    /**
     * A file's overall forward status from its jobs, the worst first: failed, paused, sending (also while a job
     * waits to retry), queued; sent once every job is sent or cancelled, and cancelled if none was sent.
     */
    public static RecordStatus overall(List<JobState> states) {
        if (states.isEmpty()) {
            return null;
        }
        if (states.contains(JobState.FAILED)) {
            return RecordStatus.FAILED;
        }
        if (states.contains(JobState.PAUSED)) {
            return RecordStatus.PAUSED;
        }
        if (states.contains(JobState.SENDING) || states.contains(JobState.RETRY_WAIT)) {
            return RecordStatus.SENDING;
        }
        if (states.contains(JobState.QUEUED)) {
            return RecordStatus.QUEUED;
        }
        return states.contains(JobState.SENT) ? RecordStatus.SENT : RecordStatus.CANCELLED;
    }

    /** Sets an upload record's status from its jobs, e.g. after new jobs were queued for it. */
    public void refreshStatus(String uploadRecordId) {
        refreshRecord(uploadRecordId);
    }

    /**
     * Sets an upload record's status from its jobs. Returns true only for the change that finished the file, so
     * when two destinations finish at once exactly one caller deletes or keeps it.
     */
    private boolean refreshRecord(String uploadRecordId) {
        if (uploadRecordId == null) {
            return false;
        }
        List<JobState> states = db.query("SELECT state FROM forward_job WHERE upload_record_id = ?",
                rs -> JobState.valueOf(rs.getString(1)), uploadRecordId);
        RecordStatus s = overall(states);
        if (s == null) {
            return false;
        }
        int n = db.update("UPDATE upload_record SET status = ? WHERE id = ? AND status <> ? AND status IN "
                + FORWARD_STATUSES, s, uploadRecordId, s);
        return n > 0 && (s == RecordStatus.SENT || s == RecordStatus.CANCELLED);
    }

    /**
     * Pauses or resumes a folder's unfinished jobs to one destination (destination disabled, or no longer assigned
     * to the owner, and back). Jobs to the folder's other destinations are not touched.
     */
    public void pauseDestination(String folderId, String destinationId, boolean pause) {
        List<String> touched;
        if (pause) {
            touched = db.query("SELECT DISTINCT upload_record_id FROM forward_job WHERE folder_id = ?"
                    + " AND destination_id = ? AND state IN ('QUEUED', 'RETRY_WAIT')", rs -> rs.getString(1),
                    folderId, destinationId);
            db.update("UPDATE forward_job SET state = 'PAUSED', updated_at = ? WHERE folder_id = ?"
                    + " AND destination_id = ? AND state IN ('QUEUED', 'RETRY_WAIT')", System.currentTimeMillis(),
                    folderId, destinationId);
        } else {
            touched = db.query("SELECT DISTINCT upload_record_id FROM forward_job WHERE folder_id = ?"
                    + " AND destination_id = ? AND state = 'PAUSED'", rs -> rs.getString(1), folderId, destinationId);
            db.update("UPDATE forward_job SET state = 'QUEUED', next_attempt_at = ?, updated_at = ?"
                    + " WHERE folder_id = ? AND destination_id = ? AND state = 'PAUSED'", 0L,
                    System.currentTimeMillis(), folderId, destinationId);
        }
        touched.forEach(this::refreshRecord);
    }

    public long countJobs(JobState state, String destinationId) {
        return db.queryLong("SELECT COUNT(*) FROM forward_job WHERE state = ? AND destination_id = ?", state,
                destinationId);
    }

    /** Jobs to a destination that are not yet sent or cancelled. */
    public long countOpenJobs(String destinationId) {
        return db.queryLong("SELECT COUNT(*) FROM forward_job WHERE destination_id = ?"
                + " AND state NOT IN ('SENT', 'CANCELLED')", destinationId);
    }

    /** A forward folder's upload records whose files may still be in fdrop, to send to an added destination. */
    public List<UploadRecord> forwardRecordsInFolder(String folderId) {
        return db.query("SELECT * FROM upload_record WHERE folder_id = ? AND went_to = 'FORWARD' AND status IN "
                + FORWARD_STATUSES + " ORDER BY completed_at", RecordRepo::record, folderId);
    }

    /** Recovers jobs left SENDING by a crash. */
    public int resetStuckSending(long before) {
        return db.update("UPDATE forward_job SET state = 'RETRY_WAIT', next_attempt_at = 0 WHERE state = 'SENDING'"
                + " AND updated_at < ?", before);
    }
}
