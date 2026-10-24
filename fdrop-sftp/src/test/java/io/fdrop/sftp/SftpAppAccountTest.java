package io.fdrop.sftp;

import io.fdrop.core.FakeDirectory;
import io.fdrop.core.FakeOidc;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.model.Enums.AppMethod;
import io.fdrop.core.service.AppAccountService;
import io.fdrop.storage.local.LocalStorageBackend;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SFTP logins of application accounts: plain password and SSH keys, never keyboard-interactive, no second factor
 * even when everyone else needs one, and the account's allowed methods and source addresses enforced.
 */
class SftpAppAccountTest {

    @TempDir
    Path dir;

    Fdrop app;
    SftpServer server;
    SshClient client;
    ForwardClient forwardClient;
    FakeDirectory directory = new FakeDirectory();

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.sftp.port = 0;
        cfg.sftp.bind = "127.0.0.1";
        cfg.sftp.hostKeyPath = dir.resolve("host_key").toString();
        cfg.auth.twoFactor.require = "all";
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("data"));
        forwardClient = new ForwardClient(Duration.ofSeconds(5));
        app = new Fdrop(cfg, b, new MinaSshTools(forwardClient), FakeOidc::new);
        app.twoFactor.useDirectory(directory);
        app.appAccounts.useDirectory(directory.applications());
        ((FakeOidc) app.oidc).usePasswords(directory::password);
        app.accounts.ensure(TestSupport.user("alice"));
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

    private boolean login(String user, UserAuthFactory method, String password, KeyPair key) {
        client.setUserAuthFactories(List.of(method));
        try (ClientSession s = client.connect(user, "127.0.0.1", server.port()).verify(Duration.ofSeconds(10))
                .getSession()) {
            if (password != null) {
                s.addPasswordIdentity(password);
            }
            if (key != null) {
                s.addPublicKeyIdentity(key);
            }
            return s.auth().verify(Duration.ofSeconds(10)).isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    private AppAccountService.Created create(String name, Set<AppMethod> methods, List<String> sources) {
        return app.appAccounts.create(TestSupport.admin("admin1"), new AppAccountService.CreateInput(name, null,
                "alice", methods, sources));
    }

    @Test
    void passwordWithoutASecondFactorEvenWhenEveryoneElseNeedsOne() {
        String password = create("app-batch", EnumSet.of(AppMethod.PASSWORD), List.of()).password();
        assertThat(login("app-batch", UserAuthPasswordFactory.INSTANCE, password, null)).isTrue();
        assertThat(login("app-batch", UserAuthPasswordFactory.INSTANCE, "wrong", null)).isFalse();
        // keyboard-interactive is never offered to application accounts
        assertThat(login("app-batch", UserAuthKeyboardInteractiveFactory.INSTANCE, password, null)).isFalse();
        // a person under require: all cannot use a plain password
        assertThat(login("alice", UserAuthPasswordFactory.INSTANCE, "alice", null)).isFalse();
    }

    @Test
    void anAccountAddedInKeycloakIsTakenOnAtItsFirstPasswordLogin() {
        // "app-direct" is in the applications group in Keycloak but was never created in fdrop
        assertThat(login("app-direct", UserAuthPasswordFactory.INSTANCE, "app-direct", null)).isTrue();
        AppAccountService.AppView v = app.appAccounts.get(TestSupport.admin("admin1"), "sub-app-direct");
        assertThat(v.allowedMethods()).containsExactlyInAnyOrder(AppMethod.values());
    }

    @Test
    void keysAddedByAnAdmin() throws Exception {
        create("app-keys", EnumSet.of(AppMethod.PUBLICKEY), List.of());
        KeyPair key = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
        app.appAccounts.addSshKey(TestSupport.admin("admin1"), "sub-app-keys", "batch host",
                PublicKeyEntry.toString(key.getPublic()), app.ssh);
        assertThat(login("app-keys", UserAuthPublicKeyFactory.INSTANCE, null, key)).isTrue();
        assertThat(login("app-keys", UserAuthPublicKeyFactory.INSTANCE, null,
                KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256))).isFalse();

        directory.applications().setEnabled("sub-app-keys", false);
        assertThat(login("app-keys", UserAuthPublicKeyFactory.INSTANCE, null, key)).isFalse();
    }

    @Test
    void methodsAndSourcesAreEnforced() {
        String onlyMtls = create("app-mtls", EnumSet.of(AppMethod.MTLS, AppMethod.PASSWORD), List.of()).password();
        app.appAccounts.update(TestSupport.admin("admin1"), "sub-app-mtls", new AppAccountService.UpdateInput(null,
                null, EnumSet.of(AppMethod.MTLS), null, null));
        assertThat(login("app-mtls", UserAuthPasswordFactory.INSTANCE, onlyMtls, null)).isFalse();

        String elsewhere = create("app-far", EnumSet.of(AppMethod.PASSWORD), List.of("10.0.0.0/8")).password();
        assertThat(login("app-far", UserAuthPasswordFactory.INSTANCE, elsewhere, null)).isFalse();
        String local = create("app-near", EnumSet.of(AppMethod.PASSWORD), List.of("127.0.0.1/32")).password();
        assertThat(login("app-near", UserAuthPasswordFactory.INSTANCE, local, null)).isTrue();
    }
}
