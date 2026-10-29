package io.fdrop.sftp;

import io.fdrop.core.FakeDirectory;
import io.fdrop.core.FakeOidc;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.RetrievalState;
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.model.Records.RemoteRetrieval;
import io.fdrop.core.service.DestinationService;
import io.fdrop.core.service.FileService;
import io.fdrop.core.service.FolderService;
import io.fdrop.core.service.RemoteBrowseService;
import io.fdrop.core.service.RemoteBrowseService.CameFrom;
import io.fdrop.core.service.RemoteBrowseService.EntryView;
import io.fdrop.core.service.SshTools;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.storage.local.LocalStorageBackend;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Destination tabs end to end: listing and retrieving from an in-process SFTP destination (spec/folders.md). */
class RemoteBrowseTest {

    @TempDir
    Path dir;

    Fdrop app;
    ForwardClient client;
    ForwardWorker worker;
    SshServer destination;
    Path remoteRoot;
    Path inbox;
    final AtomicReference<PublicKey> allowedKey = new AtomicReference<>();
    final Principal admin = TestSupport.admin("root");
    final Principal alice = TestSupport.user("alice");
    final Principal bob = TestSupport.user("bob");
    final Principal carol = TestSupport.user("carol");

    @BeforeEach
    void start() throws Exception {
        remoteRoot = Files.createDirectories(dir.resolve("remote"));
        inbox = Files.createDirectories(remoteRoot.resolve("inbox/invoices"));
        destination = SshServer.setUpDefaultServer();
        destination.setHost("127.0.0.1");
        destination.setPort(0);
        destination.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("dest_host_key")));
        destination.setPublickeyAuthenticator((user, key, session) -> allowedKey.get() != null
                && KeyUtils.compareKeys(allowedKey.get(), key));
        destination.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        destination.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        destination.start();

        FdropConfig cfg = TestSupport.config();
        cfg.forward.allowedHosts.add("127.0.0.1");
        cfg.forward.browse.keepFor = "1s";
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("data"));
        client = new ForwardClient(Duration.ofSeconds(10));
        app = new Fdrop(cfg, b, new MinaSshTools(client), FakeOidc::new);
        app.browse.setRemote(new MinaRemoteFiles(client));
        app.twoFactor.useDirectory(new FakeDirectory());
        for (Principal p : List.of(admin, alice, bob, carol)) {
            app.accounts.ensure(p);
        }
        worker = new ForwardWorker(app, client);
    }

    @AfterEach
    void stop() throws Exception {
        worker.close();
        client.close();
        destination.stop(true);
        app.close();
    }

    /** A destination on /inbox/invoices, assigned to alice, with Browsing as given. */
    String destination(boolean browsable) {
        int port = ((InetSocketAddress) destination.getBoundAddresses().iterator().next()).getPort();
        SshTools.TestResult probe = app.destinationService.probe(admin, "127.0.0.1", port);
        DestinationService.AdminView d = app.destinationService.create(admin, new DestinationService.DestinationInput(
                "Partner A", null, "127.0.0.1", port, "/inbox/invoices", "fdrop", ServerAuth.HOST_KEY,
                probe.hostKeyFingerprint(), null, false, true, browsable));
        allowedKey.set(MinaSshTools.parse(d.keys().get(0).publicKey()));
        app.destinationService.assign(admin, d.destination().id(), "alice");
        return d.destination().id();
    }

    FolderService.FolderView folder(String destId, int keepDays) {
        return app.folderService.create(alice, FolderType.FORWARD, "Out", List.of(destId), keepDays);
    }

    /** Uploads a file to the folder and sends it. */
    ForwardJob send(FolderService.FolderView f, String name, String content) throws Exception {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        try (FileService.FileWrite w = app.files.openWrite(alice, "/forward/" + f.name() + "/" + name, data.length,
                false, Via.HTTP, "127.0.0.1")) {
            w.write(ByteBuffer.wrap(data));
            w.commit();
        }
        ForwardJob j = app.folderService.jobs(alice, f.id(), 10).get(0);
        assertThat(app.records.claim(j.id(), j.state())).isTrue();
        worker.process(j);
        ForwardJob sent = app.records.job(j.id()).orElseThrow();
        assertThat(sent.state()).as(String.valueOf(sent.lastError())).isEqualTo(JobState.SENT);
        return sent;
    }

    RemoteRetrieval await(String folderId, RemoteRetrieval r) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        RemoteRetrieval now = r;
        while ((now.state() == RetrievalState.QUEUED || now.state() == RetrievalState.RETRIEVING)
                && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            now = app.browse.retrieval(alice, folderId, r.id());
        }
        return now;
    }

    String read(Principal p, String folderId, String retrievalId) throws Exception {
        RemoteBrowseService.Content c = app.browse.content(p, folderId, retrievalId);
        try (InputStream in = Channels.newInputStream(app.browse.openRead(c, 0, -1))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void listsTheRemoteDirectorySayingWhereEachFileCameFrom() throws Exception {
        String d = destination(true);
        FolderService.FolderView f = folder(d, 0);
        assertThat(f.destinations().get(0).browsable()).isTrue();
        send(f, "inv-1.pdf", "one");
        send(f, "inv-2.pdf", "two");
        Files.writeString(inbox.resolve("inv-2.pdf"), "changed by the partner");
        Files.writeString(inbox.resolve("remittance.csv"), "a,b");
        Files.writeString(inbox.resolve(".inv-3.pdf.fdrop-part"), "partial");
        Files.createDirectories(inbox.resolve("archive"));
        Files.writeString(inbox.resolve("archive/old.txt"), "old");

        RemoteBrowseService.Listing l = app.browse.list(alice, f.id(), d, "", false);
        assertThat(l.remoteDir()).isEqualTo("/inbox/invoices");
        assertThat(l.entries()).extracting(EntryView::name)
                .containsExactly("archive", "inv-1.pdf", "inv-2.pdf", "remittance.csv");
        Map<String, CameFrom> from = l.entries().stream().filter(e -> !e.directory())
                .collect(Collectors.toMap(EntryView::name, EntryView::cameFrom));
        assertThat(from).containsEntry("inv-1.pdf", CameFrom.SENT_BY_FDROP)
                .containsEntry("inv-2.pdf", CameFrom.CHANGED_SINCE_SENT)
                .containsEntry("remittance.csv", CameFrom.NOT_FROM_FDROP);
        assertThat(l.entries().get(0).directory()).isTrue();

        RemoteBrowseService.Listing sub = app.browse.list(alice, f.id(), d, "archive", false);
        assertThat(sub.path()).isEqualTo("archive");
        assertThat(sub.entries()).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("archive/old.txt");
            assertThat(e.cameFrom()).isEqualTo(CameFrom.NOT_FROM_FDROP);
            assertThat(e.size()).isEqualTo(3);
        });

        // read-only members browse too; others don't see the folder at all
        app.folderService.putMember(alice, f.id(), "bob", Permission.READ);
        assertThat(app.browse.list(bob, f.id(), d, "", false).entries()).hasSize(4);
        assertThatThrownBy(() -> app.browse.list(carol, f.id(), d, "", false))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void retrievesAFileOnceAndAgainOnlyWhenItChanges() throws Exception {
        String d = destination(true);
        FolderService.FolderView f = folder(d, 0);
        Files.writeString(inbox.resolve("remittance.csv"), "a,b,c");

        RemoteRetrieval r = await(f.id(), app.browse.retrieve(alice, f.id(), d, "remittance.csv"));
        assertThat(r.state()).as(String.valueOf(r.error())).isEqualTo(RetrievalState.READY);
        assertThat(r.storageKey()).startsWith("retrieved/" + f.id() + "/");
        assertThat(read(alice, f.id(), r.id())).isEqualTo("a,b,c");
        assertThat(app.users.find(alice.sub()).orElseThrow().usedBytes()).isEqualTo(5);
        // the copy sits apart from the folder's own files
        assertThat(app.files.list(alice, "/forward/Out")).isEmpty();
        assertThat(app.browse.list(alice, f.id(), d, "", true).entries()).singleElement()
                .satisfies(e -> assertThat(e.retrieval().id()).isEqualTo(r.id()));

        // the same version again is the same copy, without another transfer
        assertThat(app.browse.retrieve(alice, f.id(), d, "remittance.csv").id()).isEqualTo(r.id());

        // a changed file is retrieved again
        Files.writeString(inbox.resolve("remittance.csv"), "a,b,c,d,e");
        RemoteRetrieval again = await(f.id(), app.browse.retrieve(alice, f.id(), d, "remittance.csv"));
        assertThat(again.id()).isNotEqualTo(r.id());
        assertThat(read(alice, f.id(), again.id())).isEqualTo("a,b,c,d,e");
        assertThat(inbox.resolve("remittance.csv")).hasContent("a,b,c,d,e");
    }

    @Test
    void reusesTheFoldersOwnCopyWhileItIsKept() throws Exception {
        String d = destination(true);
        FolderService.FolderView f = folder(d, 3);
        ForwardJob sent = send(f, "kept.pdf", "kept bytes");
        long used = app.users.find(alice.sub()).orElseThrow().usedBytes();

        RemoteRetrieval r = app.browse.retrieve(alice, f.id(), d, "kept.pdf");
        assertThat(r.state()).isEqualTo(RetrievalState.READY);
        assertThat(r.ownCopy()).isTrue();
        assertThat(r.storageKey()).isEqualTo(sent.storageKey());
        assertThat(read(alice, f.id(), r.id())).isEqualTo("kept bytes");
        assertThat(app.users.find(alice.sub()).orElseThrow().usedBytes()).isEqualTo(used);

        // sweeping it away leaves the folder's own file alone
        Thread.sleep(1100);
        app.browse.sweep();
        assertThat(app.storage.stat(StoragePath.of(sent.storageKey()))).isPresent();
        assertThatThrownBy(() -> app.browse.retrieval(alice, f.id(), r.id())).isInstanceOf(FdropException.class);
    }

    @Test
    void sweepDeletesExpiredCopiesAndGivesBackTheQuota() throws Exception {
        String d = destination(true);
        FolderService.FolderView f = folder(d, 0);
        Files.writeString(inbox.resolve("a.txt"), "12345678");
        RemoteRetrieval r = await(f.id(), app.browse.retrieve(alice, f.id(), d, "a.txt"));
        assertThat(r.state()).isEqualTo(RetrievalState.READY);
        assertThat(app.users.find(alice.sub()).orElseThrow().usedBytes()).isEqualTo(8);

        Thread.sleep(1100);
        app.browse.sweep();
        assertThat(app.storage.stat(StoragePath.of(r.storageKey()))).isEmpty();
        assertThat(app.users.find(alice.sub()).orElseThrow().usedBytes()).isZero();
    }

    @Test
    void refusesWhatItMustNot() throws Exception {
        String off = destination(false);
        FolderService.FolderView f = folder(off, 0);
        assertThatThrownBy(() -> app.browse.list(alice, f.id(), off, "", false))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(403));

        app.destinationService.update(admin, off, new DestinationService.DestinationInput(null, null, null, null,
                null, null, null, null, null, null, null, true));
        assertThatThrownBy(() -> app.browse.list(alice, f.id(), off, "../..", false))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> app.browse.retrieve(alice, f.id(), off, ""))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> app.browse.retrieve(alice, f.id(), off, "missing.pdf"))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(404));

        // a symbolic link out of the remote directory is listed as what it points to, but never opened
        Files.createDirectories(remoteRoot.resolve("secret"));
        Files.writeString(remoteRoot.resolve("secret/keys.txt"), "secret");
        Files.createSymbolicLink(inbox.resolve("escape"), remoteRoot.resolve("secret"));
        assertThatThrownBy(() -> app.browse.list(alice, f.id(), off, "escape", false))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> app.browse.retrieve(alice, f.id(), off, "escape/keys.txt"))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThat(app.browse.list(alice, f.id(), off, "", true).entries()).extracting(EntryView::name)
                .doesNotContain("escape");
        // a link that stays inside is followed
        Files.createDirectories(inbox.resolve("2026"));
        Files.writeString(inbox.resolve("2026/x.txt"), "x");
        Files.createSymbolicLink(inbox.resolve("latest"), Path.of("2026"));
        assertThat(app.browse.list(alice, f.id(), off, "", true).entries()).filteredOn(e -> e.name().equals("latest"))
                .singleElement().satisfies(e -> assertThat(e.directory()).isTrue());
        assertThat(app.browse.list(alice, f.id(), off, "latest", false).entries()).extracting(EntryView::name)
                .containsExactly("x.txt");

        // a disabled destination is not available
        app.destinationService.update(admin, off, new DestinationService.DestinationInput(null, null, null, null,
                null, null, null, null, null, null, false));
        assertThatThrownBy(() -> app.browse.list(alice, f.id(), off, "", false))
                .isInstanceOfSatisfying(FdropException.class, e -> assertThat(e.status()).isEqualTo(409));
    }
}
