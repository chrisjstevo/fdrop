package io.fdrop.core.db;

import io.fdrop.core.TestCerts;
import io.fdrop.core.config.FdropConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startup checks of the metadata DB's TLS settings; nothing here connects. */
class DbTlsTest {

    @TempDir
    Path dir;

    private FdropConfig.Db pg() throws Exception {
        TestCerts.Issued ca = TestCerts.ca("db ca");
        Files.writeString(dir.resolve("ca.crt"), ca.certPem());
        TestCerts.client(ca, "CN=fdrop").write(dir, "client");
        FdropConfig.Db cfg = new FdropConfig.Db();
        cfg.url = "jdbc:postgresql://db.example.com:5432/fdrop";
        cfg.tls.ca = dir.resolve("ca.crt").toString();
        cfg.tls.cert = dir.resolve("client.crt").toString();
        cfg.tls.key = dir.resolve("client.key").toString();
        return cfg;
    }

    @Test
    void verifyFullWithOurFactory() throws Exception {
        Map<String, String> p = DbTls.properties(pg());
        assertThat(p).containsEntry("sslmode", "verify-full")
                .containsEntry("sslfactory", PemSslFactory.class.getName())
                .containsKeys(PemSslFactory.CA, PemSslFactory.CERT, PemSslFactory.KEY);
        assertThat(DbTls.certExpirySeconds(pg())).isBetween(29 * 86400.0, 30 * 86400.0);
    }

    @Test
    void h2IgnoresTls() {
        FdropConfig.Db cfg = new FdropConfig.Db();
        assertThat(DbTls.properties(cfg)).isEmpty();
        assertThat(DbTls.enabled(cfg)).isFalse();
    }

    @Test
    void refusesMissingFilesAndSslParameters() throws Exception {
        FdropConfig.Db cfg = pg();
        cfg.tls.cert = null;
        assertThatThrownBy(() -> DbTls.properties(cfg)).hasMessageContaining("db.tls.cert is required");
        FdropConfig.Db gone = pg();
        gone.tls.ca = dir.resolve("nope.crt").toString();
        assertThatThrownBy(() -> DbTls.properties(gone)).hasMessageContaining("db.tls.ca: cannot read");
        FdropConfig.Db param = pg();
        param.url += "?sslmode=require";
        assertThatThrownBy(() -> DbTls.properties(param)).hasMessageContaining("must not carry ssl parameters");
        FdropConfig.Db mode = pg();
        mode.tls.mode = "require";
        assertThatThrownBy(() -> DbTls.properties(mode)).hasMessageContaining("verify-full or disable");
    }

    @Test
    void refusesAKeyThatIsNotTheCertificatesWithoutShowingIt() throws Exception {
        FdropConfig.Db cfg = pg();
        TestCerts.Issued other = TestCerts.selfSigned("CN=other");
        Files.writeString(Path.of(cfg.tls.key), other.keyPem());
        assertThatThrownBy(() -> DbTls.properties(cfg))
                .hasMessageContaining("db.tls.key does not belong to the certificate")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("PRIVATE KEY", "MIG"));

        FdropConfig.Db traditional = pg();
        Files.writeString(Path.of(traditional.tls.key), "-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----\n");
        assertThatThrownBy(() -> DbTls.properties(traditional)).hasMessageContaining("openssl pkcs8 -topk8");
    }

    @Test
    void disableIsAnExplicitOptOut() throws Exception {
        FdropConfig.Db cfg = pg();
        cfg.tls.mode = "disable";
        assertThat(DbTls.properties(cfg)).containsExactly(Map.entry("sslmode", "disable"));
        assertThat(DbTls.enabled(cfg)).isFalse();
    }
}
