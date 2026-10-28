package io.fdrop.storage.local;

import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.DataSize;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.RecordStatus;
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.model.Enums.UploadState;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Enums.WentTo;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.model.Records.UploadSession;
import io.fdrop.core.service.AccountService;
import io.fdrop.core.service.DestinationService;
import io.fdrop.core.service.FileService;
import io.fdrop.core.service.FolderService;
import io.fdrop.core.service.UploadService;
import io.fdrop.core.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServicesTest {

    @TempDir
    Path dir;

    Fdrop app;
    final Principal alice = TestSupport.user("alice");
    final Principal bob = TestSupport.user("bob");
    final Principal carol = TestSupport.user("carol");
    final Principal admin = TestSupport.admin("root");

    @BeforeEach
    void setUp() throws Exception {
        FdropConfig cfg = TestSupport.config();
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir);
        app = TestSupport.fdrop(cfg, b);
        for (Principal p : List.of(alice, bob, carol, admin)) {
            app.accounts.ensure(p);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        app.close();
    }

    private void put(Principal p, String path, byte[] data) throws Exception {
        try (FileService.FileWrite w = app.files.openWrite(p, path, data.length, false, Via.HTTP, "203.0.113.7")) {
            w.write(ByteBuffer.wrap(data));
            w.commit();
        }
    }

    private byte[] get(Principal p, String path) throws Exception {
        FileService.ReadTarget t = app.files.readTarget(p, path);
        try (ReadableByteChannel ch = app.files.openRead(t, 0, -1)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Channels.newInputStream(ch).transferTo(out);
            return out.toByteArray();
        }
    }

    @Test
    void homeFilesQuotaAndHistory() throws Exception {
        put(alice, "/home/notes.txt", "hello".getBytes(StandardCharsets.UTF_8));
        app.files.mkdir(alice, "/home/docs");
        put(alice, "/home/docs/a.bin", new byte[1000]);
        assertThat(app.files.list(alice, "/home")).extracting(FileService.Entry::name)
                .containsExactly("docs", "notes.txt");
        assertThat(app.users.find(alice.sub()).orElseThrow().usedBytes()).isEqualTo(1005);

        app.files.move(alice, "/home/notes.txt", "/home/docs/notes.txt");
        assertThat(get(alice, "/home/docs/notes.txt")).asString(StandardCharsets.UTF_8).isEqualTo("hello");

        // bob cannot see alice's home: /home is always the caller's own
        assertThat(app.files.list(bob, "/home")).isEmpty();

        assertThatThrownBy(() -> app.files.delete(alice, "/home/docs", false))
                .isInstanceOf(FdropException.class).hasMessageContaining("not empty");
        app.files.delete(alice, "/home/docs/a.bin", false);
        assertThat(app.users.find(alice.sub()).orElseThrow().usedBytes()).isEqualTo(5);

        AccountService.HistoryPage page = app.accounts.history(alice, new RecordRepo.Filter(null, false, null, null,
                null, null, null, 50));
        assertThat(page.totalCount()).isEqualTo(2);
        assertThat(page.rows()).extracting(r -> r.record().status())
                .containsExactlyInAnyOrder(RecordStatus.MOVED, RecordStatus.DELETED);
        assertThat(page.rows()).allSatisfy(r -> assertThat(r.record().clientIp()).isEqualTo("203.0.113.7"));

        AccountService.HistoryPage byIp = app.accounts.history(alice, new RecordRepo.Filter(null, false, "203.0.",
                null, null, null, null, 50));
        assertThat(byIp.totalCount()).isEqualTo(2);
        assertThat(app.accounts.history(bob, new RecordRepo.Filter(null, false, null, null, null, null, null, 50))
                .totalCount()).isZero();
    }

    @Test
    void quotaAndSizeLimits() {
        app.config.limits.maxFileSize = DataSize.ofBytes(100);
        assertThatThrownBy(() -> put(alice, "/home/too-big", new byte[101]))
                .isInstanceOf(FdropException.class).extracting(e -> ((FdropException) e).status()).isEqualTo(413);
        app.config.limits.maxFileSize = DataSize.ofGiB(1);
        assertThatThrownBy(() -> put(alice, "/home/over-quota", new byte[101 * 1024 * 1024]))
                .isInstanceOf(FdropException.class).extracting(e -> ((FdropException) e).status()).isEqualTo(507);
    }

    @Test
    void sharedFolderPermissions() throws Exception {
        FolderService.FolderView f = app.folderService.create(alice, FolderType.SHARED, "Invoices", null, null);
        app.folderService.putMember(alice, f.id(), "bob", Permission.READ);
        put(alice, "/shared/Invoices/inv-1.pdf", "pdf".getBytes(StandardCharsets.UTF_8));

        assertThat(app.files.list(bob, "/shared")).extracting(FileService.Entry::name).containsExactly("Invoices");
        assertThat(get(bob, "/shared/invoices/inv-1.pdf")).hasSize(3);
        assertThatThrownBy(() -> put(bob, "/shared/Invoices/bob.txt", new byte[1]))
                .isInstanceOf(FdropException.class).extracting(e -> ((FdropException) e).status()).isEqualTo(403);

        // carol is not a member, and neither is the admin: both get "not found"
        assertThat(app.files.list(carol, "/shared")).isEmpty();
        assertThatThrownBy(() -> get(carol, "/shared/Invoices/inv-1.pdf")).isInstanceOf(FdropException.class)
                .extracting(e -> ((FdropException) e).status()).isEqualTo(404);
        assertThatThrownBy(() -> get(admin, "/shared/Invoices/inv-1.pdf")).isInstanceOf(FdropException.class)
                .extracting(e -> ((FdropException) e).status()).isEqualTo(404);

        app.folderService.putMember(alice, f.id(), "bob", Permission.WRITE);
        put(bob, "/shared/Invoices/bob.txt", new byte[10]);
        // uploads into a shared folder count against the owner's quota
        assertThat(app.users.find(alice.sub()).orElseThrow().usedBytes()).isEqualTo(13);
        assertThat(app.users.find(bob.sub()).orElseThrow().usedBytes()).isZero();

        app.folderService.removeMember(alice, f.id(), "bob");
        assertThatThrownBy(() -> app.files.list(bob, "/shared/Invoices")).isInstanceOf(FdropException.class);

        assertThatThrownBy(() -> app.folderService.create(bob, FolderType.SHARED, "invoices", null, null))
                .isInstanceOf(FdropException.class).hasMessageContaining("already exists");
    }

    @Test
    void resumableUploadOutOfOrderWithDuplicatesAndHash() throws Exception {
        byte[] data = new byte[11 * 1024 * 1024];
        new Random(42).nextBytes(data);
        UploadSession s = app.uploadService.create(alice, new UploadService.CreateRequest("/home/big.iso",
                data.length, 1L, Hashing.sha256Hex(data), false), "198.51.100.1");
        assertThat(s.chunkCount()).isEqualTo(2);
        long cs = s.chunkSize();
        byte[] c0 = Arrays.copyOfRange(data, 0, (int) cs);
        byte[] c1 = Arrays.copyOfRange(data, (int) cs, data.length);

        // wrong hash is refused and stores nothing
        assertThatThrownBy(() -> app.uploadService.putChunk(alice, s.id(), 1, ch(c1), c1.length, "00".repeat(32)))
                .isInstanceOf(FdropException.class);
        assertThat(app.uploadService.putChunk(alice, s.id(), 1, ch(c1), c1.length, Hashing.sha256Hex(c1)))
                .isEqualTo(UploadService.ChunkResult.STORED);
        // completing with a chunk missing is a 409 and the session stays open
        assertThatThrownBy(() -> app.uploadService.complete(alice, s.id(), "198.51.100.2"))
                .isInstanceOf(FdropException.class).hasMessageContaining("missing");
        assertThat(app.uploadService.status(alice, s.id()).received()).hasSize(1);

        assertThat(app.uploadService.putChunk(alice, s.id(), 0, ch(c0), c0.length, null))
                .isEqualTo(UploadService.ChunkResult.STORED);
        assertThat(app.uploadService.putChunk(alice, s.id(), 0, ch(c0), c0.length, Hashing.sha256Hex(c0)))
                .isEqualTo(UploadService.ChunkResult.DUPLICATE);
        assertThatThrownBy(() -> app.uploadService.putChunk(alice, s.id(), 0, ch(c1), c0.length,
                Hashing.sha256Hex(c1))).isInstanceOf(FdropException.class);

        UploadSession done = app.uploadService.complete(alice, s.id(), "198.51.100.2");
        assertThat(done.state()).isEqualTo(UploadState.COMPLETED);
        assertThat(get(alice, "/home/big.iso")).isEqualTo(data);
        var rec = app.accounts.history(alice, new RecordRepo.Filter(null, false, null, null, null, null, null, 10))
                .rows().get(0).record();
        assertThat(rec.clientIp()).isEqualTo("198.51.100.2");
        assertThat(rec.startIp()).isEqualTo("198.51.100.1");
        assertThat(rec.size()).isEqualTo(data.length);

        // other users cannot see the session
        assertThatThrownBy(() -> app.uploadService.status(bob, s.id())).isInstanceOf(FdropException.class);
    }

    @Test
    void forwardFolderQueuesJobsAndPausesWithoutAssignment() throws Exception {
        DestinationService.AdminView d = app.destinationService.create(admin, new DestinationService.DestinationInput(
                "Partner A", "inbound invoices", "sftp.test.invalid", 22, "/inbox", "fdrop", ServerAuth.HOST_KEY,
                "SHA256:abc", null, false, true));
        assertThat(d.keys()).hasSize(1);
        assertThatThrownBy(() -> app.folderService.create(alice, FolderType.FORWARD, "To Partner A",
                List.of(d.destination().id()), 0)).isInstanceOf(FdropException.class).hasMessageContaining("not assigned");

        app.destinationService.assign(admin, d.destination().id(), "alice");
        assertThat(app.destinationService.forUser(alice)).extracting(DestinationService.UserView::name)
                .containsExactly("Partner A");
        assertThat(app.destinationService.forUser(bob)).isEmpty();
        FolderService.FolderView f = app.folderService.create(alice, FolderType.FORWARD, "To Partner A",
                List.of(d.destination().id()), 0);

        assertThatThrownBy(() -> app.files.mkdir(alice, "/forward/To Partner A/sub")).isInstanceOf(FdropException.class);
        put(alice, "/forward/To Partner A/invoice.pdf", "x".getBytes(StandardCharsets.UTF_8));
        List<ForwardJob> jobs = app.folderService.jobs(alice, f.id(), 10);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).state()).isEqualTo(JobState.QUEUED);
        var history = app.accounts.history(alice, new RecordRepo.Filter(WentTo.FORWARD, false, null, null, null, null,
                null, 10));
        assertThat(history.rows()).hasSize(1);
        assertThat(history.rows().get(0).destinationName()).isEqualTo("Partner A");
        assertThat(history.rows().get(0).record().status()).isEqualTo(RecordStatus.QUEUED);

        app.destinationService.unassign(admin, d.destination().id(), "alice");
        assertThat(app.records.job(jobs.get(0).id()).orElseThrow().state()).isEqualTo(JobState.PAUSED);
        assertThatThrownBy(() -> put(alice, "/forward/To Partner A/two.pdf", new byte[1]))
                .isInstanceOf(FdropException.class).hasMessageContaining("paused");
        assertThatThrownBy(() -> app.destinationService.delete(admin, d.destination().id()))
                .isInstanceOf(FdropException.class).hasMessageContaining("disable it instead");

        app.destinationService.assign(admin, d.destination().id(), "alice");
        assertThat(app.records.job(jobs.get(0).id()).orElseThrow().state()).isEqualTo(JobState.QUEUED);

        // standard users cannot manage destinations
        assertThatThrownBy(() -> app.destinationService.list(alice)).isInstanceOf(FdropException.class);
    }

    @Test
    void aUsernameMovesToTheKeycloakAccountNowUsingIt() {
        // the realm was imported again (or the user deleted and recreated): same username, new Keycloak id
        app.accounts.ensure(new Principal("sub-old-dave", "dave", null, true, false));
        app.accounts.ensure(new Principal("sub-new-dave", "dave", null, true, false));
        assertThat(app.users.findByUsername("dave").orElseThrow().sub()).isEqualTo("sub-new-dave");
        assertThat(app.users.find("sub-old-dave").orElseThrow().username()).isEqualTo("dave~sub-old-");
        // signing in again changes nothing
        app.accounts.ensure(new Principal("sub-new-dave", "dave", null, true, false));
        assertThat(app.users.find("sub-old-dave").orElseThrow().username()).isEqualTo("dave~sub-old-");
    }

    private static ReadableByteChannel ch(byte[] b) {
        return Channels.newChannel(new ByteArrayInputStream(b));
    }
}
