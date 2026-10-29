package io.fdrop.core.db;

import io.fdrop.core.model.Enums.RetrievalState;
import io.fdrop.core.model.Records.RemoteRetrieval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Copies of forward destinations' remote files, retrieved into fdrop from a forward folder's destination tab. */
public final class RetrievalRepo {

    private final Db db;

    public RetrievalRepo(Db db) {
        this.db = db;
    }

    private static RemoteRetrieval row(ResultSet rs) throws SQLException {
        return new RemoteRetrieval(rs.getString("id"), rs.getString("folder_id"), rs.getString("destination_id"),
                rs.getString("remote_path"), rs.getLong("remote_size"), rs.getLong("remote_mtime"),
                RetrievalState.valueOf(rs.getString("state")), rs.getLong("bytes_done"), rs.getString("storage_key"),
                rs.getBoolean("own_copy"), rs.getString("charged_sub"), rs.getString("error"),
                rs.getString("requested_by"), rs.getLong("created_at"), rs.getLong("updated_at"),
                Db.getLong(rs, "ready_at"), Db.getLong(rs, "last_download_at"), Db.getLong(rs, "expires_at"));
    }

    public Optional<RemoteRetrieval> find(String id) {
        return db.queryOne("SELECT * FROM remote_retrieval WHERE id = ?", RetrievalRepo::row, id);
    }

    /** Every retrieval from one destination through one folder, newest first. */
    public List<RemoteRetrieval> forDestination(String folderId, String destinationId) {
        return db.query("SELECT * FROM remote_retrieval WHERE folder_id = ? AND destination_id = ?"
                + " ORDER BY created_at DESC", RetrievalRepo::row, folderId, destinationId);
    }

    /** Retrievals of one remote path, newest first. */
    public List<RemoteRetrieval> forPath(String folderId, String destinationId, String remotePath) {
        return db.query("SELECT * FROM remote_retrieval WHERE folder_id = ? AND destination_id = ?"
                + " AND remote_path = ? ORDER BY created_at DESC", RetrievalRepo::row, folderId, destinationId,
                remotePath);
    }

    public void insert(RemoteRetrieval r) {
        db.update("INSERT INTO remote_retrieval (id, folder_id, destination_id, remote_path, remote_size, remote_mtime,"
                        + " state, bytes_done, storage_key, own_copy, charged_sub, error, requested_by, created_at,"
                        + " updated_at, ready_at, last_download_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", r.id(), r.folderId(),
                r.destinationId(), r.remotePath(), r.remoteSize(), r.remoteMtime(), r.state(), r.bytesDone(),
                r.storageKey(), r.ownCopy(), r.chargedSub(), r.error(), r.requestedBy(), r.createdAt(),
                r.updatedAt(), r.readyAt(), r.lastDownloadAt(), r.expiresAt());
    }

    /** Moves a queued retrieval to RETRIEVING; false if another node or thread already did. */
    public boolean start(String id, long now) {
        return db.update("UPDATE remote_retrieval SET state = 'RETRIEVING', updated_at = ? WHERE id = ?"
                + " AND state = 'QUEUED'", now, id) > 0;
    }

    public void progress(String id, long bytesDone, long now) {
        db.update("UPDATE remote_retrieval SET bytes_done = ?, updated_at = ? WHERE id = ?", bytesDone, now, id);
    }

    public void ready(String id, long bytes, String chargedSub, long now, long expiresAt) {
        db.update("UPDATE remote_retrieval SET state = 'READY', bytes_done = ?, charged_sub = ?, error = NULL,"
                + " updated_at = ?, ready_at = ?, expires_at = ? WHERE id = ?", bytes, chargedSub, now, now, expiresAt,
                id);
    }

    public void fail(String id, String error, long now) {
        db.update("UPDATE remote_retrieval SET state = 'FAILED', error = ?, updated_at = ? WHERE id = ?",
                error.length() > 900 ? error.substring(0, 900) : error, now, id);
    }

    public void downloaded(String id, long now, long expiresAt) {
        db.update("UPDATE remote_retrieval SET last_download_at = ?, expires_at = ?, updated_at = ? WHERE id = ?",
                now, expiresAt, now, id);
    }

    public void delete(String id) {
        db.update("DELETE FROM remote_retrieval WHERE id = ?", id);
    }

    /** Ready copies past their keep time, failed rows a day old, and every row whose folder has been deleted. */
    public List<RemoteRetrieval> due(long now, int limit) {
        return db.query("SELECT r.* FROM remote_retrieval r LEFT JOIN folder f ON f.id = r.folder_id"
                        + " WHERE f.id IS NULL OR (r.state = 'READY' AND r.expires_at < ?)"
                        + " OR (r.state = 'FAILED' AND r.updated_at < ?) ORDER BY r.updated_at LIMIT ?",
                RetrievalRepo::row, now, now - 86_400_000L, limit);
    }

    /** Retrievals a node stopped working on (it crashed or restarted): no progress since {@code before}. */
    public int failStale(long before, long now) {
        return db.update("UPDATE remote_retrieval SET state = 'FAILED', error = 'interrupted: fdrop restarted',"
                + " updated_at = ? WHERE state IN ('QUEUED', 'RETRIEVING') AND updated_at < ?", now, before);
    }
}
