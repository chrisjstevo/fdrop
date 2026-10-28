package io.fdrop.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole of {@code fdrop.yaml}. Every key has a default so a missing file still starts a local dev server;
 * every key can be overridden by an {@code FDROP_SECTION_KEY} environment variable (see {@link ConfigLoader}).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class FdropConfig {

    public Http http = new Http();
    public Management management = new Management();
    public Sftp sftp = new Sftp();
    public Auth auth = new Auth();
    public Forward forward = new Forward();
    public Storage storage = new Storage();
    public Upload upload = new Upload();
    public FileExpiry expiry = new FileExpiry();
    public Search search = new Search();
    public AuditLog audit = new AuditLog();
    public Db db = new Db();
    public Limits limits = new Limits();

    public static class Http {
        /** HTTPS only: there is no plain-HTTP listener (see "Security" in SPEC.md). */
        public int port = 8443;
        public String bind = "0.0.0.0";
        public Tls tls = new Tls();
        public DataSize simpleUploadMax = DataSize.ofMiB(64);
        public List<String> trustedProxies = new ArrayList<>();
        /** Public base URL as browsers see it, used for redirects; defaults to https://localhost:{port}. */
        public String publicUrl;
        public Mtls mtls = new Mtls();
    }

    /** The listener for application accounts with client certificates; uses the {@code http.tls} certificate. */
    public static class Mtls {
        public boolean enabled = false;
        public int port = 8444;
        public String bind = "0.0.0.0";
    }

    /** The certificate of every fdrop listener (web, mTLS, management); required, as fdrop serves HTTPS only. */
    public static class Tls {
        public String cert = "/etc/fdrop/tls.crt";
        public String key = "/etc/fdrop/tls.key";
    }

    public static class Management {
        public int port = 8081;
        public String bind = "0.0.0.0";
        /** Empty means {@code /metrics} needs no token. */
        public String metricsToken = "";
        public List<Double> histogramBuckets = new ArrayList<>(List.of(0.005, 0.025, 0.1, 0.5, 1.0, 5.0, 30.0, 120.0, 600.0));
    }

    public static class Sftp {
        public boolean enabled = true;
        public int port = 2222;
        public String bind = "0.0.0.0";
        public String hostKeyPath = "./data/ssh_host_ed25519_key";
        public String spoolDir = "./data/spool";
        public boolean proxyProtocol = false;
        public int maxSessionsPerUser = 10;
        public String idleTimeout = "10m";
    }

    public static class Auth {
        public Oidc oidc = new Oidc();
        public UserManagement userManagement = new UserManagement();
        public TwoFactor twoFactor = new TwoFactor();
        public Session session = new Session();
        public Applications applications = new Applications();
    }

    public static class Applications {
        /** Keycloak group whose members are application accounts; its role mapping is {@code oidc.appRole}. */
        public String group = "fdrop-applications";
        /** How long a generated password stays valid; {@code 0} means passwords never expire. */
        public String passwordMaxAge = "365d";
        /** Warn this long before a password or certificate expires. */
        public List<String> expiryWarnings = new ArrayList<>(List.of("30d", "7d"));
        public AppMtls mtls = new AppMtls();

        public Duration passwordMaxAgeDuration() {
            return passwordMaxAge == null || passwordMaxAge.isBlank() || passwordMaxAge.equals("0")
                    ? Duration.ZERO : Durations.parse(passwordMaxAge);
        }
    }

    public static class AppMtls {
        /** PEM bundle of CAs that issue application client certificates; pinned certificates need none. */
        public String trustedCas;
        /** Optional CRL files or URLs; when set, by-subject certificates are checked for revocation. */
        public List<String> crls = new ArrayList<>();
        /** Header a TLS-terminating proxy puts the client certificate in, believed only from trusted proxies. */
        public String proxyHeader;
    }

    public static class TwoFactor {
        /** none | admins | all: who must sign in with a second factor. */
        public String require = "admins";
        /** The ACR value Keycloak returns for a two-factor sign-in (the fdrop-web client's ACR-to-LoA map). */
        public String acr = "2fa";
        /** False leaves SSH keys as the only SFTP login for users who need two factors. */
        public boolean sftpPasswordWithCode = true;
    }

    public static class Oidc {
        /** https only. */
        public String issuer = "https://localhost:8180/realms/fdrop";
        /** PEM bundle trusted for calls to Keycloak, besides the JDK's trust store; null for the JDK's alone. */
        public String trustedCas;
        public String webClientId = "fdrop-web";
        public String webClientSecret = "";
        public String redirectUri;
        public String sftpClientId = "fdrop-sftp";
        public String sftpClientSecret = "";
        public String apiAudience = "fdrop-api";
        public String userRole = "fdrop-user";
        public String adminRole = "fdrop-admin";
        public String appRole = "fdrop-app";
    }

    public static class UserManagement {
        public boolean enabled = true;
    }

    public static class Session {
        public String idle = "24h";
        public String absolute = "7d";
        public boolean secureCookie = true;

        public Duration idleDuration() {
            return Durations.parse(idle);
        }

        public Duration absoluteDuration() {
            return Durations.parse(absolute);
        }
    }

    public static class Forward {
        public List<String> allowedHosts = new ArrayList<>();
        /** 32-byte key, base64. Required for forward folders. */
        public String encryptionKey = "";
        public int workers = 4;
        public int perDestinationConcurrency = 2;
        public String retryFor = "24h";
        public String connectTimeout = "30s";
    }

    public static class Storage {
        public String type = "local";
        public Local local = new Local();
        public S3 s3 = new S3();
        public Encryption encryption = new Encryption();
    }

    /** Encryption at rest (see SPEC.md). */
    public static class Encryption {
        /** Encrypt new files. Encrypted files are always decrypted, whatever this says. */
        public boolean enabled = true;
        /** local | aws-kms | vault */
        public String keyProvider = "local";
        public EncryptionLocal local = new EncryptionLocal();
        public AwsKms awsKms = new AwsKms();
        public Vault vault = new Vault();
        public DekCache dekCache = new DekCache();
        /** Serve objects without the encryption header as they are; only while migrating an unencrypted store. */
        public boolean allowPlaintextReads = false;
        public Reencrypt reencrypt = new Reencrypt();
    }

    public static class EncryptionLocal {
        /** {@code id:base64key,…}, 32-byte keys, the first primary. From an env var or secret, never the image. */
        public String keys = "";
    }

    public static class AwsKms {
        public String keyId;
        public String region;
        public String endpointOverride;
    }

    public static class Vault {
        public String address;
        public String transitKey = "fdrop";
        public String tokenFile = "/run/secrets/vault-token";
    }

    public static class DekCache {
        public int maxEntries = 10_000;
        public String ttl = "10m";
    }

    public static class Reencrypt {
        public DataSize bytesPerSecond = DataSize.ofMiB(50);
    }

    public static class Local {
        public String root = "./data/files";
    }

    public static class S3 {
        public String bucket = "";
        public String prefix = "";
        public String region = "us-east-1";
        /** https only. */
        public String endpointOverride;
        /** PEM bundle trusted for calls to the S3 endpoint, besides the JDK's trust store. */
        public String trustedCas;
        public boolean pathStyle = false;
        public String sse;
    }

    public static class Upload {
        public DataSize chunkSize = DataSize.ofMiB(8);
        public String ttl = "24h";
        public int maxParallelChunks = 3;
        public String historyRetention = "365d";
        public DataSize clientHashMax = DataSize.ofGiB(2);
    }

    /** The audit log kept in the DB and searchable by admins (see "Audit log" in SPEC.md). */
    public static class AuditLog {
        /** Events older than this are pruned from the DB; the log files follow logging config. */
        public String retention = "400d";
        /** Events held while the DB is unreachable; beyond it they are only in the log file. */
        public int bufferSize = 10_000;
        /** This node's name in the audit chain; defaults to the host name. Must be unique per running node. */
        public String node;
        /** Separate from search.indexDir; encrypted with the storage KEK when encryption is on. */
        public String indexDir = "./data/audit-index";
        public int exportMax = 100_000;
        public String verifyInterval = "24h";
    }

    /** Full-text search (see "Search" in SPEC.md). Needs the fdrop-search module on the classpath. */
    public static class Search {
        public boolean enabled = true;
        /** Local disk on each node; encrypted with the storage KEK when encryption at rest is on. */
        public String indexDir = "./data/search";
        public boolean includeForwardFolders = false;
        public String commitInterval = "5s";
        public String feedRetention = "7d";
        public int maxResults = 1000;
        public int rateLimitPerMinute = 60;
        public boolean auditQueries = false;
        public SearchExtract extract = new SearchExtract();
    }

    public static class SearchExtract {
        /** fork: a separate JVM per file, with memory and time limits; inprocess: tests only. */
        public String isolation = "fork";
        public int workers = 2;
        public String forkHeap = "512m";
        public String timeout = "60s";
        public DataSize maxFileSize = DataSize.ofMiB(100);
        /** Characters of text taken from each file; the rest is not searchable by its contents. */
        public DataSize maxTextPerFile = DataSize.ofMiB(20);
    }

    /** File expiry (see "File expiry" in SPEC.md). {@code max} and {@code home.*} use the expiry syntax. */
    public static class FileExpiry {
        /** False refuses new expiries and hides the UI controls; existing expiries still run. */
        public boolean enabled = true;
        /** Longest expiry anyone can set, and the cap on folder and home settings (at most 100y). */
        public String max = "10y";
        public ExpiryHome home = new ExpiryHome();
        public String sweepInterval = "30s";
        public int batchSize = 500;
        /** A claim not finished by then is taken over by another node. */
        public String claimTimeout = "5m";
        public int maxDeletesPerSecond = 200;
        public ExpirySftp sftp = new ExpirySftp();
    }

    public static class ExpiryHome {
        /** Expiry for /home uploads when the user has set no default; null means never. */
        @JsonProperty("default")
        public String defaultExpiry;
        /** Longest expiry in any /home; when set, every /home file expires. */
        public String max;
    }

    public static class ExpirySftp {
        /** Control file suffix; reserved on every protocol. */
        public String suffix = ".fdrop-expires";
        /** How long a control file written before its file is kept. */
        public String pendingFor = "10m";
    }

    public static class Db {
        public String url = "jdbc:h2:file:./data/meta;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        public String username = "sa";
        public String password = "";
        public int maxPoolSize = 16;
        public DbTls tls = new DbTls();
    }

    /** TLS with a client certificate to PostgreSQL (see "Metadata DB connection" in SPEC.md); ignored for H2. */
    public static class DbTls {
        /** verify-full (check the server's certificate and host name) or disable (no TLS; logged as a warning). */
        public String mode = "verify-full";
        /** PEM: the CA that signed the DB server's certificate. */
        public String ca;
        /** PEM: fdrop's client certificate (its CN is the DB user). */
        public String cert;
        /** PEM, PKCS#8: the client certificate's private key. */
        public String key;
    }

    public static class Limits {
        public DataSize maxFileSize = DataSize.ofGiB(100);
        public DataSize defaultQuota = DataSize.ofGiB(50);
        public DataSize defaultAppQuota = DataSize.ofGiB(10);
    }
}
