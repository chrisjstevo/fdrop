package io.fdrop.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
    public Db db = new Db();
    public Limits limits = new Limits();

    public static class Http {
        public int port = 8080;
        public String bind = "0.0.0.0";
        public Tls tls = new Tls();
        public DataSize simpleUploadMax = DataSize.ofMiB(64);
        public List<String> trustedProxies = new ArrayList<>();
        /** Public base URL as browsers see it, used for redirects; defaults to http://localhost:{port}. */
        public String publicUrl;
    }

    public static class Tls {
        public boolean enabled = false;
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
        public String issuer = "http://localhost:8180/realms/fdrop";
        public String webClientId = "fdrop-web";
        public String webClientSecret = "";
        public String redirectUri;
        public String sftpClientId = "fdrop-sftp";
        public String sftpClientSecret = "";
        public String apiAudience = "fdrop-api";
        public String userRole = "fdrop-user";
        public String adminRole = "fdrop-admin";
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
    }

    public static class Local {
        public String root = "./data/files";
    }

    public static class S3 {
        public String bucket = "";
        public String prefix = "";
        public String region = "us-east-1";
        public String endpointOverride;
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

    public static class Db {
        public String url = "jdbc:h2:file:./data/meta;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        public String username = "sa";
        public String password = "";
        public int maxPoolSize = 16;
    }

    public static class Limits {
        public DataSize maxFileSize = DataSize.ofGiB(100);
        public DataSize defaultQuota = DataSize.ofGiB(50);
    }
}
