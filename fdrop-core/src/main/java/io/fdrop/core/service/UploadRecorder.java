package io.fdrop.core.service;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.db.Db;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.SearchRepo;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.RecordStatus;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Enums.WentTo;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.model.Records.UploadRecord;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.util.Hashing;

import java.util.List;
import java.util.Locale;

/**
 * Called once per finished upload, whichever way it arrived: writes the {@code upload_record} row behind the My
 * uploads page and, for forward folders, queues one forward job per destination in the same transaction.
 */
public final class UploadRecorder {

    /** Notified after a forward job is queued, so a worker can pick it up without waiting for its poll. */
    public interface JobListener {
        void jobQueued();
    }

    private final Db db;
    private final RecordRepo records;
    private final FolderRepo folders;
    private final FdropMetrics metrics;
    private volatile JobListener listener = () -> { };

    private final SearchRepo search;

    public UploadRecorder(Db db, RecordRepo records, FolderRepo folders, FdropMetrics metrics, SearchRepo search) {
        this.search = search;
        this.db = db;
        this.records = records;
        this.folders = folders;
        this.metrics = metrics;
    }

    public void setJobListener(JobListener l) {
        this.listener = l;
    }

    /** Tells the forward workers that jobs were queued outside an upload (a destination added to a folder). */
    public void jobsQueued() {
        listener.jobQueued();
    }

    public UploadRecord record(Principal p, VirtualPath vp, ObjectInfo info, Via via, String clientIp,
                               String startIp) {
        long now = System.currentTimeMillis();
        WentTo wentTo = vp.wentTo();
        UploadRecord rec = new UploadRecord(Hashing.newId("rec"), p.sub(), vp.name(), info.size(), wentTo,
                vp.folder() == null ? null : vp.folder().id(), vp.virtual(), vp.virtual(), info.path().toString(),
                wentTo == WentTo.FORWARD ? RecordStatus.QUEUED : RecordStatus.STORED, via, clientIp,
                startIp == null ? clientIp : startIp, now, null);
        boolean queued = db.inTransaction(() -> {
            records.insert(rec);
            search.append(SearchRepo.Op.INDEX, info.path().toString(), null);
            if (wentTo == WentTo.FORWARD) {
                List<String> dests = folders.destinations(vp.folder().id());
                if (dests.isEmpty()) {
                    throw new IllegalStateException("forward folder without destinations");
                }
                // one job per destination, so each destination receives its own copy; consecutive creation
                // times keep a file's jobs listed in the folder's destination order
                for (int i = 0; i < dests.size(); i++) {
                    records.insertJob(new ForwardJob(Hashing.newId("job"), vp.folder().id(), dests.get(i), rec.id(),
                            info.path().toString(), vp.name(), info.size(), JobState.QUEUED, 0, null, 0, null,
                            now + i, now, null, null, null));
                }
                return true;
            }
            return false;
        });
        metrics.uploadCompleted(via.name().toLowerCase(Locale.ROOT), wentTo.name().toLowerCase(Locale.ROOT));
        Audit.log("upload", "user", p.username(), "ip", clientIp, "protocol", via, "path", vp.virtual(),
                "bytes", info.size());
        if (queued) {
            listener.jobQueued();
        }
        return rec;
    }
}
