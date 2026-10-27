package io.fdrop.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Callable;

/**
 * {@code fdrop}: uploads files to fdrop as an application account, signing in with a client certificate on fdrop's
 * mTLS listener. Exit codes are listed in {@link CliException}.
 */
@Command(name = "fdrop", mixinStandardHelpOptions = true, versionProvider = FdropCli.Version.class,
        description = "Uploads files to fdrop as an application account, with a client certificate.",
        subcommands = {FdropCli.Upload.class, FdropCli.Watch.class, FdropCli.Whoami.class},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:Uploaded, or whoami succeeded",
                "1:Usage error: bad option, missing file, unreadable certificate or key",
                "2:Authentication failed: TLS handshake refused, untrusted server certificate, or 401",
                "3:Target exists and --overwrite was not given",
                "4:Not allowed: no write permission, method or source refused, quota exceeded",
                "5:Gave up retrying; the session is kept for a later run",
                "6:Server-side failure: assembly failed, hash mismatch or unexpected response",
                "7:watch: another watcher runs with the same settings, or the watched folder went away"})
public final class FdropCli implements Callable<Integer> {

    /** What a run needs from its surroundings; tests supply their own. */
    record Context(Map<String, String> env, PrintStream out, PrintStream err, Backoff.Clock clock, boolean terminal,
                   Path stateDir, boolean installShutdownHook, java.util.function.Consumer<Runnable> onWatchStop) {

        Context(Map<String, String> env, PrintStream out, PrintStream err, Backoff.Clock clock, boolean terminal,
                Path stateDir, boolean installShutdownHook) {
            this(env, out, err, clock, terminal, stateDir, installShutdownHook, null);
        }
    }

    private final Context ctx;

    FdropCli(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public Integer call() {
        new CommandLine(this).usage(ctx.err());
        return CliException.USAGE;
    }

    public static void main(String[] args) {
        Map<String, String> env = System.getenv();
        Context ctx = new Context(env, System.out, System.err, Backoff.Clock.SYSTEM, System.console() != null,
                SessionStore.defaultDir(env), true);
        System.exit(run(args, ctx));
    }

    static int run(String[] args, Context ctx) {
        Settings settings;
        try {
            settings = Settings.load(args, ctx.env());
        } catch (CliException e) {
            ctx.err().println("fdrop: " + e.getMessage());
            return e.code();
        }
        CommandLine cl = new CommandLine(new FdropCli(ctx));
        cl.setDefaultValueProvider(settings);
        cl.setOut(new PrintWriter(ctx.out(), true));
        cl.setErr(new PrintWriter(ctx.err(), true));
        cl.setParameterExceptionHandler((e, a) -> {
            ctx.err().println("fdrop: " + e.getMessage());
            ctx.err().println("Run '" + e.getCommandLine().getCommandSpec().qualifiedName() + " --help' for usage.");
            return CliException.USAGE;
        });
        cl.setExecutionExceptionHandler((e, cmd, parsed) -> {
            if (e instanceof CliException ce) {
                ctx.err().println("fdrop: " + ce.getMessage());
                return ce.code();
            }
            if (e instanceof InterruptedException) {
                ctx.err().println("fdrop: interrupted; the upload session is kept: run the same command to resume");
                return CliException.GAVE_UP;
            }
            ctx.err().println("fdrop: unexpected error: " + e);
            return CliException.SERVER;
        });
        return cl.execute(args);
    }

    static String version() {
        Properties p = new Properties();
        try (InputStream in = FdropCli.class.getResourceAsStream("/fdrop-cli.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // version unknown
        }
        return p.getProperty("version", "dev") + " (commit " + p.getProperty("commit", "unknown") + ")";
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"fdrop-cli " + version()};
        }
    }

    /** How to reach fdrop and which certificate to present. */
    static final class Connection {
        @Option(names = "--url", paramLabel = "URL",
                description = "Base URL of fdrop's mTLS listener, e.g. https://fdrop.example.com:8444 ($FDROP_URL)")
        URI url;

        @Option(names = "--cert", paramLabel = "FILE",
                description = "PEM client certificate chain ($FDROP_CERT)")
        Path cert;

        @Option(names = "--key", paramLabel = "FILE",
                description = "PKCS#8 PEM private key, RSA, EC or Ed25519 ($FDROP_KEY)")
        Path key;

        @Option(names = "--key-password-file", paramLabel = "FILE",
                description = "File holding the password of an encrypted key ($FDROP_KEY_PASSWORD_FILE)")
        Path keyPasswordFile;

        @Option(names = "--keystore", paramLabel = "FILE",
                description = "PKCS#12 file with certificate and key, instead of --cert and --key ($FDROP_KEYSTORE)")
        Path keystore;

        @Option(names = "--keystore-password-file", paramLabel = "FILE",
                description = "File holding the PKCS#12 password ($FDROP_KEYSTORE_PASSWORD_FILE)")
        Path keystorePasswordFile;

        @Option(names = "--ca", paramLabel = "FILE",
                description = "Extra PEM CA bundle to trust for the server certificate ($FDROP_CA)")
        Path ca;

        @Option(names = "--config", paramLabel = "FILE",
                description = "Properties file with any of these options (default ~/.config/fdrop/cli.properties;"
                        + " $FDROP_CONFIG_FILE). Must not be readable by others")
        Path config;

        Credentials credentials() {
            if (keystore != null) {
                if (cert != null || key != null) {
                    throw new CliException(CliException.USAGE, "give either --keystore or --cert and --key, not both");
                }
                return Credentials.fromPkcs12(keystore, Settings.password(keystorePasswordFile));
            }
            if (cert == null || key == null) {
                throw new CliException(CliException.USAGE, "a client certificate is required: --cert and --key, or"
                        + " --keystore");
            }
            return Credentials.fromPem(cert, key, Settings.password(keyPasswordFile));
        }

        Api api(Credentials c) {
            if (url == null) {
                throw new CliException(CliException.USAGE, "--url is required (or $FDROP_URL)");
            }
            return new Api(url, Tls.context(c, ca), "fdrop-cli/" + version().split(" ")[0]);
        }
    }

    @Command(name = "upload", mixinStandardHelpOptions = true,
            description = "Uploads one file. A remote path ending in / keeps the local file name.")
    static final class Upload implements Callable<Integer> {
        @ParentCommand
        FdropCli parent;

        @Mixin
        Connection connection;

        @Parameters(index = "0", paramLabel = "LOCAL-FILE", description = "The file to upload")
        Path file;

        @Parameters(index = "1", paramLabel = "REMOTE-PATH",
                description = "Where in fdrop: /home/, /shared/<folder>/…, /forward/<folder>/, or a full file path")
        String remote;

        @Option(names = "--overwrite", description = "Replace an existing file at the target")
        boolean overwrite;

        @Option(names = "--expires-in", paramLabel = "EXPIRY",
                description = "Delete the file this long after it arrives, e.g. '6 days', 2weeks or never; without it"
                        + " the folder's default applies")
        String expiresIn;

        @Option(names = "--parallel", paramLabel = "N", defaultValue = "3",
                description = "Chunks sent at once, 1 to 8 (default ${DEFAULT-VALUE})")
        int parallel;

        @Option(names = "--retry-for", paramLabel = "DURATION", defaultValue = "1h",
                description = "How long to keep retrying network errors (default ${DEFAULT-VALUE})")
        String retryFor;

        @Option(names = "--no-resume", description = "Start a new upload session instead of resuming a saved one")
        boolean noResume;

        @Option(names = "--quiet", description = "No progress output")
        boolean quiet;

        @Option(names = "--json", description = "Print the result as one line of JSON")
        boolean json;

        @Override
        public Integer call() throws InterruptedException {
            Context ctx = parent.ctx;
            if (parallel < 1 || parallel > 8) {
                throw new CliException(CliException.USAGE, "--parallel must be between 1 and 8");
            }
            Duration giveUpAfter = Settings.duration(retryFor);
            String expiry = expiresIn == null ? null : ExpirySyntax.canonical(expiresIn);
            Credentials creds = connection.credentials();
            Api api = connection.api(creds);
            Uploader.Progress progress = ctx.terminal() && !quiet && !json
                    ? new TerminalProgress(ctx.err(), file.getFileName().toString()) : Uploader.Progress.NONE;
            Uploader uploader = new Uploader(api, new SessionStore(ctx.stateDir()), ctx.clock(), new Random(),
                    progress);
            Thread hook = Thread.ofPlatform().unstarted(() -> ctx.err().println(
                    "\nfdrop: interrupted; the upload session is kept: run the same command again to resume"));
            if (ctx.installShutdownHook()) {
                Runtime.getRuntime().addShutdownHook(hook);
            }
            Uploader.Result r;
            try {
                r = uploader.upload(new Uploader.Options(file, remote, overwrite, parallel, giveUpAfter, !noResume,
                        creds.fingerprint(), expiry));
            } finally {
                if (ctx.installShutdownHook()) {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook);
                    } catch (IllegalStateException e) {
                        // already shutting down
                    }
                }
                if (progress instanceof TerminalProgress) {
                    ctx.err().println();
                }
            }
            if (json) {
                ObjectNode n = Api.JSON.createObjectNode();
                n.put("path", r.path());
                n.put("size", r.size());
                n.put("sha256", r.sha256());
                n.put("uploadId", r.uploadId());
                n.put("resumed", r.resumed());
                if (r.file().hasNonNull("expiresAt")) {
                    n.put("expiresAt", r.file().get("expiresAt").asText());
                    n.put("expiresIn", r.file().path("expiresIn").asText(null));
                }
                ctx.out().println(n);
            } else if (!quiet) {
                ctx.out().println("Uploaded " + r.path() + " (" + bytes(r.size()) + ", sha256 " + r.sha256() + ")"
                        + (r.resumed() ? ", resumed" : "")
                        + (r.file().hasNonNull("expiresAt") ? "; expires " + r.file().get("expiresAt").asText() : "")
                        + (r.path().startsWith("/forward/") ? "; queued for forwarding" : ""));
            }
            return CliException.OK;
        }
    }

    @Command(name = "watch", mixinStandardHelpOptions = true,
            description = {"Listens to one local folder and uploads each new file into one folder on the server,"
                    + " until stopped.",
                    "Example: fdrop watch /srv/outbox /shared/finance/incoming/ --recursive"})
    static final class Watch implements Callable<Integer> {
        @ParentCommand
        FdropCli parent;

        @Mixin
        Connection connection;

        @Parameters(index = "0", arity = "0..1", paramLabel = "LOCAL-DIR",
                description = "The local folder to listen to (or --local-dir)")
        Path localArg;

        @Parameters(index = "1", arity = "0..1", paramLabel = "REMOTE-DIR",
                description = "The server folder to upload into, ending in /, e.g. /shared/finance/incoming/ (or"
                        + " --remote-dir)")
        String remoteArg;

        @Option(names = "--local-dir", paramLabel = "DIR",
                description = "The local folder to listen to ($FDROP_WATCH_LOCAL_DIR)")
        Path localDir;

        @Option(names = "--remote-dir", paramLabel = "DIR",
                description = "The server folder to upload into, ending in / ($FDROP_WATCH_REMOTE_DIR)")
        String remoteDir;

        @Option(names = "--recursive", description = "Watch subfolders too, keeping their structure on the server")
        boolean recursive;

        @Option(names = "--include", paramLabel = "GLOB", split = ",",
                description = "Only upload files whose name or relative path matches; repeatable")
        List<String> include = new ArrayList<>();

        @Option(names = "--ignore", paramLabel = "GLOB", split = ",",
                description = "Never upload matching files; repeatable, replaces the defaults (hidden files and"
                        + " *.tmp, *.part, *.partial, *.crdownload, *~)")
        List<String> ignore;

        @Option(names = "--settle", paramLabel = "DURATION", defaultValue = "10s",
                description = "How long size and modified time must stay the same (default ${DEFAULT-VALUE})")
        String settle;

        @Option(names = "--ready-marker", paramLabel = "SUFFIX",
                description = "Upload a file only when <name><SUFFIX> exists, e.g. .done")
        String readyMarker;

        @Option(names = "--after-upload", paramLabel = "ACTION", defaultValue = "keep",
                description = "keep, delete or move=<dir> (default ${DEFAULT-VALUE})")
        String afterUpload;

        @Option(names = "--on-failure", paramLabel = "ACTION", defaultValue = "keep",
                description = "For files the server refuses: keep or move=<dir> (default ${DEFAULT-VALUE})")
        String onFailure;

        @Option(names = "--max-files", paramLabel = "N", defaultValue = "2",
                description = "Files uploaded at once, 1 to 8 (default ${DEFAULT-VALUE})")
        int maxFiles;

        @Option(names = "--rescan", paramLabel = "DURATION", defaultValue = "5m",
                description = "Full scan interval, to catch missed events (default ${DEFAULT-VALUE})")
        String rescan;

        @Option(names = "--poll", paramLabel = "DURATION",
                description = "Only scan, every DURATION, without file system events (network file systems)")
        String poll;

        @Option(names = "--status-file", paramLabel = "FILE", description = "Where to write the status JSON")
        Path statusFile;

        @Option(names = "--shutdown-timeout", paramLabel = "DURATION", defaultValue = "30s",
                description = "How long uploads may finish after a stop signal (default ${DEFAULT-VALUE})")
        String shutdownTimeout;

        @Option(names = "--overwrite", description = "Replace existing files on the server")
        boolean overwrite;

        @Option(names = "--expires-in", paramLabel = "EXPIRY",
                description = "Delete each file this long after it arrives; without it the folder's default applies")
        String expiresIn;

        @Option(names = "--parallel", paramLabel = "N", defaultValue = "3",
                description = "Chunks of one file sent at once, 1 to 8 (default ${DEFAULT-VALUE})")
        int parallel;

        @Option(names = "--retry-for", paramLabel = "DURATION", defaultValue = "1h",
                description = "How long one upload keeps retrying network errors (default ${DEFAULT-VALUE})")
        String retryFor;

        @Option(names = "--json", description = "Log one JSON object per line")
        boolean json;

        /** Set while running, so a signal can stop it. */
        volatile Watcher watcher;

        @Override
        public Integer call() throws InterruptedException {
            Context ctx = parent.ctx;
            WatchConfig cfg = config();
            Credentials creds = connection.credentials();
            Api api = new Api(connection.url == null ? missingUrl() : connection.url, Tls.context(creds, connection.ca),
                    "fdrop-cli/" + version().split(" ")[0] + " (watch)");
            checkServer(api, cfg.remoteDir());

            Path state = ctx.stateDir().resolveSibling("watch").resolve(watchId(api, creds, cfg));
            try {
                Files.createDirectories(state, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } catch (IOException e) {
                throw new CliException(CliException.USAGE, "cannot create " + state + ": " + e.getMessage());
            }
            WatchLog log = new WatchLog(ctx.err(), json);
            Uploader uploader = new Uploader(api, new SessionStore(ctx.stateDir()), ctx.clock(), new Random(),
                    Uploader.Progress.NONE);
            Watcher.Uploads uploads = new Watcher.Uploads() {
                @Override
                public Uploader.Result upload(Path file, String remotePath, BooleanSupplier unchanged)
                        throws InterruptedException {
                    return uploader.upload(new Uploader.Options(file, remotePath, cfg.overwrite(), cfg.parallel(),
                            cfg.retryFor(), true, creds.fingerprint(), cfg.expiresIn(), unchanged));
                }

                @Override
                public void mkdir(String remotePath) {
                    Api.Reply r;
                    try {
                        r = api.call("POST", "/api/v1/files/mkdir", Map.of("path", remotePath));
                    } catch (Api.Retryable e) {
                        throw new CliException(CliException.GAVE_UP, "cannot create " + remotePath + ": "
                                + e.getMessage());
                    }
                    if (r.status() != 409) {
                        Api.expect(r, "creating " + remotePath);
                    }
                }
            };
            try (WatchLock lock = WatchLock.acquire(state.resolve("lock"));
                 WatchLedger ledger = new WatchLedger(state.resolve("ledger.jsonl"), cfg.localDir())) {
                watcher = new Watcher(cfg, uploads, ledger, log, ctx.clock());
                if (ctx.onWatchStop() != null) {
                    ctx.onWatchStop().accept(watcher::stop);
                } else if (ctx.installShutdownHook()) {
                    Signals.onStop(watcher::stop);
                }
                watcher.run();
            }
            return CliException.OK;
        }

        private static URI missingUrl() {
            throw new CliException(CliException.USAGE, "--url is required (or $FDROP_URL)");
        }

        WatchConfig config() {
            if (localArg != null && localDir != null && !localArg.equals(localDir)) {
                throw new CliException(CliException.USAGE, "the local folder is given twice, as " + localArg + " and"
                        + " --local-dir " + localDir);
            }
            if (remoteArg != null && remoteDir != null && !remoteArg.equals(remoteDir)) {
                throw new CliException(CliException.USAGE, "the server folder is given twice, as " + remoteArg
                        + " and --remote-dir " + remoteDir);
            }
            return WatchConfig.check(localArg != null ? localArg : localDir, remoteArg != null ? remoteArg : remoteDir,
                    recursive, include, ignore == null ? WatchConfig.DEFAULT_IGNORE : ignore, Settings.duration(settle),
                    readyMarker, WatchConfig.Action.parse("--after-upload", afterUpload, true),
                    WatchConfig.Action.parse("--on-failure", onFailure, false), maxFiles, Settings.duration(rescan),
                    poll == null ? null : Settings.duration(poll), statusFile, Settings.duration(shutdownTimeout),
                    overwrite, expiresIn == null ? null : ExpirySyntax.canonical(expiresIn), parallel,
                    Settings.duration(retryFor));
        }

        /** The certificate works, and the server folder exists, is a folder and the account may write to it. */
        private static void checkServer(Api api, String remote) {
            String folder = remote.length() > 1 ? remote.substring(0, remote.length() - 1) : remote;
            Api.Reply r;
            try {
                api.get("/api/v1/me");
                r = api.call("GET", "/api/v1/files?path=" + URLEncoder.encode(folder, StandardCharsets.UTF_8), null);
            } catch (Api.Retryable e) {
                throw new CliException(CliException.GAVE_UP, "cannot reach " + api.base() + ": " + e.getMessage());
            }
            if (r.status() == 404 || r.status() == 403) {
                throw new CliException(CliException.NOT_ALLOWED, "cannot upload into " + remote + ": " + r.detail());
            }
            com.fasterxml.jackson.databind.JsonNode self = Api.expect(r, "checking " + remote).path("self");
            // AREA (/home), DIR, SHARED_FOLDER and FORWARD_FOLDER are all folders
            if ("FILE".equals(self.path("type").asText("FILE"))) {
                throw new CliException(CliException.NOT_ALLOWED, remote + " is not a folder on the server");
            }
            if (!self.path("writable").asBoolean(false)) {
                throw new CliException(CliException.NOT_ALLOWED, "this account cannot write to " + remote);
            }
        }

        /** The state folder's name: these settings, so another pair of folders gets its own ledger and lock. */
        private static String watchId(Api api, Credentials creds, WatchConfig cfg) {
            return SessionStore.key(api.base().toString(), creds.fingerprint(), cfg.localDir().toString(),
                    0, 0, cfg.remoteDir()).substring(0, 24);
        }
    }

    /** {@code SIGTERM} and {@code SIGINT} stop a watch cleanly, so it exits 0 rather than being killed. */
    static final class Signals {
        private Signals() {
        }

        /** Through {@code sun.misc.Signal}, found reflectively: there is no public API for handling a signal. */
        static void onStop(Runnable stop) {
            try {
                Class<?> signal = Class.forName("sun.misc.Signal");
                Class<?> handler = Class.forName("sun.misc.SignalHandler");
                Object h = java.lang.reflect.Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[]{handler},
                        (proxy, method, args) -> {
                            if (method.getName().equals("handle")) {
                                stop.run();
                            }
                            return null;
                        });
                for (String name : new String[]{"TERM", "INT"}) {
                    signal.getMethod("handle", signal, handler)
                            .invoke(null, signal.getConstructor(String.class).newInstance(name), h);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // no signal handling here: a stop signal ends the process without the clean stop
            }
        }
    }

    @Command(name = "whoami", mixinStandardHelpOptions = true,
            description = "Checks the certificate and connection, and prints the account fdrop sees.")
    static final class Whoami implements Callable<Integer> {
        @ParentCommand
        FdropCli parent;

        @Mixin
        Connection connection;

        @Option(names = "--json", description = "Print the result as one line of JSON")
        boolean json;

        @Override
        public Integer call() {
            Context ctx = parent.ctx;
            Api api = connection.api(connection.credentials());
            com.fasterxml.jackson.databind.JsonNode me;
            try {
                me = api.get("/api/v1/me");
            } catch (Api.Retryable e) {
                throw new CliException(CliException.GAVE_UP, "cannot reach " + api.base() + ": " + e.getMessage());
            }
            String kind = me.path("kind").asText("user");
            if (json) {
                ObjectNode n = Api.JSON.createObjectNode();
                n.put("username", me.path("username").asText());
                n.put("kind", kind);
                n.put("url", api.base().toString());
                ctx.out().println(n);
            } else {
                ctx.out().println(me.path("username").asText() + " (" + (kind.equals("application")
                        ? "application account" : kind) + ") at " + api.base());
            }
            return CliException.OK;
        }
    }

    /** {@code 1.4 GB}: for the result line. */
    static String bytes(long n) {
        if (n < 1024) {
            return n + " bytes";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = n;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }

    /** {@code report.csv: 42% (1.2 GB of 2.9 GB)}, rewritten in place, only when the percentage changes. */
    static final class TerminalProgress implements Uploader.Progress {
        private final PrintStream err;
        private final String name;
        private int last = -1;

        TerminalProgress(PrintStream err, String name) {
            this.err = err;
            this.name = name;
        }

        @Override
        public synchronized void update(long acknowledged, long total) {
            int pct = total == 0 ? 100 : (int) (acknowledged * 100 / total);
            if (pct != last) {
                last = pct;
                err.print("\r" + name + ": " + pct + "% (" + bytes(acknowledged) + " of " + bytes(total) + ")");
                err.flush();
            }
        }
    }
}
