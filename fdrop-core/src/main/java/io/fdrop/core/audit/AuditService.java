package io.fdrop.core.audit;

import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.service.DestinationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The admin side of the audit log: browsing from the DB, searching through the audit index, export, chain checks, and
 * the nightly retention prune (see "Audit log" in SPEC.md). Every method checks the caller is an admin; the HTTP layer
 * also requires a two-factor session for every admin route.
 */
public final class AuditService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_PAGE = 200;
    /** Follow live refreshes every 5 s; one {@code audit.viewed} per admin in this window is enough. */
    private static final long VIEWED_EVERY_MS = 5 * 60_000;

    /** What the page asks for; null or empty means no filter. Times are epoch millis. */
    public record Filter(String q, List<String> groups, List<String> events, String user, String ip,
                         List<String> outcomes, Long from, Long to) {

        /** Anything beyond a time range needs the index. */
        public boolean needsIndex() {
            return (q != null && !q.isBlank()) || !empty(groups) || !empty(events) || (user != null && !user.isBlank())
                    || (ip != null && !ip.isBlank()) || !empty(outcomes);
        }

        private static boolean empty(List<String> l) {
            return l == null || l.isEmpty();
        }
    }

    /** One page of events, newest first; {@code indexedUpTo} is set when the index answered. */
    public record Page(long total, List<AuditStore.Row> events, Map<String, Map<String, Long>> facets,
                       Long indexedUpTo, String nextCursor) {
    }

    public record Export(String filename, String contentType, long count, ReadableByteChannel body) {
    }

    private final FdropConfig.AuditLog cfg;
    private final AuditStore store;
    private final AuditIndex index;
    private final FdropMetrics metrics;
    private final Clock clock;
    private final Map<String, Long> lastViewed = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public AuditService(FdropConfig.AuditLog cfg, AuditStore store, AuditIndex index, FdropMetrics metrics,
                        Clock clock) {
        this.cfg = cfg;
        this.store = store;
        this.index = index;
        this.metrics = metrics;
        this.clock = clock;
    }

    public AuditIndex index() {
        return index;
    }

    /** Starts the index and the nightly prune and periodic chain check. */
    public synchronized void start() {
        index.start();
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("audit-maintenance")
                .daemon().unstarted(r));
        scheduler.scheduleWithFixedDelay(() -> safely("prune", this::prune), 10, 24 * 60, TimeUnit.MINUTES);
        long verifyMs = Durations.parse(cfg.verifyInterval).toMillis();
        scheduler.scheduleWithFixedDelay(() -> safely("verify", store::verify), 15 * 60_000L, verifyMs,
                TimeUnit.MILLISECONDS);
    }

    private static void safely(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("audit {} failed: {}", what, e.toString());
        }
    }

    /** Removes events older than {@code audit.retention}. */
    public int prune() {
        long cutoff = clock.millis() - Durations.parse(cfg.retention).toMillis();
        return store.prune(cutoff);
    }

    // ---- reading ----

    public Page list(Principal admin, Filter f, String cursor, int limit, String ip) {
        DestinationService.requireAdmin(admin);
        int n = Math.max(1, Math.min(MAX_PAGE, limit));
        Long before = cursor(cursor);
        long start = System.nanoTime();
        if (!f.needsIndex()) {
            List<AuditStore.Row> rows = store.page(f.from(), f.to(), before, n + 1);
            if (before == null && !seenRecently(admin)) {
                Audit.log("audit.viewed", "user", admin.username(), "ip", ip, "protocol", "HTTP");
            }
            return page(store.count(f.from(), f.to()), rows, n, store.facets(f.from(), f.to()), null);
        }
        AuditIndex.Result r;
        try {
            r = index.search(query(f, before, n + 1));
        } catch (FdropException e) {
            metrics.auditQuery(e.status() == 400 ? "invalid" : "error", 0);
            throw e;
        }
        metrics.auditQuery("ok", System.nanoTime() - start);
        if (before == null) {
            Audit.log("audit.searched", "user", admin.username(), "ip", ip, "protocol", "HTTP", "query", f.q(),
                    "filters", describe(f), "results", r.total());
        }
        return page(r.total(), store.bySeq(r.seqs()), n, r.facets(), r.indexedUpTo());
    }

    private boolean seenRecently(Principal admin) {
        long now = clock.millis();
        Long last = lastViewed.put(admin.sub(), now);
        return last != null && now - last < VIEWED_EVERY_MS;
    }

    private static Page page(long total, List<AuditStore.Row> rows, int n, Map<String, Map<String, Long>> facets,
                             Long indexedUpTo) {
        String next = null;
        if (rows.size() > n) {
            rows = rows.subList(0, n);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(("seq:" + rows.get(n - 1).seq())
                    .getBytes(StandardCharsets.UTF_8));
        }
        return new Page(total, List.copyOf(rows), facets, indexedUpTo, next);
    }

    private static Long cursor(String c) {
        if (c == null || c.isBlank()) {
            return null;
        }
        try {
            String s = new String(Base64.getUrlDecoder().decode(c), StandardCharsets.UTF_8);
            if (s.startsWith("seq:")) {
                return Long.parseLong(s.substring(4));
            }
        } catch (IllegalArgumentException e) {
            // falls through
        }
        throw FdropException.badRequest("invalid cursor");
    }

    private static AuditIndex.Query query(Filter f, Long before, int limit) {
        return new AuditIndex.Query(f.q(), orEmpty(f.groups()), orEmpty(f.events()), blankToNull(f.user()),
                blankToNull(f.ip()), orEmpty(f.outcomes()), f.from(), f.to(), before, limit);
    }

    private static List<String> orEmpty(List<String> l) {
        return l == null ? List.of() : l;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** The filters as one line for the audit event about the search. */
    private static String describe(Filter f) {
        List<String> parts = new ArrayList<>();
        if (f.groups() != null && !f.groups().isEmpty()) {
            parts.add("group=" + String.join(",", f.groups()));
        }
        if (f.events() != null && !f.events().isEmpty()) {
            parts.add("event=" + String.join(",", f.events()));
        }
        if (f.user() != null && !f.user().isBlank()) {
            parts.add("user=" + f.user());
        }
        if (f.ip() != null && !f.ip().isBlank()) {
            parts.add("ip=" + f.ip());
        }
        if (f.outcomes() != null && !f.outcomes().isEmpty()) {
            parts.add("outcome=" + String.join(",", f.outcomes()));
        }
        if (f.from() != null) {
            parts.add("from=" + Instant.ofEpochMilli(f.from()));
        }
        if (f.to() != null) {
            parts.add("to=" + Instant.ofEpochMilli(f.to()));
        }
        return String.join(" ", parts);
    }

    public AuditStore.Row get(Principal admin, String id) {
        DestinationService.requireAdmin(admin);
        return store.find(id).orElseThrow(() -> FdropException.notFound("no such audit event"));
    }

    public List<Object[]> events(Principal admin) {
        DestinationService.requireAdmin(admin);
        return store.events();
    }

    public List<AuditStore.ChainCheck> verify(Principal admin, String ip) {
        DestinationService.requireAdmin(admin);
        List<AuditStore.ChainCheck> out = store.verify();
        Audit.log("audit.verified", "user", admin.username(), "ip", ip, "protocol", "HTTP", "nodes", out.size(),
                "intact", out.stream().allMatch(AuditStore.ChainCheck::intact));
        return out;
    }

    public AuditIndex.Status indexStatus(Principal admin) {
        DestinationService.requireAdmin(admin);
        return index.status();
    }

    public void rebuildIndex(Principal admin, String ip) {
        DestinationService.requireAdmin(admin);
        index.rebuild();
        Audit.log("audit.index.rebuild", "user", admin.username(), "ip", ip, "protocol", "HTTP");
    }

    // ---- export ----

    /**
     * The selection as JSON Lines (the exact log lines) or CSV, newest first, streamed from a background thread.
     * Refused when it holds more than {@code audit.exportMax} events.
     */
    public Export export(Principal admin, Filter f, String format, String ip) {
        DestinationService.requireAdmin(admin);
        boolean csv = "csv".equals(format);
        if (!csv && format != null && !format.equals("jsonl")) {
            throw FdropException.badRequest("format must be jsonl or csv");
        }
        int max = cfg.exportMax;
        List<Long> seqs = null;
        Long upTo = null;
        long count;
        if (f.needsIndex()) {
            AuditIndex.Result r = index.search(query(f, null, max + 1));
            count = r.total();
            seqs = r.seqs();
        } else {
            // what is in the DB now: events written while the export runs (its own audit.exported) are left out
            upTo = store.latestSeq() + 1;
            count = store.count(f.from(), f.to(), upTo);
        }
        if (count > max) {
            throw FdropException.tooLarge("the export would hold " + count + " events, more than the limit of " + max
                    + "; narrow the dates").withType("audit-export-too-large");
        }
        Audit.log("audit.exported", "user", admin.username(), "ip", ip, "protocol", "HTTP", "query", f.q(),
                "filters", describe(f), "format", csv ? "csv" : "jsonl", "results", count);
        Pipe pipe;
        try {
            pipe = Pipe.open();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Long> chosen = seqs;
        Long first = upTo;
        Thread.ofVirtual().name("audit-export").start(() -> {
            try (OutputStream out = Channels.newOutputStream(pipe.sink())) {
                if (csv) {
                    out.write(("id,time,node,node_seq,event,group,user,ip,protocol,outcome,summary\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                if (chosen != null) {
                    for (int i = 0; i < chosen.size(); i += 1000) {
                        write(out, store.bySeq(chosen.subList(i, Math.min(chosen.size(), i + 1000))), csv);
                    }
                } else {
                    Long before = first;
                    while (true) {
                        List<AuditStore.Row> rows = store.page(f.from(), f.to(), before, 1000);
                        if (rows.isEmpty()) {
                            break;
                        }
                        write(out, rows, csv);
                        before = rows.getLast().seq();
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warn("audit export stopped: {}", e.toString());
            }
        });
        String name = "fdrop-audit-" + Instant.ofEpochMilli(clock.millis()).toString().replace(":", "")
                .substring(0, 15) + (csv ? ".csv" : ".jsonl");
        return new Export(name, csv ? "text/csv; charset=utf-8" : "application/x-ndjson", count, pipe.source());
    }

    private static void write(OutputStream out, List<AuditStore.Row> rows, boolean csv) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (AuditStore.Row r : rows) {
            if (csv) {
                sb.append(String.join(",", List.of(r.id(), Instant.ofEpochMilli(r.ts()).toString(), csv(r.node()),
                        Long.toString(r.nodeSeq()), csv(r.event()), csv(r.group()), csv(r.user()), csv(r.ip()),
                        csv(r.protocol()), csv(r.outcome()), csv(r.summary())))).append("\r\n");
            } else {
                sb.append(r.data()).append('\n');
            }
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** RFC 4180 quoting, and a leading quote on values a spreadsheet would run as a formula. */
    static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        index.close();
    }
}
