package io.fdrop.storage.local;

import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.expiry.Expiry;
import io.fdrop.core.model.Enums.ExpirySource;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.RecordStatus;
import io.fdrop.core.model.Enums.UploadState;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Records.UploadRecord;
import io.fdrop.core.model.Records.UploadSession;
import io.fdrop.core.service.ExpiryService;
import io.fdrop.core.service.ExpirySweeper;
import io.fdrop.core.service.FileService;
import io.fdrop.core.service.FolderService;
import io.fdrop.core.service.UploadService;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** File expiry end to end through the services, on the local backend, with a clock the test moves. */
class ExpiryServicesTest {

    static final Instant T0 = Instant.parse("2026-10-26T09:14:03Z");

    @TempDir
    Path dir;

    Fdrop app;
    TestSupport.MutableClock clock;
    final Principal alice = TestSupport.user("alice");
    final Principal bob = TestSupport.user("bob");

    @BeforeEach
    void setUp() throws Exception {
        start(TestSupport.config());
    }

    private void start(FdropConfig cfg) throws Exception {
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir);
        clock = new TestSupport.MutableClock(T0);
        app = TestSupport.fdrop(cfg, b, clock);
        for (Principal p : List.of(alice, bob)) {
            app.accounts.ensure(p);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        app.close();
    }

    private void put(Principal p, String path, String expiresIn) throws Exception {
        put(p, path, expiresIn, Via.HTTP);
    }

    private void put(Principal p, String path, String expiresIn, Via via) throws Exception {
        byte[] data = ("content of " + path).getBytes(StandardCharsets.UTF_8);
        try (FileService.FileWrite w = app.files.openWrite(p, path, data.length, false, via, "203.0.113.7",
                expiresIn)) {
            w.write(ByteBuffer.wrap(data));
            w.commit();
        }
    }

    private FileService.Entry stat(Principal p, String path) {
        return app.files.stat(p, path);
    }

    private static void assertProblem(Runnable r, String type) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(FdropException.class,
                e -> assertThat(e.type()).isEqualTo(type));
    }

    private long used(Principal p) {
        return app.users.find(p.sub()).orElseThrow().usedBytes();
    }

    private UploadRecord lastRecord(Principal p) {
        return app.accounts.history(p, new RecordRepo.Filter(null, false, null, null, null, null, null, 10)).rows()
                .get(0).record();
    }

    @Test
    void anExpiredFileIsHiddenAtOnceAndSweptAfterwards() throws Exception {
        put(alice, "/home/report.pdf", "6 days");
        FileService.Entry e = stat(alice, "/home/report.pdf");
        assertThat(e.expiresAt()).isEqualTo(T0.plus(Duration.ofDays(6)).toEpochMilli());
        assertThat(e.expiresIn()).isEqualTo("6 days");
        assertThat(e.expirySource()).isEqualTo(ExpirySource.UPLOAD);
        assertThat(lastRecord(alice).expiresAt()).isEqualTo(e.expiresAt());
        long size = e.size();
        assertThat(used(alice)).isEqualTo(size);

        clock.advance(Duration.ofDays(6).minusSeconds(1));
        assertThat(app.expirySweeper.sweep()).isZero();
        assertThat(app.files.list(alice, "/home")).extracting(FileService.Entry::name).containsExactly("report.pdf");

        clock.advance(Duration.ofSeconds(1));
        assertThat(app.files.list(alice, "/home")).isEmpty();
        assertThatThrownBy(() -> stat(alice, "/home/report.pdf")).isInstanceOfSatisfying(FdropException.class,
                ex -> assertThat(ex.status()).isEqualTo(404));
        assertThatThrownBy(() -> app.files.readTarget(alice, "/home/report.pdf")).isInstanceOf(FdropException.class);
        // a new upload under the same name waits until the old file is gone
        assertThatThrownBy(() -> put(alice, "/home/report.pdf", null)).isInstanceOfSatisfying(FdropException.class,
                ex -> assertThat(ex.status()).isEqualTo(409));

        assertThat(app.expirySweeper.sweep()).isEqualTo(1);
        assertThat(Files.exists(dir.resolve("users/sub-alice/report.pdf"))).isFalse();
        assertThat(used(alice)).isZero();
        assertThat(lastRecord(alice).status()).isEqualTo(RecordStatus.EXPIRED);
        assertThat(app.expiryRepo.count()).isZero();
        assertThat(app.expirySweeper.sweep()).isZero();
        put(alice, "/home/report.pdf", null);
        assertThat(stat(alice, "/home/report.pdf").expiresAt()).isNull();
    }

    @Test
    void aReadIsCutOffAtTheExpiry() throws Exception {
        put(alice, "/home/a.txt", "1 hour");
        FileService.ReadTarget t = app.files.readTarget(alice, "/home/a.txt");
        try (ReadableByteChannel ch = app.files.openRead(t, 0, -1)) {
            clock.advance(Duration.ofHours(1));
            assertThatThrownBy(() -> ch.read(ByteBuffer.allocate(16))).isInstanceOf(IOException.class)
                    .hasMessageContaining("expired");
        }
    }

    @Test
    void folderDefaultsAndMaximums() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.SHARED, "Scratch", null, null);
        app.folderService.putMember(alice, f.id(), "bob", Permission.WRITE);
        assertThat(app.folderService.updateExpiry(alice, f.id(), "1day", "7 days", false)).isZero();
        assertThat(app.folderService.get(bob, f.id()).defaultExpiry()).isEqualTo("1 day");
        assertThat(app.folderService.get(bob, f.id()).maxExpiry()).isEqualTo("7 days");
        assertThatThrownBy(() -> app.folderService.updateExpiry(bob, f.id(), "2 days", null, false))
                .isInstanceOf(FdropException.class).hasMessageContaining("owner");

        put(bob, "/shared/Scratch/a.txt", null);
        assertThat(stat(bob, "/shared/Scratch/a.txt").expiresAt()).isEqualTo(T0.plus(Duration.ofDays(1)).toEpochMilli());
        assertThat(stat(bob, "/shared/Scratch/a.txt").expirySource()).isEqualTo(ExpirySource.FOLDER_DEFAULT);
        put(bob, "/shared/Scratch/b.txt", "2 days");
        assertThat(stat(bob, "/shared/Scratch/b.txt").expiresIn()).isEqualTo("2 days");

        assertThatThrownBy(() -> put(bob, "/shared/Scratch/c.txt", "30 days"))
                .isInstanceOfSatisfying(FdropException.class, e -> {
                    assertThat(e.type()).isEqualTo(Expiry.PROBLEM_TOO_LONG);
                    assertThat(e.getMessage()).isEqualTo("'30 days' is longer than this folder's maximum of '7 days',"
                            + " set by the folder owner");
                });
        assertThatThrownBy(() -> put(bob, "/shared/Scratch/c.txt", "never"))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("Files in this folder must expire within '7 days'"));
        assertProblem(() -> app.files.openWrite(bob, "/shared/Scratch/c.txt", 1, false, Via.HTTP, null, "6 dys"),
                Expiry.PROBLEM_INVALID);
        assertThat(app.files.list(bob, "/shared/Scratch")).hasSize(2);

        // the default can't be longer than the maximum, nor anything longer than the server's
        assertProblem(() -> app.folderService.updateExpiry(alice, f.id(), "8 days", null, false),
                Expiry.PROBLEM_TOO_LONG);
        assertProblem(() -> app.folderService.updateExpiry(alice, f.id(), null, "11 years", false),
                Expiry.PROBLEM_TOO_LONG);

        // a maximum without a default gives every file the maximum
        app.folderService.updateExpiry(alice, f.id(), "never", null, false);
        put(bob, "/shared/Scratch/d.txt", null);
        assertThat(stat(bob, "/shared/Scratch/d.txt").expiresIn()).isEqualTo("7 days");
    }

    @Test
    void homeDefaultsAndNever() throws Exception {
        put(alice, "/home/forever.txt", null);
        assertThat(stat(alice, "/home/forever.txt").expiresAt()).isNull();
        app.expiry.setHomeDefault(alice, alice.sub(), "2weeks");
        put(alice, "/home/temp.txt", null);
        assertThat(stat(alice, "/home/temp.txt").expirySource()).isEqualTo(ExpirySource.HOME_DEFAULT);
        assertThat(stat(alice, "/home/temp.txt").expiresIn()).isEqualTo("2 weeks");
        put(alice, "/home/keep.txt", "never");
        assertThat(stat(alice, "/home/keep.txt").expiresAt()).isNull();
        assertProblem(() -> app.expiry.setHomeDefault(alice, alice.sub(), "11y"), Expiry.PROBLEM_TOO_LONG);
    }

    @Test
    void serverHomeMaximumMakesEveryHomeFileExpire() throws Exception {
        app.close();
        FdropConfig cfg = TestSupport.config();
        cfg.expiry.home.max = "30 days";
        start(cfg);
        put(alice, "/home/a.txt", null);
        assertThat(stat(alice, "/home/a.txt").expiresIn()).isEqualTo("30 days");
        assertThatThrownBy(() -> put(alice, "/home/b.txt", "never")).isInstanceOfSatisfying(FdropException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Files in Home must expire within '30 days'"));
        assertThatThrownBy(() -> put(alice, "/home/b.txt", "31 days")).isInstanceOfSatisfying(FdropException.class,
                e -> assertThat(e.getMessage()).contains("for files in Home"));
    }

    @Test
    void changingAndClearingAnExpiry() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.SHARED, "Team", null, null);
        app.folderService.putMember(alice, f.id(), "bob", Permission.READ);
        put(alice, "/shared/Team/a.txt", "1 day");
        clock.advance(Duration.ofHours(2));

        ExpiryService.View v = app.expiry.change(alice, "/shared/Team/a.txt",
                new ExpiryService.Change("2weeks", null), Via.HTTP, "user");
        assertThat(v.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(14)));
        assertThat(v.source()).isEqualTo(ExpirySource.CHANGED);
        assertThat(lastRecord(alice).expiresAt()).isEqualTo(v.expiresAt().toEpochMilli());

        Instant date = Instant.parse("2026-11-30T17:00:00Z");
        assertThat(app.expiry.change(alice, "/shared/Team/a.txt", new ExpiryService.Change(null, date), Via.HTTP,
                "user").expiresAt()).isEqualTo(date);
        assertProblem(() -> app.expiry.change(alice, "/shared/Team/a.txt",
                new ExpiryService.Change(null, T0.minusSeconds(1)), Via.HTTP, "user"), Expiry.PROBLEM_INVALID);

        // read-only members see the expiry but cannot change it
        assertThat(stat(bob, "/shared/Team/a.txt").expiresAt()).isEqualTo(date.toEpochMilli());
        assertThatThrownBy(() -> app.expiry.change(bob, "/shared/Team/a.txt", ExpiryService.Change.clear(),
                Via.HTTP, "user")).isInstanceOfSatisfying(FdropException.class,
                e -> assertThat(e.status()).isEqualTo(403));

        assertThat(app.expiry.change(alice, "/shared/Team/a.txt", ExpiryService.Change.clear(), Via.HTTP, "user"))
                .isNull();
        assertThat(stat(alice, "/shared/Team/a.txt").expiresAt()).isNull();
        assertThat(lastRecord(alice).expiresAt()).isNull();
    }

    @Test
    void movingIntoAFolderWithAShorterMaximumShortensTheExpiry() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.SHARED, "Short", null, null);
        app.folderService.updateExpiry(alice, f.id(), null, "7 days", false);
        put(alice, "/home/a.txt", null);
        put(alice, "/home/b.txt", "1 day");
        FileService.Moved m = app.files.moveWithExpiry(alice, "/home/a.txt", "/shared/Short/a.txt");
        assertThat(m.expiryShortened()).isTrue();
        assertThat(m.entry().expiresAt()).isEqualTo(T0.plus(Duration.ofDays(7)).toEpochMilli());
        FileService.Moved kept = app.files.moveWithExpiry(alice, "/home/b.txt", "/shared/Short/b.txt");
        assertThat(kept.expiryShortened()).isFalse();
        assertThat(kept.entry().expiresAt()).isEqualTo(T0.plus(Duration.ofDays(1)).toEpochMilli());
        // the expiry follows a rename
        app.files.move(alice, "/shared/Short/b.txt", "/shared/Short/c.txt");
        assertThat(stat(alice, "/shared/Short/c.txt").expiresAt()).isEqualTo(T0.plus(Duration.ofDays(1)).toEpochMilli());
        // and goes with a delete
        app.files.delete(alice, "/shared/Short/c.txt", false);
        assertThat(app.expiryRepo.count()).isEqualTo(1);
    }

    @Test
    void loweringAMaximumCanShortenExistingFiles() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.SHARED, "Old", null, null);
        put(alice, "/shared/Old/none.txt", null);
        put(alice, "/shared/Old/long.txt", "30 days");
        put(alice, "/shared/Old/short.txt", "1 day");
        assertThat(app.folderService.updateExpiry(alice, f.id(), null, "7 days", false)).isZero();
        assertThat(stat(alice, "/shared/Old/none.txt").expiresAt()).isNull();
        assertThat(app.folderService.updateExpiry(alice, f.id(), null, "7 days", true)).isEqualTo(2);
        long week = T0.plus(Duration.ofDays(7)).toEpochMilli();
        assertThat(stat(alice, "/shared/Old/none.txt").expiresAt()).isEqualTo(week);
        assertThat(stat(alice, "/shared/Old/long.txt").expiresAt()).isEqualTo(week);
        assertThat(stat(alice, "/shared/Old/short.txt").expiresAt()).isEqualTo(T0.plus(Duration.ofDays(1)).toEpochMilli());
    }

    @Test
    void resumableUploadsStartTheClockAtCompletion() throws Exception {
        byte[] data = "resumable".getBytes(StandardCharsets.UTF_8);
        assertProblem(() -> app.uploadService.create(alice, new UploadService.CreateRequest("/home/r.bin",
                data.length, null, null, false, "1 decade"), "198.51.100.1"), Expiry.PROBLEM_INVALID);
        UploadSession s = app.uploadService.create(alice, new UploadService.CreateRequest("/home/r.bin", data.length,
                null, Hashing.sha256Hex(data), false, "1hour"), "198.51.100.1");
        assertThat(s.expiresIn()).isEqualTo("1 hour");
        app.uploadService.putChunk(alice, s.id(), 0, Channels.newChannel(new ByteArrayInputStream(data)), data.length,
                null);
        clock.advance(Duration.ofMinutes(30));
        assertThat(app.uploadService.complete(alice, s.id(), "198.51.100.1").state()).isEqualTo(UploadState.COMPLETED);
        assertThat(stat(alice, "/home/r.bin").expiresAt())
                .isEqualTo(T0.plus(Duration.ofMinutes(90)).toEpochMilli());
    }

    @Test
    void reservedControlNamesCannotBeStored() {
        assertProblem(() -> app.files.openWrite(alice, "/home/a.pdf.fdrop-expires", 1, false, Via.HTTP, null),
                "reserved-name");
        assertProblem(() -> app.files.mkdir(alice, "/home/x.FDROP-EXPIRES"), "reserved-name");
    }

    @Test
    void sftpControlValuesBeforeAndAfterTheUpload() throws Exception {
        // before: held as pending and applied when the file is completed over SFTP
        app.expiry.controlWrite(alice, "/home/report.pdf", "6 days\n");
        assertThat(app.expiry.controlRead(alice, "/home/report.pdf")).isEqualTo("pending 6 days\n");
        clock.advance(Duration.ofMinutes(1));
        put(alice, "/home/report.pdf", null, Via.SFTP);
        Instant at = T0.plus(Duration.ofMinutes(1)).plus(Duration.ofDays(6));
        assertThat(stat(alice, "/home/report.pdf").expiresAt()).isEqualTo(at.toEpochMilli());
        assertThat(app.expiry.controlRead(alice, "/home/report.pdf")).isEqualTo(at + " 6 days\n");

        // after: changes the expiry from now; removing the control file clears it
        app.expiry.controlWrite(alice, "/home/report.pdf", "1 day");
        assertThat(stat(alice, "/home/report.pdf").expiresIn()).isEqualTo("1 day");
        app.expiry.controlClear(alice, "/home/report.pdf");
        assertThat(app.expiry.controlRead(alice, "/home/report.pdf")).isEqualTo("never\n");

        // a pending value lapses after expiry.sftp.pendingFor
        app.expiry.controlWrite(alice, "/home/late.pdf", "2 days");
        clock.advance(Duration.ofMinutes(11));
        put(alice, "/home/late.pdf", null, Via.SFTP);
        assertThat(stat(alice, "/home/late.pdf").expiresAt()).isNull();

        assertProblem(() -> app.expiry.controlWrite(alice, "/home/x.pdf", "5m"), Expiry.PROBLEM_INVALID);
        assertThatThrownBy(() -> app.expiry.controlRead(alice, "/home/nothing.pdf"))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void twoSweepersDeleteEachFileOnce() throws Exception {
        for (int i = 0; i < 20; i++) {
            put(alice, "/home/f" + i + ".txt", "1 sec");
        }
        clock.advance(Duration.ofSeconds(2));
        ExpirySweeper other = new ExpirySweeper(app.config, app.db, app.expiryRepo, app.records, app.users,
                app.folders, app.storage, app.expiry, app.metrics, clock);
        try {
            var a = Thread.ofVirtual().start(() -> app.expirySweeper.sweep());
            int b = other.sweep();
            a.join();
            assertThat(app.expiryRepo.count()).isZero();
            assertThat(app.files.list(alice, "/home")).isEmpty();
            assertThat(used(alice)).isZero();
            assertThat(b).isBetween(0, 20);
        } finally {
            other.close();
        }
    }

    @Test
    void aFileDeletedBehindFdropsBackIsCountedAsAlreadyGone() throws Exception {
        put(alice, "/home/gone.txt", "1 min");
        Files.delete(dir.resolve("users/sub-alice/gone.txt"));
        clock.advance(Duration.ofMinutes(1));
        assertThat(app.expirySweeper.sweep()).isEqualTo(1);
        assertThat(app.expiryRepo.find(StoragePath.of("users/sub-alice/gone.txt").toString())).isEmpty();
        assertThat(app.metrics.scrape(null)).contains("outcome=\"already_gone\"");
    }
}
