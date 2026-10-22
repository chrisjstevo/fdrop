package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.DestinationRepo;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.RecordRepo.Filter;
import io.fdrop.core.db.RecordRepo.Page;
import io.fdrop.core.db.UploadRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Records.Folder;
import io.fdrop.core.model.Records.FolderForward;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.model.Records.SshKey;
import io.fdrop.core.model.Records.UploadRecord;
import io.fdrop.core.model.Records.User;
import io.fdrop.core.util.Hashing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The signed-in user's own things: profile, quota, SSH keys, and the My uploads history. */
public final class AccountService {

    /** A My uploads row with folder and destination names resolved, and whether its links still work. */
    public record HistoryRow(UploadRecord record, String folderName, String destinationName, boolean reachable,
                             boolean canRetry, String jobId) {
    }

    /** One page of My uploads. */
    public record HistoryPage(List<HistoryRow> rows, long totalCount, long totalBytes, String nextCursor) {
    }

    private final FdropConfig cfg;
    private final UserRepo users;
    private final UploadRepo uploads;
    private final RecordRepo records;
    private final FolderRepo folders;
    private final DestinationRepo destinations;
    private final PathResolver resolver;
    private final SshTools ssh;

    public AccountService(FdropConfig cfg, UserRepo users, UploadRepo uploads, RecordRepo records, FolderRepo folders,
                          DestinationRepo destinations, PathResolver resolver, SshTools ssh) {
        this.cfg = cfg;
        this.users = users;
        this.uploads = uploads;
        this.records = records;
        this.folders = folders;
        this.destinations = destinations;
        this.resolver = resolver;
        this.ssh = ssh;
    }

    /** Called at every login: creates the fdrop row on first sight. */
    public User ensure(Principal p) {
        return users.upsert(p.sub(), p.username(), p.email(), cfg.limits.defaultQuota.bytes());
    }

    public User me(Principal p) {
        return users.find(p.sub()).orElseGet(() -> ensure(p));
    }

    public long reserved(Principal p) {
        return uploads.reservedBytes(p.sub());
    }

    public List<User> searchUsers(Principal p, String q) {
        if (!p.canUseFiles()) {
            throw FdropException.forbidden("the standard role is required");
        }
        return users.search(q == null ? "" : q, 20);
    }

    // ---- SSH keys ----

    public List<SshKey> keys(Principal p) {
        return users.keys(p.sub());
    }

    public SshKey addKey(Principal p, String name, String publicKey) {
        if (!p.canUseFiles()) {
            throw FdropException.forbidden("the standard role is required");
        }
        ensure(p);
        SshTools.ParsedKey k = ssh.parsePublicKey(publicKey);
        String label = name == null || name.isBlank() ? k.type() : Names.label(name, "name", 200);
        SshKey key = new SshKey(Hashing.newId("ssh"), p.sub(), label, k.type(), k.normalized(), k.fingerprint(),
                System.currentTimeMillis(), null);
        try {
            users.addKey(key);
        } catch (io.fdrop.core.db.Db.DbException e) {
            if (e.isUniqueViolation()) {
                throw FdropException.conflict("that key is already added");
            }
            throw e;
        }
        Audit.log("sshkey.add", "user", p.username(), "fingerprint", k.fingerprint());
        return key;
    }

    public void deleteKey(Principal p, String id) {
        if (!users.deleteKey(p.sub(), id)) {
            throw FdropException.notFound("no such key");
        }
        Audit.log("sshkey.delete", "user", p.username(), "key", id);
    }

    // ---- My uploads ----

    public HistoryPage history(Principal p, Filter f) {
        Page page = records.page(p.sub(), f);
        Map<String, Optional<Folder>> folderCache = new HashMap<>();
        Map<String, String> destNames = new HashMap<>();
        List<HistoryRow> rows = page.rows().stream().map(r -> {
            Optional<Folder> folder = r.folderId() == null ? Optional.empty()
                    : folderCache.computeIfAbsent(r.folderId(), folders::find);
            String folderName = folder.map(Folder::name).orElse(null);
            String destName = null;
            boolean canRetry = false;
            String jobId = null;
            if (folder.isPresent() && r.wentTo() == io.fdrop.core.model.Enums.WentTo.FORWARD) {
                Optional<FolderForward> ff = folders.forward(folder.get().id());
                if (ff.isPresent()) {
                    destName = destNames.computeIfAbsent(ff.get().destinationId(),
                            id -> destinations.find(id).map(ForwardDestination::name).orElse(null));
                }
                canRetry = folder.get().ownerSub().equals(p.sub())
                        && r.status() == io.fdrop.core.model.Enums.RecordStatus.FAILED;
                if (canRetry) {
                    jobId = records.jobsForFolder(folder.get().id(), 1000).stream()
                            .filter(j -> r.id().equals(j.uploadRecordId())).map(j -> j.id()).findFirst()
                            .orElse(null);
                }
            }
            boolean reachable = r.storageKey() != null && (r.folderId() == null
                    ? r.storageKey().startsWith(PathResolver.homeKey(p.sub()) + "/")
                    : folder.isPresent() && resolver.permission(p, folder.get()).isPresent());
            return new HistoryRow(r, folderName, destName, reachable, canRetry, jobId);
        }).toList();
        return new HistoryPage(rows, page.totalCount(), page.totalBytes(), page.nextCursor());
    }
}
