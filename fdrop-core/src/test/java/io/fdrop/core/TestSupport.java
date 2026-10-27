package io.fdrop.core;

import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.ConfigLoader;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.service.SshTools;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.util.Hashing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

/** Builds a full {@link Fdrop} on an in-memory H2 database for tests, with a fake SSH toolkit. */
public final class TestSupport {

    private TestSupport() {
    }

    public static FdropConfig config() {
        FdropConfig cfg = new ConfigLoader(Map.of()).loadFromString("{}");
        cfg.db.url = "jdbc:h2:mem:" + Hashing.randomToken(8) + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        cfg.forward.encryptionKey = Base64.getEncoder().encodeToString(new byte[32]);
        cfg.forward.allowedHosts.add("sftp.test.invalid");
        cfg.limits.defaultQuota = io.fdrop.core.config.DataSize.ofMiB(100);
        cfg.storage.encryption.local.keys = "test1:" + Base64.getEncoder().encodeToString(new byte[32]);
        // each application its own audit index (with fdrop-search on the classpath), under target/ for mvn clean
        cfg.audit.indexDir = "target/audit-index/" + Hashing.randomToken(8);
        cfg.audit.node = "test";
        return cfg;
    }

    public static Fdrop fdrop(FdropConfig cfg, StorageBackend backend) {
        return fdrop(cfg, backend, new FakeDirectory());
    }

    /** With a directory a test controls, to give users second factors. */
    public static Fdrop fdrop(FdropConfig cfg, StorageBackend backend, FakeDirectory directory) {
        return fdrop(cfg, backend, directory, Clock.systemUTC());
    }

    /** With a clock a test controls, for file expiry. */
    public static Fdrop fdrop(FdropConfig cfg, StorageBackend backend, Clock clock) {
        return fdrop(cfg, backend, new FakeDirectory(), clock);
    }

    public static Fdrop fdrop(FdropConfig cfg, StorageBackend backend, FakeDirectory directory, Clock clock) {
        Fdrop app = new Fdrop(cfg, backend, new FakeSsh(), FakeOidc::new, clock);
        app.twoFactor.useDirectory(directory);
        app.appAccounts.useDirectory(directory.applications());
        ((FakeOidc) app.oidc).usePasswords(directory::password);
        return app;
    }

    public static Principal user(String name) {
        return new Principal("sub-" + name, name, name + "@test", true, false);
    }

    public static Principal admin(String name) {
        return new Principal("sub-" + name, name, name + "@test", true, true);
    }

    /** An application account; test names start {@code app} so the fakes recognise them. */
    public static Principal app(String name) {
        return new Principal("sub-" + name, name, null, false, false, true);
    }

    /** The principal the fakes give a username: {@code app…} applications, {@code admin…} admins, else users. */
    public static Principal byName(String name) {
        return name.startsWith("app") ? app(name) : name.startsWith("admin") ? admin(name) : user(name);
    }

    /** A clock that only moves when a test moves it. */
    public static final class MutableClock extends Clock {
        private volatile Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        public void set(Instant t) {
            now = t;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** SSH tools that accept any key and never connect anywhere. */
    public static final class FakeSsh implements SshTools {
        @Override
        public GeneratedKey generateEd25519(String comment) {
            return new GeneratedKey("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI" + Hashing.randomToken(24) + " " + comment,
                    ("PRIVATE-" + comment).getBytes());
        }

        @Override
        public ParsedKey parsePublicKey(String line) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) {
                throw io.fdrop.core.error.FdropException.badRequest("bad key");
            }
            return new ParsedKey(parts[0], "SHA256:" + Hashing.sha256Hex(parts[1]).substring(0, 43),
                    parts[0] + " " + parts[1]);
        }

        @Override
        public void checkUserCertificate(String certLine, String publicKeyLine) {
        }

        @Override
        public TestResult probe(String host, int port, String username, String publicKey, byte[] privateKey,
                                String userCert, ForwardDestination pinned) {
            return new TestResult(true, publicKey != null, "ssh-ed25519", "SHA256:fake", false, null, null);
        }
    }
}
