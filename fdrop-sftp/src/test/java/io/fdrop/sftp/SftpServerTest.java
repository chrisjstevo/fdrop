package io.fdrop.sftp;

import io.fdrop.core.FakeDirectory;
import io.fdrop.core.FakeOidc;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.service.DestinationService;
import io.fdrop.core.service.FolderService;
import io.fdrop.storage.local.LocalStorageBackend;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SFTP server with a real MINA SFTP client over a socket. Passwords equal usernames (fake Keycloak). */
class SftpServerTest {

    @TempDir
    Path dir;

    Fdrop app;
    SftpServer server;
    ForwardClient forwardClient;
    SshClient client;

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.sftp.port = 0;
        cfg.sftp.bind = "127.0.0.1";
        cfg.sftp.hostKeyPath = dir.resolve("host_key").toString();
        cfg.forward.allowedHosts.add("127.0.0.1");
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("data"));
        forwardClient = new ForwardClient(Duration.ofSeconds(10));
        app = new Fdrop(cfg, b, new MinaSshTools(forwardClient), FakeOidc::new);
        app.twoFactor.useDirectory(new FakeDirectory());
        server = new SftpServer(app).start();
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
    }

    @AfterEach
    void stop() throws Exception {
        client.stop();
        server.close();
        forwardClient.close();
        app.close();
    }

    private SftpClient login(String user, String password) throws Exception {
        ClientSession s = client.connect(user, "127.0.0.1", server.port()).verify(Duration.ofSeconds(10)).getSession();
        s.addPasswordIdentity(password);
        s.auth().verify(Duration.ofSeconds(10));
        return SftpClientFactory.instance().createSftpClient(s);
    }

    private static void put(SftpClient sftp, String path, byte[] data) throws Exception {
        try (OutputStream out = sftp.write(path)) {
            out.write(data);
        }
    }

    private static byte[] get(SftpClient sftp, String path) throws Exception {
        try (InputStream in = sftp.read(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private static List<String> ls(SftpClient sftp, String path) throws Exception {
        List<String> out = new ArrayList<>();
        for (SftpClient.DirEntry e : sftp.readDir(path)) {
            if (!e.getFilename().equals(".") && !e.getFilename().equals("..")) {
                out.add(e.getFilename());
            }
        }
        out.sort(null);
        return out;
    }

    @Test
    void wrongPasswordIsRejected() {
        assertThatThrownBy(() -> login("alice", "wrong")).isInstanceOf(Exception.class);
    }

    @Test
    void homeFilesOverSftp() throws Exception {
        try (SftpClient sftp = login("alice", "alice")) {
            assertThat(sftp.canonicalPath(".")).isEqualTo("/home");
            assertThat(ls(sftp, "/")).containsExactly("forward", "home", "shared");

            byte[] data = new byte[3 * 1024 * 1024 + 5];
            new Random(3).nextBytes(data);
            put(sftp, "report.bin", data);
            sftp.mkdir("/home/docs");
            sftp.rename("/home/report.bin", "/home/docs/report.bin");
            assertThat(ls(sftp, "/home")).containsExactly("docs");
            assertThat(get(sftp, "/home/docs/report.bin")).isEqualTo(data);
            assertThat(sftp.stat("/home/docs/report.bin").getSize()).isEqualTo(data.length);

            // files appear in the HTTP-side history as SFTP uploads
            var rows = app.accounts.history(TestSupport.user("alice"),
                    new RecordRepo.Filter(null, false, null, null, null, null, null, 10)).rows();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).record().via().name()).isEqualTo("SFTP");
            assertThat(rows.get(0).record().clientIp()).isEqualTo("127.0.0.1");

            sftp.remove("/home/docs/report.bin");
            sftp.rmdir("/home/docs");
            assertThat(ls(sftp, "/home")).isEmpty();
        }
    }

    @Test
    void sharedFolderPermissionsApplyMidSession() throws Exception {
        try (SftpClient alice = login("alice", "alice")) {
            put(alice, "/home/x", new byte[1]);
        }
        try (SftpClient bob = login("bob", "bob")) {
            put(bob, "/home/x", new byte[1]);
        }
        FolderService.FolderView f = app.folderService.create(TestSupport.user("alice"), FolderType.SHARED, "Team",
                null, null);
        app.folderService.putMember(TestSupport.user("alice"), f.id(), "bob", Permission.READ);
        try (SftpClient alice = login("alice", "alice"); SftpClient bob = login("bob", "bob")) {
            put(alice, "/shared/Team/plan.txt", "plan".getBytes(StandardCharsets.UTF_8));
            assertThat(get(bob, "/shared/Team/plan.txt")).asString(StandardCharsets.UTF_8).isEqualTo("plan");
            assertThatThrownBy(() -> put(bob, "/shared/Team/bob.txt", new byte[1]))
                    .isInstanceOfSatisfying(SftpException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(SftpConstants.SSH_FX_PERMISSION_DENIED));

            app.folderService.removeMember(TestSupport.user("alice"), f.id(), "bob");
            assertThatThrownBy(() -> get(bob, "/shared/Team/plan.txt")).isInstanceOf(SftpException.class);
            assertThat(ls(bob, "/shared")).isEmpty();
        }
    }

    @Test
    void resumeAppendsToAnExistingFile() throws Exception {
        byte[] first = "first half, ".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second half".getBytes(StandardCharsets.UTF_8);
        try (SftpClient sftp = login("carol", "carol")) {
            put(sftp, "/home/resume.txt", first);
            try (SftpClient.CloseableHandle h = sftp.open("/home/resume.txt", SftpClient.OpenMode.Write)) {
                sftp.write(h, first.length, second);
            }
            assertThat(get(sftp, "/home/resume.txt")).asString(StandardCharsets.UTF_8)
                    .isEqualTo("first half, second half");
        }
    }

    @Test
    void forwardFoldersAcceptFilesOnly() throws Exception {
        var admin = TestSupport.admin("root");
        var alice = TestSupport.user("alice");
        app.accounts.ensure(admin);
        app.accounts.ensure(alice);
        DestinationService.AdminView d = app.destinationService.create(admin, new DestinationService.DestinationInput(
                "Partner", null, "127.0.0.1", 2200, "/in", "fdrop", ServerAuth.HOST_KEY, "SHA256:pinned", null,
                false, true));
        app.destinationService.assign(admin, d.destination().id(), "alice");
        app.folderService.create(alice, FolderType.FORWARD, "Out", List.of(d.destination().id()), 0);
        try (SftpClient sftp = login("alice", "alice")) {
            put(sftp, "/forward/Out/a.txt", "a".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> sftp.mkdir("/forward/Out/sub")).isInstanceOf(SftpException.class);
            assertThatThrownBy(() -> sftp.mkdir("/shared/new")).isInstanceOf(SftpException.class);
        }
        assertThat(app.folderService.jobs(alice, app.folderService.list(alice).get(0).id(), 10)).hasSize(1);
    }

    @Test
    void cannotEscapeTheVirtualTree() throws Exception {
        try (SftpClient sftp = login("dave", "dave")) {
            assertThat(sftp.canonicalPath("/home/../../..")).isEqualTo("/");
            assertThatThrownBy(() -> get(sftp, "/etc/passwd")).isInstanceOf(SftpException.class);
            assertThatThrownBy(() -> put(sftp, "/users/other/x", new byte[1])).isInstanceOf(SftpException.class);
        }
    }
}
