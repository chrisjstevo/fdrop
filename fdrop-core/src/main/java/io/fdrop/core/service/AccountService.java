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
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.UserKind;
import io.fdrop.core.model.Enums.WentTo;
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

    /**
     * A My uploads row with folder and destination names resolved, and whether its links still work. A forward
     * upload lists each destination it is sent to; {@code destinationName} joins their names, and {@code jobId}
     * is the first failed job, for clients written for one destination.
     */
    public record HistoryRow(UploadRecord record, String folderName, String destinationName, boolean reachable,
                             boolean canRetry, String jobId, List<DestinationStatus> destinations) {
    }

    /** Where one forward upload stands at one destination. */
    public record DestinationStatus(String jobId, String destinationId, String destinationName, JobState state,
                                    String error, Long sentAt) {
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

    /** Called at every login: creates the fdrop row on first sight, with the quota for the account's kind. */
    public User ensure(Principal p) {
        if (p.app()) {
            return users.upsert(p.sub(), p.username(), p.email(), cfg.limits.defaultAppQuota.bytes(),
                    UserKind.APPLICATION);
        }
        return users.upsert(p.sub(), p.username(), p.email(), cfg.limits.defaultQuota.bytes(), UserKind.USER);
    }

    public User me(Principal p) {
        return users.find(p.sub()).orElseGet(() -> ensure(p));
    }

    public long reserved(Principal p) {
        return uploads.reservedBytes(p.sub());
    }

    /** People and application accounts an owner can add as members. */
    public List<User> searchUsers(Principal p, String q) {
        if (!p.isPerson()) {
            throw FdropException.forbidden("the standard role is required");
        }
        return users.search(q == null ? "" : q, 20);
    }

    // ---- SSH keys ----

    public List<SshKey> keys(Principal p) {
        return users.keys(p.sub());
    }

    public SshKey addKey(Principal p, String name, String publicKey) {
        if (!p.isPerson()) {
            throw FdropException.forbidden(p.app() ? "an admin manages an application account's SSH keys"
                    : "the standard role is required");
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
        if (p.app()) {
            throw FdropException.forbidden("an admin manages an application account's SSH keys");
        }
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
            List<DestinationStatus> dests = List.of();
            if (r.wentTo() == WentTo.FORWARD) {
                dests = records.jobsForRecord(r.id()).stream().map(j -> new DestinationStatus(j.id(),
                        j.destinationId(), destNames.computeIfAbsent(j.destinationId(),
                        id -> destinations.find(id).map(ForwardDestination::name).orElse("(deleted destination)")),
                        j.state(), j.lastError(), j.sentAt())).toList();
                destName = dests.isEmpty() ? null
                        : String.join(", ", dests.stream().map(DestinationStatus::destinationName).toList());
                boolean owner = folder.isPresent() && folder.get().ownerSub().equals(p.sub());
                jobId = dests.stream().filter(d -> d.state() == JobState.FAILED).map(DestinationStatus::jobId)
                        .findFirst().orElse(null);
                canRetry = owner && jobId != null;
                if (!owner) {
                    jobId = null;
                }
            }
            boolean reachable = r.storageKey() != null && (r.folderId() == null
                    ? r.storageKey().startsWith(PathResolver.homeKey(p.sub()) + "/")
                    : folder.isPresent() && resolver.permission(p, folder.get()).isPresent());
            return new HistoryRow(r, folderName, destName, reachable, canRetry, jobId, dests);
        }).toList();
        return new HistoryPage(rows, page.totalCount(), page.totalBytes(), page.nextCursor());
    }
}
