package io.fdrop.core.db;

import io.fdrop.core.config.FdropConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks the metadata DB's TLS settings at startup and turns them into pgJDBC connection properties (see "Metadata DB
 * connection" in spec/security-and-configuration.md). PostgreSQL is reached with {@code sslmode=verify-full} and a client certificate unless
 * {@code db.tls.mode} is explicitly {@code disable}; H2 ignores all of it.
 */
public final class DbTls {

    private static final Logger log = LoggerFactory.getLogger(DbTls.class);
    private static final Pattern SSL_PARAM = Pattern.compile("(?i)[?&;]ssl[a-z]*=");

    private DbTls() {
    }

    public static boolean postgres(String url) {
        return url != null && url.startsWith("jdbc:postgresql:");
    }

    /** Whether connections use a client certificate: PostgreSQL with TLS on. */
    public static boolean enabled(FdropConfig.Db cfg) {
        return postgres(cfg.url) && !"disable".equals(cfg.tls.mode);
    }

    /**
     * The driver properties for {@code cfg}, after checking the files exist, parse and belong together.
     *
     * @throws IllegalStateException naming the setting at fault; never with key material
     */
    public static Map<String, String> properties(FdropConfig.Db cfg) {
        Map<String, String> p = new LinkedHashMap<>();
        if (!postgres(cfg.url)) {
            return p;
        }
        if (SSL_PARAM.matcher(cfg.url).find()) {
            throw new IllegalStateException("db.url must not carry ssl parameters; configure TLS with db.tls.*");
        }
        String mode = cfg.tls.mode == null ? "verify-full" : cfg.tls.mode;
        switch (mode) {
            case "disable" -> {
                log.warn("db.tls.mode is disable: the metadata DB connection is not encrypted or authenticated by"
                        + " certificate. Never use this in production.");
                p.put("sslmode", "disable");
                return p;
            }
            case "verify-full" -> {
                // checked below
            }
            default -> throw new IllegalStateException("db.tls.mode must be verify-full or disable, not " + mode);
        }
        Path ca = file("db.tls.ca", cfg.tls.ca);
        Path cert = file("db.tls.cert", cfg.tls.cert);
        Path key = file("db.tls.key", cfg.tls.key);
        try {
            if (Pem.certificates(ca).isEmpty()) {
                throw new IllegalStateException("db.tls.ca holds no certificate");
            }
            X509Certificate leaf = Pem.certificates(cert).getFirst();
            if (!Pem.matches(Pem.privateKey(key), leaf)) {
                throw new IllegalStateException("db.tls.key does not belong to the certificate in db.tls.cert");
            }
            PemSslFactory.context(ca, cert, key);
        } catch (IOException e) {
            throw new IllegalStateException("metadata DB TLS: " + e.getMessage(), e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("metadata DB TLS: " + e.getMessage(), e);
        }
        p.put("sslmode", "verify-full");
        p.put("sslfactory", PemSslFactory.class.getName());
        p.put(PemSslFactory.CA, ca.toString());
        p.put(PemSslFactory.CERT, cert.toString());
        p.put(PemSslFactory.KEY, key.toString());
        return p;
    }

    private static Path file(String setting, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(setting + " is required for a PostgreSQL metadata DB (or set"
                    + " db.tls.mode: disable, never in production)");
        }
        Path p = Path.of(value);
        if (!Files.isReadable(p)) {
            throw new IllegalStateException(setting + ": cannot read " + p);
        }
        return p;
    }

    /** Seconds until the client certificate expires, read from the file each time; NaN when it cannot be read. */
    public static double certExpirySeconds(FdropConfig.Db cfg) {
        try {
            X509Certificate c = Pem.certificates(Path.of(cfg.tls.cert)).getFirst();
            return (c.getNotAfter().getTime() - System.currentTimeMillis()) / 1000.0;
        } catch (IOException | RuntimeException e) {
            return Double.NaN;
        }
    }
}
