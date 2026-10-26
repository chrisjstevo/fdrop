package io.fdrop.sftp;

import io.fdrop.core.FakeDirectory;
import io.fdrop.core.FakeOidc;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.storage.local.LocalStorageBackend;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** File expiry over SFTP: folder defaults and {@code .fdrop-expires} control files, with a real MINA client. */
class SftpExpiryTest {

    @TempDir
    Path dir;

    Fdrop app;
    SftpServer server;
    ForwardClient forwardClient;
    SshClient client;
    TestSupport.MutableClock clock;

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.sftp.port = 0;
        cfg.sftp.bind = "127.0.0.1";
        cfg.sftp.hostKeyPath = dir.resolve("host_key").toString();
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("data"));
        forwardClient = new ForwardClient(Duration.ofSeconds(10));
        clock = new TestSupport.MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        app = new Fdrop(cfg, b, new MinaSshTools(forwardClient), FakeOidc::new, clock);
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

    private SftpClient login(String user) throws Exception {
        ClientSession s = client.connect(user, "127.0.0.1", server.port()).verify(Duration.ofSeconds(10)).getSession();
        s.addPasswordIdentity(user);
        s.auth().verify(Duration.ofSeconds(10));
        return SftpClientFactory.instance().createSftpClient(s);
    }

    private static void put(SftpClient sftp, String path, String text) throws Exception {
        try (OutputStream out = sftp.write(path)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String get(SftpClient sftp, String path) throws Exception {
        try (InputStream in = sftp.read(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
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

    private Long expiresAt(String path) {
        return app.files.stat(TestSupport.user("alice"), path).expiresAt();
    }

    @Test
    void controlFileBeforeTheUploadSetsItsExpiry() throws Exception {
        try (SftpClient sftp = login("alice")) {
            put(sftp, "/home/report.pdf.fdrop-expires", "6 days\n");
            assertThat(get(sftp, "/home/report.pdf.fdrop-expires")).isEqualTo("pending 6 days\n");
            put(sftp, "/home/report.pdf", "%PDF");
            assertThat(ls(sftp, "/home")).containsExactly("report.pdf");
            Instant at = clock.instant().plus(Duration.ofDays(6));
            assertThat(expiresAt("/home/report.pdf")).isEqualTo(at.toEpochMilli());
            assertThat(get(sftp, "/home/report.pdf.fdrop-expires")).isEqualTo(at + " 6 days\n");
            assertThat(sftp.stat("/home/report.pdf.fdrop-expires").getSize()).isEqualTo((at + " 6 days\n").length());

            // after the upload: counts from now; rm clears it
            clock.advance(Duration.ofHours(1));
            put(sftp, "/home/report.pdf.fdrop-expires", "1day");
            assertThat(expiresAt("/home/report.pdf")).isEqualTo(clock.instant().plus(Duration.ofDays(1)).toEpochMilli());
            sftp.remove("/home/report.pdf.fdrop-expires");
            assertThat(get(sftp, "/home/report.pdf.fdrop-expires")).isEqualTo("never\n");
            assertThat(expiresAt("/home/report.pdf")).isNull();
        }
    }

    @Test
    void aBadValueFailsTheCloseWithTheReason() throws Exception {
        try (SftpClient sftp = login("alice")) {
            assertThatThrownBy(() -> put(sftp, "/home/a.pdf.fdrop-expires", "6 dys"))
                    .isInstanceOfSatisfying(SftpException.class, e -> assertThat(e.getMessage())
                            .contains("Unknown unit 'dys'. Use one of sec, min, hour, day, week, month, year"));
            assertThatThrownBy(() -> sftp.stat("/home/a.pdf.fdrop-expires")).isInstanceOf(SftpException.class);
        }
    }

    @Test
    void temporaryNameUploadsAreCovered() throws Exception {
        try (SftpClient sftp = login("alice")) {
            // WinSCP-style: the control file arrives as .filepart and is renamed onto its name
            put(sftp, "/home/data.csv.fdrop-expires.filepart", "2 weeks");
            sftp.rename("/home/data.csv.fdrop-expires.filepart", "/home/data.csv.fdrop-expires");
            assertThat(ls(sftp, "/home")).isEmpty();
            // and so does the file itself
            put(sftp, "/home/data.csv.filepart", "a,b\n");
            sftp.rename("/home/data.csv.filepart", "/home/data.csv");
            assertThat(app.files.stat(TestSupport.user("alice"), "/home/data.csv").expiresIn()).isEqualTo("2 weeks");
        }
    }

    @Test
    void folderDefaultsApplyToPlainSftpUploadsAndExpiredFilesVanish() throws Exception {
        var alice = TestSupport.user("alice");
        app.accounts.ensure(alice);
        var f = app.folderService.create(alice, FolderType.SHARED, "Scratch", null, null);
        app.folderService.updateExpiry(alice, f.id(), "1 hour", null, false);
        try (SftpClient sftp = login("alice")) {
            put(sftp, "/shared/Scratch/tmp.txt", "x");
            assertThat(app.files.stat(alice, "/shared/Scratch/tmp.txt").expiresIn()).isEqualTo("1 hour");
            clock.advance(Duration.ofHours(1));
            assertThat(ls(sftp, "/shared/Scratch")).isEmpty();
            assertThatThrownBy(() -> get(sftp, "/shared/Scratch/tmp.txt")).isInstanceOf(SftpException.class);
        }
    }
}
