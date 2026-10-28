package io.fdrop.storage.local;

import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.RecordStatus;
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Enums.WentTo;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.service.AccountService;
import io.fdrop.core.service.DestinationService;
import io.fdrop.core.service.FileService;
import io.fdrop.core.service.FolderService;
import io.fdrop.core.storage.StoragePath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Forward folders with several destinations: one job per file and destination, the file's overall status, when it
 * leaves fdrop, giving up on a destination, and changing destinations. Sending itself is in the SFTP module's
 * ForwardWorkerTest; here the workers' state changes are made directly.
 */
class ForwardDestinationsTest {

    @TempDir
    Path dir;

    Fdrop app;
    final Principal alice = TestSupport.user("alice");
    final Principal bob = TestSupport.user("bob");
    final Principal admin = TestSupport.admin("root");
    String a;
    String b;
    String c;

    @BeforeEach
    void setUp() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.forward.maxDestinationsPerFolder = 3;
        LocalStorageBackend backend = new LocalStorageBackend();
        backend.init(dir);
        app = TestSupport.fdrop(cfg, backend);
        for (Principal p : List.of(alice, bob, admin)) {
            app.accounts.ensure(p);
        }
        a = destination("Partner A", "/a");
        b = destination("Partner A DR", "/b");
        c = destination("Regional office", "/c");
        for (String d : List.of(a, b, c)) {
            app.destinationService.assign(admin, d, "alice");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        app.close();
    }

    private String destination(String name, String dir) {
        return app.destinationService.create(admin, new DestinationService.DestinationInput(name, null,
                "sftp.test.invalid", 22, dir, "fdrop", ServerAuth.HOST_KEY, "SHA256:abc", null, false, true))
                .destination().id();
    }

    private void put(Principal p, String path, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try (FileService.FileWrite w = app.files.openWrite(p, path, data.length, false, Via.HTTP, "203.0.113.7")) {
            w.write(ByteBuffer.wrap(data));
            w.commit();
        }
    }

    /** The jobs of the newest file called {@code name}, by destination. */
    private Map<String, ForwardJob> jobs(String folderId, String name) {
        List<ForwardJob> all = app.folderService.jobs(alice, folderId, 100).stream()
                .filter(j -> j.fileName().equals(name)).toList();
        String record = all.get(0).uploadRecordId();
        return all.stream().filter(j -> j.uploadRecordId().equals(record))
                .collect(Collectors.toMap(ForwardJob::destinationId, Function.identity()));
    }

    private RecordStatus status(String name) {
        return app.accounts.history(alice, new RecordRepo.Filter(WentTo.FORWARD, false, name, null, null, null, null,
                10)).rows().get(0).record().status();
    }

    private boolean stored(ForwardJob j) throws Exception {
        return app.storage.stat(StoragePath.of(j.storageKey())).isPresent();
    }

    private void sent(ForwardJob j) {
        if (app.records.updateJob(j.id(), JobState.SENT, null, 0, null, System.currentTimeMillis())) {
            app.forwarding.finished(app.records.job(j.id()).orElseThrow());
        }
    }

    private void failed(ForwardJob j) {
        app.records.updateJob(j.id(), JobState.FAILED, "host key mismatch", 0, System.currentTimeMillis(), null);
    }

    @Test
    void eachFileGetsAJobPerDestinationAndLeavesOnlyAfterTheLast() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.FORWARD, "Invoices", List.of(a, b), 0);
        assertThat(f.destinations()).extracting(d -> d.name()).containsExactly("Partner A", "Partner A DR");
        assertThat(f.destinationName()).isEqualTo("Partner A, Partner A DR");

        put(alice, "/forward/Invoices/inv-1.pdf", "one");
        Map<String, ForwardJob> jobs = jobs(f.id(), "inv-1.pdf");
        assertThat(jobs).containsOnlyKeys(a, b);
        assertThat(status("inv-1.pdf")).isEqualTo(RecordStatus.QUEUED);

        sent(jobs.get(a));
        assertThat(status("inv-1.pdf")).isEqualTo(RecordStatus.QUEUED);
        assertThat(stored(jobs.get(a))).as("still waiting for Partner A DR").isTrue();

        app.records.updateJob(jobs.get(b).id(), JobState.RETRY_WAIT, "connection refused", 0, 1L, null);
        assertThat(status("inv-1.pdf")).isEqualTo(RecordStatus.SENDING);

        sent(jobs.get(b));
        assertThat(status("inv-1.pdf")).isEqualTo(RecordStatus.SENT);
        assertThat(stored(jobs.get(b))).as("deleted once both have it").isFalse();

        AccountService.HistoryRow row = app.accounts.history(alice, new RecordRepo.Filter(WentTo.FORWARD, false,
                "inv-1", null, null, null, null, 10)).rows().get(0);
        assertThat(row.destinations()).extracting(AccountService.DestinationStatus::state)
                .containsOnly(JobState.SENT);
        assertThat(row.destinationName()).isEqualTo("Partner A, Partner A DR");
    }

    @Test
    void keptFilesExpireFromTheLastSend() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.FORWARD, "Kept", List.of(a, b), 7);
        put(alice, "/forward/Kept/r.csv", "r");
        Map<String, ForwardJob> jobs = jobs(f.id(), "r.csv");
        sent(jobs.get(a));
        assertThat(app.expiry.find(StoragePath.of(jobs.get(a).storageKey()))).isEmpty();
        sent(jobs.get(b));
        long lastSent = app.records.job(jobs.get(b).id()).orElseThrow().sentAt();
        var expiry = app.expiry.find(StoragePath.of(jobs.get(b).storageKey())).orElseThrow();
        // seven calendar days, so allow for a daylight-saving change in the server's zone
        assertThat(expiry.expiresAt()).isCloseTo(lastSent + 7L * 24 * 3600 * 1000, within(3_600_000L));
        assertThat(stored(jobs.get(b))).isTrue();
    }

    @Test
    void aFailedDestinationHoldsTheFileUntilRetriedOrStopped() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.FORWARD, "Out", List.of(a, b, c), 0);
        put(alice, "/forward/Out/x.bin", "x");
        Map<String, ForwardJob> jobs = jobs(f.id(), "x.bin");
        sent(jobs.get(a));
        failed(jobs.get(b));
        app.records.pauseDestination(f.id(), c, true);
        assertThat(status("x.bin")).as("failed is worse than paused").isEqualTo(RecordStatus.FAILED);
        assertThat(app.records.job(jobs.get(c).id()).orElseThrow().state()).isEqualTo(JobState.PAUSED);
        assertThat(app.records.job(jobs.get(a).id()).orElseThrow().state()).as("other destinations untouched")
                .isEqualTo(JobState.SENT);

        // only the owner, only failed jobs, and only with a reason
        assertThatThrownBy(() -> app.forwarding.cancel(bob, f.id(), jobs.get(b).id(), "gone"))
                .isInstanceOf(FdropException.class);
        assertThatThrownBy(() -> app.forwarding.cancel(alice, f.id(), jobs.get(c).id(), "gone"))
                .isInstanceOf(FdropException.class).hasMessageContaining("only failed jobs");
        assertThatThrownBy(() -> app.forwarding.cancel(alice, f.id(), jobs.get(b).id(), " "))
                .isInstanceOf(FdropException.class).hasMessageContaining("why");

        app.forwarding.cancel(alice, f.id(), jobs.get(b).id(), "partner retired the DR server");
        ForwardJob cancelled = app.records.job(jobs.get(b).id()).orElseThrow();
        assertThat(cancelled.state()).isEqualTo(JobState.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("partner retired the DR server");
        assertThat(status("x.bin")).isEqualTo(RecordStatus.PAUSED);
        assertThat(stored(jobs.get(a))).isTrue();

        app.records.pauseDestination(f.id(), c, false);
        sent(jobs.get(c));
        assertThat(status("x.bin")).isEqualTo(RecordStatus.SENT);
        assertThat(stored(jobs.get(a))).as("sent to A and C, B given up: done").isFalse();
    }

    @Test
    void aFileNoDestinationReceivedStaysInFdrop() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.FORWARD, "Lost", List.of(a), 0);
        put(alice, "/forward/Lost/y.bin", "y");
        ForwardJob j = jobs(f.id(), "y.bin").get(a);
        failed(j);
        app.forwarding.cancel(alice, f.id(), j.id(), "no longer needed");
        assertThat(status("y.bin")).isEqualTo(RecordStatus.CANCELLED);
        assertThat(stored(j)).isTrue();
    }

    @Test
    void choosingDestinationsIsChecked() throws Exception {
        String d = destination("Spare", "/d");
        app.destinationService.assign(admin, d, "alice");
        assertThatThrownBy(() -> app.folderService.create(alice, FolderType.FORWARD, "Too many", List.of(a, b, c, d),
                0)).isInstanceOf(FdropException.class).hasMessageContaining("at most 3");
        assertThatThrownBy(() -> app.folderService.create(alice, FolderType.FORWARD, "Twice", List.of(a, a), 0))
                .isInstanceOf(FdropException.class).hasMessageContaining("only be chosen once");
        assertThatThrownBy(() -> app.folderService.create(alice, FolderType.FORWARD, "None", List.of(), 0))
                .isInstanceOf(FdropException.class).hasMessageContaining("at least one");
        String other = destination("Not hers", "/e");
        assertThatThrownBy(() -> app.folderService.create(alice, FolderType.FORWARD, "Unassigned", List.of(a, other),
                0)).isInstanceOf(FdropException.class).hasMessageContaining("not assigned");

        FolderService.FolderView f = app.folderService.create(alice, FolderType.FORWARD, "Mine", List.of(a, b), 0);
        assertThatThrownBy(() -> app.folderService.update(alice, f.id(), null, List.of(), false, null))
                .isInstanceOf(FdropException.class).hasMessageContaining("at least one");
        assertThatThrownBy(() -> app.folderService.update(bob, f.id(), null, List.of(a), false, null))
                .isInstanceOf(FdropException.class);

        // ownership needs every destination
        app.folderService.putMember(alice, f.id(), "bob", Permission.WRITE);
        app.destinationService.assign(admin, a, "bob");
        assertThatThrownBy(() -> app.folderService.transferOwnership(alice, f.id(), "bob"))
                .isInstanceOf(FdropException.class).hasMessageContaining("Partner A DR");
        app.destinationService.assign(admin, b, "bob");
        assertThat(app.folderService.transferOwnership(alice, f.id(), "bob").owner()).isEqualTo("bob");
    }

    @Test
    void addedDestinationsGetNewFilesOrAlsoTheExistingOnes() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.FORWARD, "Grow", List.of(a), 7);
        put(alice, "/forward/Grow/old.txt", "old");
        ForwardJob old = jobs(f.id(), "old.txt").get(a);
        sent(old);
        assertThat(app.expiry.find(StoragePath.of(old.storageKey()))).as("kept 7 days").isPresent();

        // removing a destination keeps its queued jobs; adding one without sendExisting only affects new files
        put(alice, "/forward/Grow/queued.txt", "q");
        app.folderService.update(alice, f.id(), null, List.of(b), false, null);
        assertThat(jobs(f.id(), "queued.txt")).containsOnlyKeys(a);
        put(alice, "/forward/Grow/new.txt", "n");
        assertThat(jobs(f.id(), "new.txt")).containsOnlyKeys(b);

        // with sendExisting, files still in fdrop go to the added destination too, and a kept file is held again
        FolderService.FolderView after = app.folderService.update(alice, f.id(), null, List.of(b, c), true, null);
        assertThat(after.destinations()).extracting(d -> d.id()).containsExactly(b, c);
        assertThat(jobs(f.id(), "old.txt")).containsOnlyKeys(a, c);
        assertThat(jobs(f.id(), "queued.txt")).containsOnlyKeys(a, c);
        assertThat(jobs(f.id(), "new.txt")).containsOnlyKeys(b, c);
        assertThat(app.expiry.find(StoragePath.of(old.storageKey()))).as("keep period starts again").isEmpty();
        assertThat(status("old.txt")).isEqualTo(RecordStatus.QUEUED);

        // repeating the change queues nothing twice
        app.folderService.update(alice, f.id(), null, List.of(b, c), true, null);
        assertThat(app.folderService.jobs(alice, f.id(), 100)).hasSize(6);
    }

    @Test
    void anUnassignedOrDisabledDestinationPausesOnlyItsJobsAndUploads() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.FORWARD, "Both", List.of(a, b), 0);
        put(alice, "/forward/Both/1.txt", "1");
        Map<String, ForwardJob> jobs = jobs(f.id(), "1.txt");

        app.destinationService.unassign(admin, b, "alice");
        assertThat(app.records.job(jobs.get(b).id()).orElseThrow().state()).isEqualTo(JobState.PAUSED);
        assertThat(app.records.job(jobs.get(a).id()).orElseThrow().state()).isEqualTo(JobState.QUEUED);
        assertThat(app.folderService.get(alice, f.id()).paused()).isTrue();
        assertThatThrownBy(() -> put(alice, "/forward/Both/2.txt", "2"))
                .isInstanceOf(FdropException.class).hasMessageContaining("Partner A DR");

        // the owner removes the unassigned destination: uploads open again, its paused job stays
        app.folderService.update(alice, f.id(), null, List.of(a), false, null);
        put(alice, "/forward/Both/2.txt", "2");
        assertThat(jobs(f.id(), "2.txt")).containsOnlyKeys(a);

        assertThatThrownBy(() -> app.destinationService.delete(admin, b))
                .isInstanceOf(FdropException.class).hasMessageContaining("waiting to be sent");
    }

    @Test
    void deletingAFolderCancelsItsUnsentJobsSoItsDestinationsCanBeDeleted() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.FORWARD, "Gone", List.of(a, b), 0);
        put(alice, "/forward/Gone/1.txt", "1");
        put(alice, "/forward/Gone/2.txt", "2");
        Map<String, ForwardJob> one = jobs(f.id(), "1.txt");
        sent(one.get(a));
        failed(one.get(b));
        app.forwarding.cancel(alice, f.id(), one.get(b).id(), "retired");
        Map<String, ForwardJob> two = jobs(f.id(), "2.txt");
        failed(two.get(a));

        app.folderService.delete(alice, f.id());
        assertThat(app.records.job(one.get(a).id()).orElseThrow().state()).isEqualTo(JobState.SENT);
        assertThat(app.records.job(one.get(b).id()).orElseThrow().cancelReason()).isEqualTo("retired");
        assertThat(app.records.job(two.get(a).id()).orElseThrow().state()).isEqualTo(JobState.CANCELLED);
        assertThat(app.records.job(two.get(b).id()).orElseThrow().cancelReason()).isEqualTo("folder deleted");
        app.destinationService.delete(admin, a);
        app.destinationService.delete(admin, b);
    }

    @Test
    void overallStatusPutsTheWorstFirst() {
        assertThat(RecordRepo.overall(List.of())).isNull();
        assertThat(RecordRepo.overall(List.of(JobState.SENT, JobState.FAILED, JobState.PAUSED)))
                .isEqualTo(RecordStatus.FAILED);
        assertThat(RecordRepo.overall(List.of(JobState.SENT, JobState.PAUSED, JobState.SENDING)))
                .isEqualTo(RecordStatus.PAUSED);
        assertThat(RecordRepo.overall(List.of(JobState.QUEUED, JobState.RETRY_WAIT))).isEqualTo(RecordStatus.SENDING);
        assertThat(RecordRepo.overall(List.of(JobState.SENT, JobState.QUEUED))).isEqualTo(RecordStatus.QUEUED);
        assertThat(RecordRepo.overall(List.of(JobState.SENT, JobState.CANCELLED))).isEqualTo(RecordStatus.SENT);
        assertThat(RecordRepo.overall(List.of(JobState.CANCELLED))).isEqualTo(RecordStatus.CANCELLED);
    }
}
