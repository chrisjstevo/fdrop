package io.fdrop.client;

import io.fdrop.core.FakeDirectory;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestCerts;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.model.Enums.AppMethod;
import io.fdrop.core.service.AppAccountService;
import io.fdrop.http.HttpServer;
import io.fdrop.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An application account signing in with its client certificate on fdrop's mTLS port, as integrations do. */
class ClientCertificateTest {

    @TempDir
    Path dir;

    final TestCerts.Issued serverCa = TestCerts.ca("fdrop test CA");
    final TestCerts.Issued appCert = TestCerts.selfSigned("CN=app-client");
    Fdrop app;
    HttpServer server;
    Path ca;
    Path[] client;

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.http.mtls.enabled = true;
        cfg.http.mtls.port = 0;
        cfg.upload.chunkSize = io.fdrop.core.config.DataSize.ofMiB(5);
        cfg.limits.defaultAppQuota = io.fdrop.core.config.DataSize.ofMiB(100);
        Path[] serverFiles = TestCerts.server(serverCa).write(dir, "server");
        cfg.http.tls.cert = serverFiles[0].toString();
        cfg.http.tls.key = serverFiles[1].toString();
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(Files.createDirectories(dir.resolve("files")));
        app = TestSupport.fdrop(cfg, b, new FakeDirectory());
        server = new HttpServer(app).start();

        ca = Files.writeString(dir.resolve("ca.pem"), serverCa.certPem());
        client = appCert.write(dir, "app-client");
        Principal admin = TestSupport.admin("admin1");
        app.accounts.ensure(admin);
        app.accounts.ensure(TestSupport.user("alice"));
        app.appAccounts.create(admin, new AppAccountService.CreateInput("app-client", "test", "alice",
                EnumSet.of(AppMethod.MTLS), List.of()));
        app.appAccounts.addCertificate(admin, "sub-app-client", new AppAccountService.CertInput(appCert.certPem(),
                null, null));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        app.close();
    }

    @Test
    void uploadsWithItsCertificate() throws Exception {
        byte[] data = new byte[7 * 1024 * 1024];
        new Random(7).nextBytes(data);
        Path f = Files.write(dir.resolve("export.csv"), data);
        try (FdropClient fdrop = FdropClient.builder().baseUrl("https://localhost:" + server.mtlsPort())
                .clientCertificate(client[0], client[1], ca).build()) {
            assertThat(fdrop.me().isApplication()).isTrue();
            FileEntry e = fdrop.upload(f, "/home/");
            assertThat(e.path()).isEqualTo("/home/export.csv");
            assertThat(e.size()).isEqualTo(data.length);
        }
    }

    @Test
    void aServerItDoesNotTrustIsAnErrorNotARetry() {
        TestCerts.Issued other = TestCerts.ca("someone else");
        Path otherCa = dir.resolve("other.pem");
        try {
            Files.writeString(otherCa, other.certPem());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        try (FdropClient fdrop = FdropClient.builder().baseUrl("https://localhost:" + server.mtlsPort())
                .clientCertificate(client[0], client[1], otherCa).maxAttempts(2).build()) {
            assertThatThrownBy(fdrop::me).isExactlyInstanceOf(FdropClientException.class)
                    .hasMessageContaining("TLS handshake failed");
        }
    }
}
