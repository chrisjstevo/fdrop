package io.fdrop.core.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.Db;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The audit log in the DB (see "Audit log" in SPEC.md). Every event logged through {@link Audit} is queued here and
 * written in batches by a background thread, so no request waits for the DB. Each node chains its own rows:
 * {@code hash = SHA-256(prev_hash ‖ node ‖ node_seq ‖ id ‖ ts ‖ data)}, so a row edited, removed or reordered later
 * breaks the chain from that point. If the DB is unreachable, events wait in a bounded buffer; when it is full they are
 * only in the log file, and counted.
 */
public final class AuditStore implements Audit.Sink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AuditStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 500;

    /** One stored event. */
    public record Row(long seq, String id, long ts, String node, long nodeSeq, String prevHash, String hash,
                      String event, String group, String user, String ip, String protocol, String outcome,
                      String summary, String data) {
    }

    /** One node's chain check: {@code brokenAt} is the first bad node_seq, or null when intact. */
    public record ChainCheck(String node, long checked, Long firstTs, Long lastTs, Long brokenAt, String reason) {
        public boolean intact() {
            return brokenAt == null;
        }
    }

    private record Pending(long ts, String event, ObjectNode fields, String line) {
    }

    private final Db db;
    private final FdropMetrics metrics;
    private final String node;
    private final BlockingQueue<Pending> queue;
    private final Deque<Pending> retry = new ArrayDeque<>();
    private final Thread writer;
    private final Object writeLock = new Object();
    private volatile boolean running = true;
    private long lastNodeSeq = -1;
    private String lastHash;

    public AuditStore(FdropConfig.AuditLog cfg, Db db, FdropMetrics metrics) {
        this.db = db;
        this.metrics = metrics;
        this.node = cfg.node != null && !cfg.node.isBlank() ? cfg.node : hostName();
        this.queue = new ArrayBlockingQueue<>(Math.max(100, cfg.bufferSize));
        this.writer = Thread.ofPlatform().name("audit-writer").daemon().start(this::run);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "node";
        }
    }

    public String node() {
        return node;
    }

    @Override
    public void accept(long ts, String event, ObjectNode fields, String line) {
        if (!queue.offer(new Pending(ts, event, fields, line))) {
            metrics.auditWriteFailure();
        }
    }

    private void run() {
        while (running) {
            try {
                Pending first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first != null) {
                    synchronized (retry) {
                        retry.addLast(first);
                    }
                }
                writePending();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("could not write audit events to the DB: {}", e.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Writes everything queued, in order; what fails stays at the front for the next attempt. */
    private void writePending() {
        synchronized (writeLock) {
            while (true) {
                List<Pending> batch = new ArrayList<>();
                synchronized (retry) {
                    while (batch.size() < BATCH && !retry.isEmpty()) {
                        batch.add(retry.pollFirst());
                    }
                }
                queue.drainTo(batch, BATCH - batch.size());
                if (batch.isEmpty()) {
                    metrics.auditBuffer(0);
                    return;
                }
                try {
                    write(batch);
                } catch (RuntimeException e) {
                    synchronized (retry) {
                        for (int i = batch.size() - 1; i >= 0; i--) {
                            retry.addFirst(batch.get(i));
                        }
                    }
                    lastNodeSeq = -1;
                    metrics.auditBuffer(queue.size() + retry.size());
                    throw e;
                }
            }
        }
    }

    /** Waits until every event logged so far is in the DB. For tests and shutdown. */
    public void flush() {
        writePending();
    }

    private void loadTail() {
        Optional<Object[]> last = db.queryOne("SELECT node_seq, hash FROM audit_event WHERE node = ? ORDER BY"
                + " node_seq DESC LIMIT 1", rs -> new Object[]{rs.getLong(1), rs.getString(2)}, node);
        if (last.isPresent()) {
            lastNodeSeq = (Long) last.get()[0];
            lastHash = (String) last.get()[1];
            return;
        }
        Optional<Object[]> anchor = db.queryOne("SELECT node_seq, hash FROM audit_anchor WHERE node = ?",
                rs -> new Object[]{rs.getLong(1), rs.getString(2)}, node);
        lastNodeSeq = anchor.map(a -> (Long) a[0]).orElse(0L);
        lastHash = anchor.map(a -> (String) a[1]).orElse("");
    }

    static String hash(String prev, String node, long nodeSeq, String id, long ts, String data) {
        return Hashing.sha256Hex((prev + "\n" + node + "\n" + nodeSeq + "\n" + id + "\n" + ts + "\n" + data)
                .getBytes(StandardCharsets.UTF_8));
    }

    private void write(List<Pending> batch) {
        db.inTransaction(() -> {
            if (lastNodeSeq < 0) {
                loadTail();
            }
            long seq = lastNodeSeq;
            String prev = lastHash;
            for (Pending p : batch) {
                seq++;
                String id = UUID.randomUUID().toString();
                String h = hash(prev, node, seq, id, p.ts(), p.line());
                String group = AuditEvents.group(p.event());
                db.update("INSERT INTO audit_event (id, ts, node, node_seq, prev_hash, hash, event, event_group,"
                                + " user_name, ip, protocol, outcome, summary, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                + " ?, ?, ?)", id, p.ts(), node, seq, prev, h, p.event(), group,
                        AuditEvents.text(p.fields(), "user"), AuditEvents.text(p.fields(), "ip"),
                        AuditEvents.text(p.fields(), "protocol"), AuditEvents.outcome(p.event(), p.fields()),
                        AuditEvents.summary(p.event(), p.fields()), p.line());
                metrics.auditEvent(group);
                prev = h;
            }
            lastNodeSeq = seq;
            lastHash = prev;
            return null;
        });
    }

    // ---- reading ----

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getLong("seq"), rs.getString("id"), rs.getLong("ts"), rs.getString("node"),
                rs.getLong("node_seq"), rs.getString("prev_hash"), rs.getString("hash"), rs.getString("event"),
                rs.getString("event_group"), rs.getString("user_name"), rs.getString("ip"), rs.getString("protocol"),
                rs.getString("outcome"), rs.getString("summary"), rs.getString("data"));
    }

    /** Newest first, between {@code from} and {@code to} (epoch millis, either may be null), before {@code beforeSeq}. */
    public List<Row> page(Long from, Long to, Long beforeSeq, int limit) {
        return db.query("SELECT * FROM audit_event WHERE ts >= ? AND ts < ? AND seq < ? ORDER BY seq DESC LIMIT ?",
                AuditStore::row, from == null ? 0L : from, to == null ? Long.MAX_VALUE : to,
                beforeSeq == null ? Long.MAX_VALUE : beforeSeq, limit);
    }

    public long count(Long from, Long to) {
        return count(from, to, null);
    }

    public long count(Long from, Long to, Long beforeSeq) {
        return db.queryLong("SELECT COUNT(*) FROM audit_event WHERE ts >= ? AND ts < ? AND seq < ?",
                from == null ? 0L : from, to == null ? Long.MAX_VALUE : to,
                beforeSeq == null ? Long.MAX_VALUE : beforeSeq);
    }

    /** Counts per group and per outcome in a time range. */
    public Map<String, Map<String, Long>> facets(Long from, Long to) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (String col : List.of("event_group", "outcome")) {
            Map<String, Long> m = new LinkedHashMap<>();
            for (Object[] r : db.query("SELECT " + col + ", COUNT(*) FROM audit_event WHERE ts >= ? AND ts < ?"
                            + " GROUP BY " + col + " ORDER BY " + col, rs -> new Object[]{rs.getString(1), rs.getLong(2)},
                    from == null ? 0L : from, to == null ? Long.MAX_VALUE : to)) {
                m.put((String) r[0], (Long) r[1]);
            }
            out.put(col.equals("event_group") ? "group" : "outcome", m);
        }
        return out;
    }

    public Optional<Row> find(String id) {
        return db.queryOne("SELECT * FROM audit_event WHERE id = ?", AuditStore::row, id);
    }

    /** Rows by seq, in the order given. */
    public List<Row> bySeq(List<Long> seqs) {
        if (seqs.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", seqs.stream().map(s -> "?").toList());
        Map<Long, Row> found = new LinkedHashMap<>();
        for (Row r : db.query("SELECT * FROM audit_event WHERE seq IN (" + in + ")", AuditStore::row,
                seqs.toArray())) {
            found.put(r.seq(), r);
        }
        List<Row> out = new ArrayList<>();
        for (Long s : seqs) {
            Row r = found.get(s);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** Rows after {@code seq}, oldest first: what an audit index applies next. */
    public List<Row> after(long seq, int limit) {
        return db.query("SELECT * FROM audit_event WHERE seq > ? ORDER BY seq LIMIT ?", AuditStore::row, seq, limit);
    }

    public long latestSeq() {
        return db.queryLong("SELECT COALESCE(MAX(seq), 0) FROM audit_event");
    }

    public Optional<Long> tsOf(long seq) {
        return db.queryOne("SELECT ts FROM audit_event WHERE seq = ?", rs -> rs.getLong(1), seq);
    }

    /** Event names and groups seen, with how many of each. */
    public List<Object[]> events() {
        return db.query("SELECT event, event_group, COUNT(*) FROM audit_event GROUP BY event, event_group"
                + " ORDER BY event_group, event", rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3)});
    }

    // ---- retention and verification ----

    /** Deletes events before {@code cutoff}, moving each node's chain start to its last deleted row. */
    public int prune(long cutoff) {
        flush();
        int deleted = 0;
        for (String n : db.query("SELECT DISTINCT node FROM audit_event WHERE ts < ?", rs -> rs.getString(1),
                cutoff)) {
            Optional<Object[]> last = db.queryOne("SELECT node_seq, hash FROM audit_event WHERE node = ? AND ts < ?"
                    + " ORDER BY node_seq DESC LIMIT 1", rs -> new Object[]{rs.getLong(1), rs.getString(2)}, n, cutoff);
            if (last.isEmpty()) {
                continue;
            }
            long upTo = (Long) last.get()[0];
            String hash = (String) last.get()[1];
            deleted += db.inTransaction(() -> {
                db.update("DELETE FROM audit_anchor WHERE node = ?", n);
                db.update("INSERT INTO audit_anchor (node, node_seq, hash, pruned_at) VALUES (?, ?, ?, ?)", n, upTo,
                        hash, System.currentTimeMillis());
                return db.update("DELETE FROM audit_event WHERE node = ? AND node_seq <= ?", n, upTo);
            });
            Audit.log("audit.pruned", "user", "system", "chainNode", n, "upToNodeSeq", upTo, "anchorHash", hash,
                    "cutoff", java.time.Instant.ofEpochMilli(cutoff));
        }
        return deleted;
    }

    /** Walks every node's chain from its anchor, recomputing each hash and the derived columns. */
    public List<ChainCheck> verify() {
        flush();
        List<ChainCheck> out = new ArrayList<>();
        for (String n : db.query("SELECT DISTINCT node FROM audit_event ORDER BY node", rs -> rs.getString(1))) {
            Optional<Object[]> anchor = db.queryOne("SELECT node_seq, hash FROM audit_anchor WHERE node = ?",
                    rs -> new Object[]{rs.getLong(1), rs.getString(2)}, n);
            long expect = anchor.map(a -> (Long) a[0]).orElse(0L) + 1;
            String prev = anchor.map(a -> (String) a[1]).orElse("");
            long checked = 0;
            Long first = null;
            Long last = null;
            Long broken = null;
            String reason = null;
            long after = expect - 1;
            outer:
            while (true) {
                List<Row> rows = db.query("SELECT * FROM audit_event WHERE node = ? AND node_seq > ? ORDER BY node_seq"
                        + " LIMIT 1000", AuditStore::row, n, after);
                if (rows.isEmpty()) {
                    break;
                }
                for (Row r : rows) {
                    String problem = null;
                    if (r.nodeSeq() != expect) {
                        problem = "event " + expect + " is missing";
                    } else if (!r.prevHash().equals(prev)
                            || !r.hash().equals(hash(prev, n, r.nodeSeq(), r.id(), r.ts(), r.data()))) {
                        problem = "the event does not match its hash";
                    } else if (!derivedMatch(r)) {
                        problem = "the shown columns do not match the event";
                    }
                    if (problem != null) {
                        broken = expect;
                        reason = problem;
                        break outer;
                    }
                    first = first == null ? r.ts() : first;
                    last = r.ts();
                    prev = r.hash();
                    expect++;
                    checked++;
                    after = r.nodeSeq();
                }
            }
            out.add(new ChainCheck(n, checked, first, last, broken, reason));
        }
        boolean intact = out.stream().allMatch(ChainCheck::intact);
        metrics.auditChainIntact(intact);
        for (ChainCheck c : out) {
            if (!c.intact()) {
                Audit.log("audit.chain.broken", "user", "system", "chainNode", c.node(), "nodeSeq", c.brokenAt(),
                        "reason", c.reason());
            }
        }
        return out;
    }

    private static boolean derivedMatch(Row r) {
        try {
            JsonNode f = JSON.readTree(r.data());
            return r.event().equals(f.path("event").asText())
                    && r.group().equals(AuditEvents.group(r.event()))
                    && r.outcome().equals(AuditEvents.outcome(r.event(), f))
                    && r.summary().equals(AuditEvents.summary(r.event(), f))
                    && java.util.Objects.equals(r.user(), AuditEvents.text(f, "user"))
                    && java.util.Objects.equals(r.ip(), AuditEvents.text(f, "ip"));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        Audit.clearSink(this);
        running = false;
        writer.interrupt();
        try {
            writer.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            writePending();
        } catch (RuntimeException e) {
            log.warn("audit events not written at shutdown (they are in the log file): {}", e.toString());
        }
    }

    /** Durations in config, for the janitor and the verifier. */
    public static long millis(String d) {
        return Durations.parse(d).toMillis();
    }
}
