package io.fdrop.core.db;

import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.FolderForward;
import io.fdrop.core.model.Records.FolderMember;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Shared and forward folders, their members, and forward settings. */
public final class FolderRepo {

    private final Db db;

    public FolderRepo(Db db) {
        this.db = db;
    }

    private static Folder folder(ResultSet rs) throws SQLException {
        return new Folder(rs.getString("id"), FolderType.valueOf(rs.getString("type")), rs.getString("name"),
                rs.getString("owner_sub"), rs.getLong("created_at"));
    }

    private static FolderMember member(ResultSet rs) throws SQLException {
        return new FolderMember(rs.getString("folder_id"), rs.getString("user_sub"),
                Permission.valueOf(rs.getString("permission")));
    }

    public Optional<Folder> find(String id) {
        return db.queryOne("SELECT * FROM folder WHERE id = ?", FolderRepo::folder, id);
    }

    public Optional<Folder> findByName(FolderType type, String name) {
        return db.queryOne("SELECT * FROM folder WHERE type = ? AND name_lower = ?", FolderRepo::folder, type,
                name.toLowerCase(Locale.ROOT));
    }

    /** Folders the user owns or belongs to, by type. */
    public List<Folder> visibleTo(String sub, FolderType type) {
        return db.query("SELECT f.* FROM folder f WHERE f.type = ? AND (f.owner_sub = ? OR EXISTS"
                        + " (SELECT 1 FROM folder_member m WHERE m.folder_id = f.id AND m.user_sub = ?))"
                        + " ORDER BY f.name_lower", FolderRepo::folder, type, sub, sub);
    }

    public List<Folder> visibleTo(String sub) {
        return db.query("SELECT f.* FROM folder f WHERE f.owner_sub = ? OR EXISTS"
                        + " (SELECT 1 FROM folder_member m WHERE m.folder_id = f.id AND m.user_sub = ?)"
                        + " ORDER BY f.type, f.name_lower", FolderRepo::folder, sub, sub);
    }

    public List<Folder> visibleToAll(FolderType type) {
        return db.query("SELECT * FROM folder WHERE type = ?", FolderRepo::folder, type);
    }

    public List<Folder> ownedBy(String sub) {
        return db.query("SELECT * FROM folder WHERE owner_sub = ?", FolderRepo::folder, sub);
    }

    public void insert(Folder f) {
        db.update("INSERT INTO folder (id, type, name, name_lower, owner_sub, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                f.id(), f.type(), f.name(), f.name().toLowerCase(Locale.ROOT), f.ownerSub(), f.createdAt());
    }

    public void rename(String id, String name) {
        db.update("UPDATE folder SET name = ?, name_lower = ? WHERE id = ?", name, name.toLowerCase(Locale.ROOT), id);
    }

    public void setOwner(String id, String ownerSub) {
        db.update("UPDATE folder SET owner_sub = ? WHERE id = ?", ownerSub, id);
    }

    public void delete(String id) {
        db.update("DELETE FROM folder WHERE id = ?", id);
    }

    public long count(FolderType type) {
        return db.queryLong("SELECT COUNT(*) FROM folder WHERE type = ?", type);
    }

    public List<FolderMember> members(String folderId) {
        return db.query("SELECT * FROM folder_member WHERE folder_id = ?", FolderRepo::member, folderId);
    }

    public Optional<FolderMember> member(String folderId, String sub) {
        return db.queryOne("SELECT * FROM folder_member WHERE folder_id = ? AND user_sub = ?", FolderRepo::member,
                folderId, sub);
    }

    public void putMember(String folderId, String sub, Permission p) {
        db.inTransaction(() -> {
            if (db.update("UPDATE folder_member SET permission = ? WHERE folder_id = ? AND user_sub = ?",
                    p, folderId, sub) == 0) {
                db.update("INSERT INTO folder_member (folder_id, user_sub, permission) VALUES (?, ?, ?)",
                        folderId, sub, p);
            }
            return null;
        });
    }

    public boolean removeMember(String folderId, String sub) {
        return db.update("DELETE FROM folder_member WHERE folder_id = ? AND user_sub = ?", folderId, sub) > 0;
    }

    public void removeMemberships(String sub) {
        db.update("DELETE FROM folder_member WHERE user_sub = ?", sub);
    }

    public Optional<FolderForward> forward(String folderId) {
        return db.queryOne("SELECT * FROM folder_forward WHERE folder_id = ?",
                rs -> new FolderForward(rs.getString("folder_id"), rs.getString("destination_id"),
                        rs.getInt("keep_days")), folderId);
    }

    public void putForward(FolderForward ff) {
        db.inTransaction(() -> {
            if (db.update("UPDATE folder_forward SET destination_id = ?, keep_days = ? WHERE folder_id = ?",
                    ff.destinationId(), ff.keepDays(), ff.folderId()) == 0) {
                db.update("INSERT INTO folder_forward (folder_id, destination_id, keep_days) VALUES (?, ?, ?)",
                        ff.folderId(), ff.destinationId(), ff.keepDays());
            }
            return null;
        });
    }

    /** Forward folders using a destination. */
    public List<Folder> usingDestination(String destinationId) {
        return db.query("SELECT f.* FROM folder f JOIN folder_forward ff ON ff.folder_id = f.id"
                + " WHERE ff.destination_id = ? ORDER BY f.name_lower", FolderRepo::folder, destinationId);
    }
}
