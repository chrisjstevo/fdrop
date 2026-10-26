package io.fdrop.sftp;

import io.fdrop.core.FakeDirectory;
import io.fdrop.core.FakeOidc;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.JobState;
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.service.DestinationService;
import io.fdrop.core.service.FileService;
import io.fdrop.core.service.FolderService;
import io.fdrop.core.service.SshTools;
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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Forward folders end to end against an in-process SFTP destination with public-key auth only. */
class ForwardWorkerTest {

    @TempDir
    Path dir;

    Fdrop app;
    ForwardClient client;
    ForwardWorker worker;
    SshServer destination;
    Path remoteRoot;
    final AtomicReference<PublicKey> allowedKey = new AtomicReference<>();
    final Principal admin = TestSupport.admin("root");
    final Principal alice = TestSupport.user("alice");

    @BeforeEach
    void start() throws Exception {
        remoteRoot = Files.createDirectories(dir.resolve("remote"));
        destination = SshServer.setUpDefaultServer();
        destination.setHost("127.0.0.1");
        destination.setPort(0);
        destination.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("dest_host_key")));
        destination.setPublickeyAuthenticator((user, key, session) -> allowedKey.get() != null
                && KeyUtils.compareKeys(allowedKey.get(), key));
        destination.setPasswordAuthenticator((user, pw, session) -> true);
        destination.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        destination.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        destination.start();

        FdropConfig cfg = TestSupport.config();
        cfg.forward.allowedHosts.add("127.0.0.1");
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("data"));
        client = new ForwardClient(Duration.ofSeconds(10));
        app = new Fdrop(cfg, b, new MinaSshTools(client), FakeOidc::new);
        app.twoFactor.useDirectory(new FakeDirectory());
        app.accounts.ensure(admin);
        app.accounts.ensure(alice);
        worker = new ForwardWorker(app, client);
        worker.backoffOverride = 0;
    }

    @AfterEach
    void stop() throws Exception {
        worker.close();
        client.close();
        destination.stop(true);
        app.close();
    }

    int port() {
        return ((InetSocketAddress) destination.getBoundAddresses().iterator().next()).getPort();
    }

    /** Probe, pin, create, assign; install fdrop's key on the destination. */
    DestinationService.AdminView destination(String pin) {
        SshTools.TestResult probe = app.destinationService.probe(admin, "127.0.0.1", port());
        assertThat(probe.connected()).isTrue();
        assertThat(probe.hostKeyFingerprint()).startsWith("SHA256:");
        DestinationService.AdminView d = app.destinationService.create(admin, new DestinationService.DestinationInput(
                "Partner A", "invoices", "127.0.0.1", port(), "/inbox/invoices", "fdrop", ServerAuth.HOST_KEY,
                pin == null ? probe.hostKeyFingerprint() : pin, null, false, true));
        allowedKey.set(MinaSshTools.parse(d.keys().get(0).publicKey()));
        app.destinationService.assign(admin, d.destination().id(), "alice");
        return d;
    }

    ForwardJob drop(DestinationService.AdminView d, String folder, String name, String content) throws Exception {
        FolderService.FolderView f = app.folderService.list(alice).stream().filter(x -> x.name().equals(folder))
                .findFirst().orElseGet(() -> app.folderService.create(alice, FolderType.FORWARD, folder,
                        d.destination().id(), 0));
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        try (FileService.FileWrite w = app.files.openWrite(alice, "/forward/" + folder + "/" + name, data.length,
                false, Via.HTTP, "127.0.0.1")) {
            w.write(ByteBuffer.wrap(data));
            w.commit();
        }
        return app.folderService.jobs(alice, f.id(), 10).get(0);
    }

    ForwardJob run(ForwardJob j) {
        assertThat(app.records.claim(j.id(), app.records.job(j.id()).orElseThrow().state())).isTrue();
        worker.process(j);
        return app.records.job(j.id()).orElseThrow();
    }

    @Test
    void sendsUnderTempNameThenRenamesAndDeletesLocalCopy() throws Exception {
        DestinationService.AdminView d = destination(null);
        SshTools.TestResult test = app.destinationService.test(admin, d.destination().id(), null);
        assertThat(test.authenticated()).as(String.valueOf(test.error())).isTrue();

        ForwardJob j = drop(d, "Out", "invoice-1.pdf", "pdf bytes");
        ForwardJob after = run(j);
        assertThat(after.state()).as(String.valueOf(after.lastError())).isEqualTo(JobState.SENT);
        Path sent = remoteRoot.resolve("inbox/invoices/invoice-1.pdf");
        assertThat(sent).hasContent("pdf bytes");
        assertThat(remoteRoot.resolve("inbox/invoices/.invoice-1.pdf.fdrop-part")).doesNotExist();
        // delete-after-send is the default: the file is gone from fdrop and its quota returned
        assertThat(app.files.list(alice, "/forward/Out")).isEmpty();
        assertThat(app.users.find(alice.sub()).orElseThrow().usedBytes()).isZero();

        // a second file with the same name is a conflict, not an overwrite
        ForwardJob dup = run(drop(d, "Out", "invoice-1.pdf", "other"));
        assertThat(dup.state()).isEqualTo(JobState.FAILED);
        assertThat(dup.lastError()).contains("already exists");
    }

    @Test
    void keptFilesExpireTheirKeepDaysAfterSending() throws Exception {
        DestinationService.AdminView d = destination(null);
        FolderService.FolderView f = app.folderService.create(alice, FolderType.FORWARD, "Kept",
                d.destination().id(), 3);
        // forward folders take no per-file expiry
        assertThatThrownBy(() -> app.files.openWrite(alice, "/forward/Kept/x.pdf", 1, false, Via.HTTP, null,
                "1 day")).isInstanceOfSatisfying(io.fdrop.core.error.FdropException.class,
                e -> assertThat(e.type()).isEqualTo("expiry-not-allowed"));
        ForwardJob j = drop(d, "Kept", "kept.pdf", "kept");
        assertThat(app.files.stat(alice, "/forward/Kept/kept.pdf").expiresAt()).isNull();
        ForwardJob after = run(j);
        assertThat(after.state()).isEqualTo(JobState.SENT);
        FileService.Entry e = app.files.stat(alice, "/forward/Kept/kept.pdf");
        assertThat(e.expiresIn()).isEqualTo("3 days");
        assertThat(e.expirySource()).isEqualTo(io.fdrop.core.model.Enums.ExpirySource.FORWARD_KEEP);
        assertThat(e.expiresAt()).isEqualTo(after.sentAt() + java.time.Duration.ofDays(3).toMillis());
        assertThat(f.keepDays()).isEqualTo(3);
    }

    @Test
    void hostKeyMismatchFailsWithoutRetry() throws Exception {
        DestinationService.AdminView d = destination("SHA256:not-the-real-key");
        ForwardJob after = run(drop(d, "Out", "a.txt", "a"));
        assertThat(after.state()).isEqualTo(JobState.FAILED);
        assertThat(after.lastError()).contains("does not match the pinned");
        assertThat(remoteRoot.resolve("inbox")).doesNotExist();
    }

    @Test
    void rejectedKeyFailsAndUnreachableRetries() throws Exception {
        DestinationService.AdminView d = destination(null);
        allowedKey.set(null);
        ForwardJob rejected = run(drop(d, "Out", "a.txt", "a"));
        assertThat(rejected.state()).isEqualTo(JobState.FAILED);
        assertThat(rejected.lastError()).contains("rejected fdrop's key");

        app.folderService.retry(alice, rejected.folderId(), rejected.id());
        allowedKey.set(MinaSshTools.parse(d.keys().get(0).publicKey()));
        destination.stop(true);
        ForwardJob down = run(app.records.job(rejected.id()).orElseThrow());
        assertThat(down.state()).isEqualTo(JobState.RETRY_WAIT);
        assertThat(down.firstFailedAt()).isNotNull();
    }
}
