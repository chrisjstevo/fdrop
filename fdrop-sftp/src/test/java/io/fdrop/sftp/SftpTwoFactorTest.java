package io.fdrop.sftp;

import io.fdrop.core.FakeDirectory;
import io.fdrop.core.FakeOidc;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.storage.local.LocalStorageBackend;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SFTP logins for users who need two factors: plain passwords are refused, keyboard-interactive asks for a
 * password and a one-time code ({@code 123456} in the fake Keycloak), and usernames starting {@code admin} are
 * admins.
 */
class SftpTwoFactorTest {

    @TempDir
    Path dir;

    Fdrop app;
    SftpServer server;
    SshClient client;
    ForwardClient forwardClient;
    FakeDirectory directory = new FakeDirectory();
    final List<String> prompts = new ArrayList<>();

    void start(String require, boolean codes) throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.sftp.port = 0;
        cfg.sftp.bind = "127.0.0.1";
        cfg.sftp.hostKeyPath = dir.resolve("host_key").toString();
        cfg.auth.twoFactor.require = require;
        cfg.auth.twoFactor.sftpPasswordWithCode = codes;
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("data"));
        forwardClient = new ForwardClient(Duration.ofSeconds(5));
        app = new Fdrop(cfg, b, new MinaSshTools(forwardClient), FakeOidc::new);
        app.twoFactor.useDirectory(directory);
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

    /** Logs in with one method only; {@code answers} reply to keyboard-interactive prompts in order. */
    private boolean login(String user, UserAuthFactory method, String... answers) {
        client.setUserAuthFactories(List.of(method));
        try (ClientSession s = client.connect(user, "127.0.0.1", server.port()).verify(Duration.ofSeconds(10))
                .getSession()) {
            if (method instanceof UserAuthPasswordFactory) {
                s.addPasswordIdentity(answers[0]);
            }
            s.setUserInteraction(new UserInteraction() {
                @Override
                public String[] interactive(ClientSession session, String name, String instruction, String lang,
                                            String[] prompt, boolean[] echo) {
                    prompts.addAll(List.of(prompt));
                    String[] out = new String[prompt.length];
                    for (int i = 0; i < prompt.length; i++) {
                        out[i] = i < answers.length ? answers[i] : "";
                    }
                    return out;
                }

                @Override
                public String getUpdatedPassword(ClientSession session, String prompt, String lang) {
                    return null;
                }
            });
            return s.auth().verify(Duration.ofSeconds(10)).isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void adminsNeedAPasswordAndACode() throws Exception {
        directory.withOtp("admin1");
        start("admins", true);
        assertThat(login("admin1", UserAuthPasswordFactory.INSTANCE, "admin1")).isFalse();
        assertThat(login("admin1", UserAuthKeyboardInteractiveFactory.INSTANCE, "admin1", "123456")).isTrue();
        assertThat(prompts).containsExactly("Password: ", "Verification code: ");
        assertThat(login("admin1", UserAuthKeyboardInteractiveFactory.INSTANCE, "admin1", "999999")).isFalse();
        assertThat(login("admin1", UserAuthKeyboardInteractiveFactory.INSTANCE, "admin1", "")).isFalse();
    }

    @Test
    void usersWithoutTwoFactorAreAskedOnlyForTheirPassword() throws Exception {
        start("admins", true);
        assertThat(login("alice", UserAuthPasswordFactory.INSTANCE, "alice")).isTrue();
        assertThat(login("alice", UserAuthKeyboardInteractiveFactory.INSTANCE, "alice")).isTrue();
        assertThat(prompts).containsExactly("Password: ");
    }

    @Test
    void aStandardUserWhoTurnedItOnIsAskedForACode() throws Exception {
        directory.withOtp("bob");
        start("none", true);
        assertThat(login("bob", UserAuthPasswordFactory.INSTANCE, "bob")).isFalse();
        assertThat(login("bob", UserAuthKeyboardInteractiveFactory.INSTANCE, "bob", "123456")).isTrue();
    }

    @Test
    void withoutAnAuthenticatorAppOnlyKeysWork() throws Exception {
        directory.withSecurityKey("admin2");
        start("admins", true);
        assertThat(login("admin2", UserAuthKeyboardInteractiveFactory.INSTANCE, "admin2", "123456")).isFalse();
        assertThat(login("admin2", UserAuthPasswordFactory.INSTANCE, "admin2")).isFalse();
        // required but nothing set up yet
        assertThat(login("admin4", UserAuthKeyboardInteractiveFactory.INSTANCE, "admin4", "123456")).isFalse();
    }

    @Test
    void withCodesTurnedOffOnlyKeysWork() throws Exception {
        directory.withOtp("admin3");
        start("admins", false);
        assertThat(login("admin3", UserAuthKeyboardInteractiveFactory.INSTANCE, "admin3", "123456")).isFalse();
        assertThat(login("alice", UserAuthKeyboardInteractiveFactory.INSTANCE, "alice")).isTrue();
    }
}
