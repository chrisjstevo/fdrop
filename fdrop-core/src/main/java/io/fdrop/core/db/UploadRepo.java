package io.fdrop.core.db;

import io.fdrop.core.model.Enums.UploadState;
import io.fdrop.core.model.Enums.WentTo;
import io.fdrop.core.model.Records.UploadChunk;
import io.fdrop.core.model.Records.UploadSession;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Resumable upload sessions and their acknowledged chunks. */
public final class UploadRepo {

    private final Db db;

    public UploadRepo(Db db) {
        this.db = db;
    }

    private static UploadSession session(ResultSet rs) throws SQLException {
        return new UploadSession(rs.getString("id"), rs.getString("user_sub"), rs.getString("virtual_path"),
                rs.getString("storage_key"), rs.getString("folder_id"), WentTo.valueOf(rs.getString("went_to")),
                rs.getString("file_name"), rs.getLong("size"), rs.getLong("chunk_size"), rs.getInt("chunk_count"),
                rs.getString("sha256"), UploadState.valueOf(rs.getString("state")),
                rs.getString("backend_upload_id"), rs.getBoolean("overwrite"), rs.getString("start_ip"),
                Db.getLong(rs, "last_modified"), rs.getString("error"), rs.getLong("created_at"),
                rs.getLong("updated_at"), rs.getLong("expires_at"));
    }

    private static UploadChunk chunk(ResultSet rs) throws SQLException {
        return new UploadChunk(rs.getString("session_id"), rs.getInt("idx"), rs.getLong("size"),
                rs.getString("sha256"), rs.getString("etag"), rs.getLong("received_at"));
    }

    public void insert(UploadSession s) {
        db.update("INSERT INTO upload_session (id, user_sub, virtual_path, storage_key, folder_id, went_to, file_name,"
                        + " size, chunk_size, chunk_count, sha256, state, backend_upload_id, overwrite, start_ip,"
                        + " last_modified, error, created_at, updated_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", s.id(), s.userSub(),
                s.virtualPath(), s.storageKey(), s.folderId(), s.wentTo(), s.fileName(), s.size(), s.chunkSize(),
                s.chunkCount(), s.sha256(), s.state(), s.backendUploadId(), s.overwrite(), s.startIp(),
                s.lastModified(), s.error(), s.createdAt(), s.updatedAt(), s.expiresAt());
    }

    public Optional<UploadSession> find(String id) {
        return db.queryOne("SELECT * FROM upload_session WHERE id = ?", UploadRepo::session, id);
    }

    public List<UploadSession> openFor(String sub) {
        return db.query("SELECT * FROM upload_session WHERE user_sub = ? AND state IN ('OPEN', 'ASSEMBLING')",
                UploadRepo::session, sub);
    }

    /** Bytes reserved by a user's open sessions, counted against quota. */
    public long reservedBytes(String sub) {
        return db.queryLong("SELECT COALESCE(SUM(size), 0) FROM upload_session WHERE user_sub = ?"
                + " AND state IN ('OPEN', 'ASSEMBLING')", sub);
    }

    /**
     * Moves a session from one state to another. Returns false when it was not in {@code from}, so two callers
     * racing for the same transition cannot both win.
     */
    public boolean transition(String id, UploadState from, UploadState to, String error) {
        return db.update("UPDATE upload_session SET state = ?, error = ?, updated_at = ? WHERE id = ? AND state = ?",
                to, error, System.currentTimeMillis(), id, from) > 0;
    }

    public void extend(String id, long expiresAt) {
        db.update("UPDATE upload_session SET expires_at = ?, updated_at = ? WHERE id = ?", expiresAt,
                System.currentTimeMillis(), id);
    }

    public List<UploadSession> expired(long now) {
        return db.query("SELECT * FROM upload_session WHERE state = 'OPEN' AND expires_at < ?", UploadRepo::session,
                now);
    }

    public List<UploadSession> finishedBefore(long cutoff) {
        return db.query("SELECT * FROM upload_session WHERE state IN ('COMPLETED', 'ABORTED', 'EXPIRED', 'FAILED')"
                + " AND updated_at < ?", UploadRepo::session, cutoff);
    }

    public long countByState(UploadState state) {
        return db.queryLong("SELECT COUNT(*) FROM upload_session WHERE state = ?", state);
    }

    public void delete(String id) {
        db.update("DELETE FROM upload_session WHERE id = ?", id);
    }

    public void deleteForUser(String sub) {
        db.update("DELETE FROM upload_session WHERE user_sub = ?", sub);
    }

    public List<UploadChunk> chunks(String sessionId) {
        return db.query("SELECT * FROM upload_chunk WHERE session_id = ? ORDER BY idx", UploadRepo::chunk, sessionId);
    }

    public Optional<UploadChunk> chunk(String sessionId, int idx) {
        return db.queryOne("SELECT * FROM upload_chunk WHERE session_id = ? AND idx = ?", UploadRepo::chunk,
                sessionId, idx);
    }

    public void insertChunk(UploadChunk c) {
        db.update("INSERT INTO upload_chunk (session_id, idx, size, sha256, etag, received_at) VALUES (?, ?, ?, ?, ?, ?)",
                c.sessionId(), c.idx(), c.size(), c.sha256(), c.etag(), c.receivedAt());
    }
}
