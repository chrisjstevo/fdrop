package io.fdrop.core.db;

import io.fdrop.core.TestCerts;
import io.fdrop.core.audit.Audit;
import io.fdrop.core.audit.AuditStore;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.metrics.FdropMetrics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The metadata DB over TLS with client certificates, against PostgreSQL set up from the dev container's own
 * {@code .devcontainer/postgres/} files (opened to the test's network instead of only localhost). Certificates are
 * made fresh for each run.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresTlsIT {

    private static final Path DEV = Path.of("..", ".devcontainer", "postgres");

    static Path dir;
    static TestCerts.Issued ca;
    static GenericContainer<?> pg;

    @BeforeAll
    static void start() throws Exception {
        dir = Files.createTempDirectory("pg-tls");
        ca = TestCerts.ca("fdrop test DB CA");
        Files.writeString(dir.resolve("ca.crt"), ca.certPem());
        TestCerts.client(ca, "CN=fdrop").write(dir, "fdrop");

        pg = new GenericContainer<>("docker.io/library/postgres:17-alpine");
        // the server certificate must name the host the tests connect to, and only that
        String host = pg.getHost();
        TestCerts.Issued server = TestCerts.serverFor(ca, host);
        String conf = Files.readString(DEV.resolve("postgresql.conf"))
                .replace("listen_addresses = 'localhost'", "listen_addresses = '*'");
        String hba = Files.readString(DEV.resolve("pg_hba.conf"))
                .replace("127.0.0.1/32", "0.0.0.0/0").replace("::1/128", "::/0");
        pg.withEnv("POSTGRES_USER", "fdrop").withEnv("POSTGRES_DB", "fdrop")
                .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                .withCopyToContainer(Transferable.of(conf), "/etc/postgresql/postgresql.conf")
                .withCopyToContainer(Transferable.of(hba), "/etc/postgresql/pg_hba.conf")
                .withCopyToContainer(Transferable.of(ca.certPem()), "/etc/fdrop/certs/ca.crt")
                .withCopyToContainer(Transferable.of(server.certPem()), "/etc/fdrop/certs/postgres.crt")
                .withCopyToContainer(Transferable.of(server.keyPem()), "/etc/fdrop/certs/postgres.key")
                // the same start-up as the dev container's compose service
                .withCreateContainerCmdModifier(c -> c.withEntrypoint("sh", "-c", "install -d -o postgres -g postgres"
                        + " -m 700 /var/lib/postgresql/tls && install -o postgres -g postgres -m 600"
                        + " /etc/fdrop/certs/postgres.key /var/lib/postgresql/tls/server.key && install -o postgres"
                        + " -g postgres -m 644 /etc/fdrop/certs/postgres.crt /etc/fdrop/certs/ca.crt"
                        + " /var/lib/postgresql/tls/ && exec docker-entrypoint.sh postgres"
                        + " -c config_file=/etc/postgresql/postgresql.conf"))
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        pg.start();
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.stop();
        }
    }

    private static FdropConfig.Db cfg() {
        return cfg(pg.getHost());
    }

    private static FdropConfig.Db cfg(String host) {
        FdropConfig.Db c = new FdropConfig.Db();
        c.url = "jdbc:postgresql://" + host + ":" + pg.getMappedPort(5432) + "/fdrop";
        c.username = "fdrop";
        c.password = "";
        c.maxPoolSize = 2;
        c.tls.ca = dir.resolve("ca.crt").toString();
        c.tls.cert = dir.resolve("fdrop.crt").toString();
        c.tls.key = dir.resolve("fdrop.key").toString();
        return c;
    }

    private static String failure(FdropConfig.Db c) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> new Db(c).close());
        assertThat(t).as("connection should be refused").isNotNull();
        StringBuilder sb = new StringBuilder();
        for (Throwable e = t; e != null; e = e.getCause()) {
            sb.append(e.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    @Test
    void connectsWithItsCertificateAndRunsTheMigrations() {
        try (Db db = new Db(cfg())) {
            List<Object[]> ssl = db.query("SELECT ssl, version, client_dn FROM pg_stat_ssl WHERE pid ="
                    + " pg_backend_pid()", rs -> new Object[]{rs.getBoolean(1), rs.getString(2), rs.getString(3)});
            assertThat(ssl.getFirst()[0]).isEqualTo(true);
            assertThat((String) ssl.getFirst()[1]).isIn("TLSv1.3", "TLSv1.2");
            assertThat((String) ssl.getFirst()[2]).contains("CN=fdrop");
            assertThat(db.queryLong("SELECT MAX(CAST(version AS INT)) FROM flyway_schema_history")).isEqualTo(8);

            // the audit chain on PostgreSQL, as production runs it
            FdropConfig.AuditLog a = new FdropConfig.AuditLog();
            a.node = "pg-node";
            FdropMetrics m = new FdropMetrics(List.of(0.1));
            try (AuditStore store = new AuditStore(a, db, m)) {
                Audit.setSink(store);
                Audit.log("login", "user", "alice", "ip", "10.0.0.1");
                Audit.log("upload", "user", "alice", "path", "/home/a.txt", "size", 3);
                store.flush();
                assertThat(store.verify()).allSatisfy(c -> assertThat(c.intact()).isTrue());
            }
        }
    }

    @Test
    void theServerRefusesPlainTcpAndOtherCertificates() throws Exception {
        FdropConfig.Db plain = cfg();
        plain.tls.mode = "disable";
        assertThat(failure(plain)).contains("no pg_hba.conf entry").contains("no encryption");

        TestCerts.Issued otherCa = TestCerts.ca("someone else's CA");
        TestCerts.client(otherCa, "CN=fdrop").write(dir, "stranger");
        FdropConfig.Db stranger = cfg();
        stranger.tls.cert = dir.resolve("stranger.crt").toString();
        stranger.tls.key = dir.resolve("stranger.key").toString();
        assertThat(failure(stranger)).containsAnyOf("certificate", "SSL", "handshake");

        TestCerts.client(ca, "CN=mallory").write(dir, "mallory");
        FdropConfig.Db wrongUser = cfg();
        wrongUser.tls.cert = dir.resolve("mallory.crt").toString();
        wrongUser.tls.key = dir.resolve("mallory.key").toString();
        assertThat(failure(wrongUser)).contains("certificate authentication failed for user \"fdrop\"");
    }

    @Test
    void fdropRefusesAServerItCannotVerify() throws Exception {
        TestCerts.Issued otherCa = TestCerts.ca("not the DB's CA");
        Files.writeString(dir.resolve("other-ca.crt"), otherCa.certPem());
        FdropConfig.Db wrongCa = cfg();
        wrongCa.tls.ca = dir.resolve("other-ca.crt").toString();
        assertThat(failure(wrongCa)).containsAnyOf("PKIX", "certification path", "SSL");

        // the same server by its address: the certificate names only the host name
        String host = pg.getHost();
        String address = InetAddress.getByName(host).getHostAddress();
        assumeFalse(address.equals(host), "the container host is already an address");
        assertThat(failure(cfg(address))).contains("hostname");
    }

    @Test
    void aRenewedCertificateIsUsedByNewConnections() throws Exception {
        Path rot = Files.createTempDirectory(dir, "rotate");
        TestCerts.Issued first = TestCerts.client(ca, "CN=fdrop");
        first.write(rot, "client");
        FdropConfig.Db c = cfg();
        c.tls.cert = rot.resolve("client.crt").toString();
        c.tls.key = rot.resolve("client.key").toString();
        try (Db db = new Db(c)) {
            String serial = "SELECT client_serial::text FROM pg_stat_ssl WHERE pid = pg_backend_pid()";
            assertThat(db.queryOne(serial, rs -> rs.getString(1)).orElseThrow())
                    .isEqualTo(first.cert().getSerialNumber().toString());

            TestCerts.Issued renewed = TestCerts.client(ca, "CN=fdrop", Duration.ofDays(90));
            renewed.write(rot, "client");
            db.hikari().getHikariPoolMXBean().softEvictConnections();
            assertThat(db.queryOne(serial, rs -> rs.getString(1)).orElseThrow())
                    .isEqualTo(renewed.cert().getSerialNumber().toString());
            assertThat(DbTls.certExpirySeconds(c)).isGreaterThan(80 * 86400.0);
        }
    }
}
