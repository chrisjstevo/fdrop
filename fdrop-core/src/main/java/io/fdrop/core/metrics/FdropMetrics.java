package io.fdrop.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All fdrop meters. Names and labels are part of the monitoring contract in the spec; {@link #ALLOWED_LABELS}
 * is checked by a test so no metric can quietly gain a label carrying user data or unbounded values.
 */
public final class FdropMetrics {

    /** Every fdrop_ metric and the labels it may carry. */
    public static final Map<String, Set<String>> ALLOWED_LABELS = Map.ofEntries(
            Map.entry("fdrop_build_info", Set.of("version", "commit", "java_version")),
            Map.entry("fdrop_http_requests_seconds", Set.of("method", "route", "status")),
            Map.entry("fdrop_http_connections_active", Set.of()),
            Map.entry("fdrop_sftp_sessions_active", Set.of()),
            Map.entry("fdrop_sftp_auth_total", Set.of("method", "result")),
            Map.entry("fdrop_logins_total", Set.of("result")),
            Map.entry("fdrop_two_factor_total", Set.of("result")),
            Map.entry("fdrop_transfer_bytes_total", Set.of("protocol", "direction")),
            Map.entry("fdrop_upload_sessions", Set.of("state")),
            Map.entry("fdrop_upload_chunks_total", Set.of("result")),
            Map.entry("fdrop_upload_assembly_seconds", Set.of("backend")),
            Map.entry("fdrop_uploads_completed_total", Set.of("protocol", "went_to")),
            Map.entry("fdrop_storage_operation_seconds", Set.of("backend", "operation", "outcome")),
            Map.entry("fdrop_storage_used_bytes", Set.of()),
            Map.entry("fdrop_forward_jobs", Set.of("state", "destination")),
            Map.entry("fdrop_forward_send_seconds", Set.of("destination", "outcome")),
            Map.entry("fdrop_forward_bytes_total", Set.of("destination")),
            Map.entry("fdrop_forward_failures_total", Set.of("destination", "reason")),
            Map.entry("fdrop_keycloak_request_seconds", Set.of("operation", "outcome")),
            Map.entry("fdrop_users", Set.of("kind")),
            Map.entry("fdrop_app_auth_total", Set.of("protocol", "method", "result")),
            Map.entry("fdrop_app_credentials_expiring", Set.of("type", "within")),
            Map.entry("fdrop_folders", Set.of("type")),
            Map.entry("fdrop_expiry_set_total", Set.of("protocol", "source")),
            Map.entry("fdrop_expiry_refused_total", Set.of("protocol", "reason")),
            Map.entry("fdrop_expiry_files", Set.of()),
            Map.entry("fdrop_expiry_overdue_files", Set.of()),
            Map.entry("fdrop_expiry_deleted_total", Set.of("backend", "outcome")),
            Map.entry("fdrop_expiry_delete_lag_seconds", Set.of("backend")));

    private final PrometheusMeterRegistry registry;
    private final Duration[] buckets;
    private final AtomicLong httpConnections = new AtomicLong();
    private final AtomicLong sftpSessions = new AtomicLong();
    private final AtomicLong storageUsed = new AtomicLong();
    private final AtomicLong expiryFiles = new AtomicLong();
    private final AtomicLong expiryOverdue = new AtomicLong();
    private final MultiGauge users;
    private final MultiGauge appCredentialsExpiring;
    private final MultiGauge folders;
    private final MultiGauge uploadSessions;
    private final MultiGauge forwardJobs;

    public FdropMetrics(List<Double> bucketSeconds) {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.buckets = bucketSeconds.stream().map(s -> Duration.ofNanos((long) (s * 1_000_000_000L)))
                .toArray(Duration[]::new);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);

        Package pkg = FdropMetrics.class.getPackage();
        String version = pkg.getImplementationVersion() == null ? "dev" : pkg.getImplementationVersion();
        String commit = System.getProperty("fdrop.commit", System.getenv().getOrDefault("FDROP_COMMIT", "unknown"));
        Gauge.builder("fdrop.build.info", () -> 1).tags("version", version, "commit", commit, "java_version",
                Runtime.version().toString()).register(registry);

        Gauge.builder("fdrop.http.connections.active", httpConnections, AtomicLong::get).register(registry);
        Gauge.builder("fdrop.sftp.sessions.active", sftpSessions, AtomicLong::get).register(registry);
        Gauge.builder("fdrop.storage.used", storageUsed, AtomicLong::get).baseUnit("bytes").register(registry);
        Gauge.builder("fdrop.expiry.files", expiryFiles, AtomicLong::get).register(registry);
        Gauge.builder("fdrop.expiry.overdue.files", expiryOverdue, AtomicLong::get).register(registry);
        users = MultiGauge.builder("fdrop.users").register(registry);
        appCredentialsExpiring = MultiGauge.builder("fdrop.app.credentials.expiring").register(registry);
        folders = MultiGauge.builder("fdrop.folders").register(registry);
        uploadSessions = MultiGauge.builder("fdrop.upload.sessions").register(registry);
        forwardJobs = MultiGauge.builder("fdrop.forward.jobs").register(registry);
    }

    public MeterRegistry registry() {
        return registry;
    }

    /** Prometheus text, or OpenMetrics when {@code accept} asks for it. */
    public String scrape(String accept) {
        if (accept != null && accept.contains("application/openmetrics-text")) {
            return registry.scrape("application/openmetrics-text; version=1.0.0; charset=utf-8");
        }
        return registry.scrape();
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).serviceLevelObjectives(buckets).register(registry);
    }

    public void httpRequest(String method, String route, int status, long nanos) {
        timer("fdrop.http.requests", "method", method, "route", route, "status", Integer.toString(status))
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public AtomicLong httpConnections() {
        return httpConnections;
    }

    public AtomicLong sftpSessions() {
        return sftpSessions;
    }

    public void sftpAuth(String method, boolean success) {
        Counter.builder("fdrop.sftp.auth").tags("method", method, "result", success ? "success" : "failure")
                .register(registry).increment();
    }

    /** An application account login: {@code success}, {@code failure}, {@code method_not_allowed}, {@code source_refused}. */
    public void appAuth(String protocol, String method, String result) {
        Counter.builder("fdrop.app.auth").tags("protocol", protocol, "method", method, "result", result)
                .register(registry).increment();
    }

    /** Application passwords and certificates expiring within each warning window, by [type, within]. */
    public void appCredentialsExpiring(Map<List<String>, Long> counts) {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        counts.forEach((k, v) -> rows.add(MultiGauge.Row.of(Tags.of("type", k.get(0), "within", k.get(1)), v)));
        appCredentialsExpiring.register(rows, true);
    }

    public void login(String result) {
        Counter.builder("fdrop.logins").tags("result", result).register(registry).increment();
    }

    /** A sign-in that needed two factors: {@code satisfied}, {@code step_up} or {@code refused}. */
    public void twoFactor(String result) {
        Counter.builder("fdrop.two.factor").tags("result", result).register(registry).increment();
    }

    public void transfer(String protocol, String direction, long bytes) {
        if (bytes > 0) {
            Counter.builder("fdrop.transfer").baseUnit("bytes").tags("protocol", protocol, "direction", direction)
                    .register(registry).increment(bytes);
        }
    }

    public void chunk(String result) {
        Counter.builder("fdrop.upload.chunks").tags("result", result).register(registry).increment();
    }

    public void assembly(String backend, long nanos) {
        timer("fdrop.upload.assembly", "backend", backend).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void uploadCompleted(String protocol, String wentTo) {
        Counter.builder("fdrop.uploads.completed").tags("protocol", protocol, "went_to", wentTo)
                .register(registry).increment();
    }

    public void storage(String backend, String operation, boolean ok, long nanos) {
        timer("fdrop.storage.operation", "backend", backend, "operation", operation, "outcome", ok ? "ok" : "error")
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void forwardSend(String destination, boolean ok, long nanos) {
        timer("fdrop.forward.send", "destination", destination, "outcome", ok ? "ok" : "error")
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void forwardBytes(String destination, long bytes) {
        Counter.builder("fdrop.forward").baseUnit("bytes").tags("destination", destination).register(registry)
                .increment(bytes);
    }

    public void forwardFailure(String destination, String reason) {
        Counter.builder("fdrop.forward.failures").tags("destination", destination, "reason", reason)
                .register(registry).increment();
    }

    public void keycloakCall(String operation, boolean ok, long nanos) {
        timer("fdrop.keycloak.request", "operation", operation, "outcome", ok ? "ok" : "error")
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * An expiry set, changed or cleared. {@code protocol} is http, sftp or system (forward keep); {@code source} is
     * upload, folder_default, home_default, changed, forward_keep or cleared.
     */
    public void expirySet(String protocol, String source) {
        Counter.builder("fdrop.expiry.set").tags("protocol", protocol, "source", source).register(registry)
                .increment();
    }

    /** An expiry value refused: {@code syntax}, {@code too_long}, {@code not_allowed} or {@code conflicting}. */
    public void expiryRefused(String protocol, String reason) {
        Counter.builder("fdrop.expiry.refused").tags("protocol", protocol, "reason", reason).register(registry)
                .increment();
    }

    /** One expiry delete attempt: {@code deleted}, {@code already_gone} or {@code error}. */
    public void expiryDeleted(String backend, String outcome) {
        Counter.builder("fdrop.expiry.deleted").tags("backend", backend, "outcome", outcome).register(registry)
                .increment();
    }

    /** Time from a file's expiry to its deletion from storage. */
    public void expiryDeleteLag(String backend, long millis) {
        timer("fdrop.expiry.delete.lag", "backend", backend).record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    public void expiryGauges(long files, long overdue) {
        expiryFiles.set(files);
        expiryOverdue.set(overdue);
    }

    /** Snapshot values refreshed in the background from the metadata DB. */
    public record Snapshot(Map<String, Long> usersByKind, long storageUsed, Map<String, Long> foldersByType,
                           Map<String, Long> uploadSessionsByState, Map<List<String>, Long> forwardJobs) {
    }

    public void update(Snapshot s) {
        List<MultiGauge.Row<?>> byKind = new ArrayList<>();
        s.usersByKind().forEach((kind, v) -> byKind.add(MultiGauge.Row.of(Tags.of("kind", kind), v)));
        users.register(byKind, true);
        storageUsed.set(s.storageUsed());
        List<MultiGauge.Row<?>> f = new ArrayList<>();
        s.foldersByType().forEach((k, v) -> f.add(MultiGauge.Row.of(Tags.of("type", k), v)));
        folders.register(f, true);
        List<MultiGauge.Row<?>> u = new ArrayList<>();
        s.uploadSessionsByState().forEach((k, v) -> u.add(MultiGauge.Row.of(Tags.of("state", k), v)));
        uploadSessions.register(u, true);
        List<MultiGauge.Row<?>> j = new ArrayList<>();
        s.forwardJobs().forEach((k, v) -> j.add(MultiGauge.Row.of(Tags.of("state", k.get(0), "destination", k.get(1)), v)));
        forwardJobs.register(j, true);
    }
}
