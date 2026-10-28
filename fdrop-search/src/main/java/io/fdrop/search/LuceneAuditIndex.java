package io.fdrop.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fdrop.core.Fdrop;
import io.fdrop.core.audit.AuditEvents;
import io.fdrop.core.audit.AuditIndex;
import io.fdrop.core.audit.AuditStore;
import io.fdrop.core.error.FdropException;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.sortedset.DefaultSortedSetDocValuesReaderState;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This node's audit index (see "The audit index" in spec/audit-log.md): built from {@code audit_event} in {@code seq} order, in
 * its own directory with its own key, never sharing anything with the file search index. It holds no event's full
 * data: results are {@code seq}s, read back from the DB.
 */
public final class LuceneAuditIndex implements AuditIndex {

    private static final Logger log = LoggerFactory.getLogger(LuceneAuditIndex.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SEQ_DATA = "fdrop.audit.seq";
    private static final int MAX_QUERY = 500;
    private static final int MAX_CLAUSES = 32;
    private static final long COMMIT_EVERY_MS = 5_000;
    private static final Pattern FIELD = Pattern.compile(
            "(?i)(?<![\\w:])(-?)(event|group|user|ip|path|outcome|node|protocol):(\"[^\"]*\"|\\S+)");

    static final String SEQ = "seq";
    static final String TS = "ts";
    static final String EVENT = "event";
    static final String GROUP = "group";
    static final String OUTCOME = "outcome";
    static final String PROTOCOL = "protocol";
    static final String NODE = "node";
    static final String USER = "user";
    static final String USER_EXACT = "userExact";
    static final String IP = "ip";
    static final String IP_EXACT = "ipExact";
    static final String PATH = "path";
    static final String PATH_EXACT = "pathExact";
    static final String TEXT = "text";
    static final String SUMMARY = "summary";
    /** Facet dimensions: the group's display name and the outcome. */
    static final String DIM_GROUP = "group";
    static final String DIM_OUTCOME = "outcome";

    private final AuditStore store;
    private final io.fdrop.core.metrics.FdropMetrics metrics;
    private final Path root;
    private final byte[] key;
    private final Analyzer analyzer;
    private final FacetsConfig facets = new FacetsConfig();
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("audit-indexer").daemon().factory());

    private Directory dir;
    private IndexWriter writer;
    private volatile SearcherManager manager;
    private volatile long applied;
    private volatile Long appliedTs;
    private boolean needsRebuild;
    private boolean dirty;
    private long lastCommit;
    private long lastPruneCheck;
    private volatile boolean started;
    private volatile boolean rebuilding;
    private volatile boolean rebuildRequested;
    private volatile boolean closed;

    public LuceneAuditIndex(Fdrop app) {
        this.store = app.auditStore;
        this.metrics = app.metrics;
        Analyzers words = new Analyzers();
        this.analyzer = new PerFieldAnalyzerWrapper(new StandardAnalyzer(CharArraySet.EMPTY_SET),
                Map.of(PATH, words.name));
        this.root = Path.of(app.config.audit.indexDir);
        try {
            Files.createDirectories(root);
            this.key = IndexFiles.key(root, app.encryption);
            open(root.resolve("index"));
        } catch (IOException e) {
            throw new UncheckedIOException("could not open the audit index in " + root, e);
        }
    }

    // ---- the index on disk ----

    private IndexWriterConfig writerConfig() {
        return new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
    }

    private void open(Path path) throws IOException {
        Files.createDirectories(path);
        dir = IndexFiles.directory(path, key);
        writer = new IndexWriter(dir, writerConfig());
        Map<String, String> commit = new HashMap<>();
        Iterable<Map.Entry<String, String>> data = writer.getLiveCommitData();
        if (data != null) {
            data.forEach(e -> commit.put(e.getKey(), e.getValue()));
        }
        manager = new SearcherManager(writer, null);
        String seq = commit.get(SEQ_DATA);
        applied = seq == null ? 0 : Long.parseLong(seq);
        // never committed, or the DB was replaced by one that is behind this index
        needsRebuild = seq == null || store.latestSeq() < applied;
        appliedTs = applied == 0 ? null : store.tsOf(applied).orElse(null);
        lastCommit = System.currentTimeMillis();
    }

    private void commit() throws IOException {
        writer.setLiveCommitData(Map.of(SEQ_DATA, Long.toString(applied)).entrySet());
        writer.commit();
        dirty = false;
        lastCommit = System.currentTimeMillis();
    }

    // ---- indexing ----

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void start() {
        started = true;
        loop.scheduleWithFixedDelay(this::safeTick, 0, 1, TimeUnit.SECONDS);
    }

    private void safeTick() {
        try {
            tick(20);
        } catch (Exception e) {
            log.warn("audit indexing failed: {}", e.toString());
        }
    }

    /** Indexes every event in the DB now and makes it searchable. For tests. */
    public synchronized void indexNow() throws IOException {
        store.flush();
        tick(Integer.MAX_VALUE);
        commit();
        manager.maybeRefreshBlocking();
    }

    private synchronized void tick(int batches) throws IOException {
        if (closed) {
            return;
        }
        if (needsRebuild || rebuildRequested) {
            rebuildNow();
        }
        dropPruned();
        for (int i = 0; i < batches; i++) {
            List<AuditStore.Row> rows = store.after(applied, 500);
            if (rows.isEmpty()) {
                break;
            }
            for (AuditStore.Row r : rows) {
                writer.addDocument(facets.build(document(r)));
                applied = r.seq();
                appliedTs = r.ts();
            }
            dirty = true;
        }
        if (dirty && System.currentTimeMillis() - lastCommit >= COMMIT_EVERY_MS) {
            commit();
        }
        manager.maybeRefresh();
        metrics.auditIndexLag(lagSeconds());
    }

    /** Removes events the retention prune deleted from the DB, at most once an hour. */
    private void dropPruned() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastPruneCheck < 3_600_000) {
            return;
        }
        lastPruneCheck = now;
        List<AuditStore.Row> oldest = store.after(0, 1);
        long keepFrom = oldest.isEmpty() ? applied + 1 : oldest.getFirst().seq();
        writer.deleteDocuments(LongPoint.newRangeQuery(SEQ, Long.MIN_VALUE, keepFrom - 1));
        dirty = true;
    }

    private double lagSeconds() {
        return store.after(applied, 1).stream().findFirst()
                .map(r -> Math.max(0, System.currentTimeMillis() - r.ts()) / 1000.0).orElse(0.0);
    }

    Document document(AuditStore.Row r) {
        Document d = new Document();
        d.add(new LongPoint(SEQ, r.seq()));
        d.add(new NumericDocValuesField(SEQ, r.seq()));
        d.add(new StoredField(SEQ, r.seq()));
        d.add(new LongPoint(TS, r.ts()));
        d.add(new StoredField(TS, r.ts()));
        d.add(new StringField(EVENT, r.event(), Field.Store.NO));
        d.add(new StringField(GROUP, AuditEvents.slug(r.group()), Field.Store.NO));
        d.add(new SortedSetDocValuesFacetField(DIM_GROUP, r.group()));
        d.add(new StringField(OUTCOME, r.outcome(), Field.Store.NO));
        d.add(new SortedSetDocValuesFacetField(DIM_OUTCOME, r.outcome()));
        d.add(new StringField(NODE, r.node(), Field.Store.NO));
        if (r.protocol() != null) {
            d.add(new StringField(PROTOCOL, r.protocol().toLowerCase(Locale.ROOT), Field.Store.NO));
        }
        if (r.user() != null) {
            d.add(new StringField(USER_EXACT, r.user().toLowerCase(Locale.ROOT), Field.Store.NO));
            d.add(new TextField(USER, r.user(), Field.Store.NO));
        }
        if (r.ip() != null) {
            d.add(new StringField(IP_EXACT, r.ip(), Field.Store.NO));
            InetAddress a = address(r.ip());
            if (a != null) {
                d.add(new InetAddressPoint(IP, a));
            }
        }
        d.add(new StoredField(SUMMARY, r.summary()));
        StringBuilder text = new StringBuilder(r.event()).append(' ').append(r.group()).append(' ')
                .append(r.outcome()).append(' ').append(r.summary());
        try {
            JsonNode f = JSON.readTree(r.data());
            for (Iterator<Map.Entry<String, JsonNode>> it = f.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().equals("ts") || e.getValue().isNull()) {
                    continue;
                }
                String v = e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString();
                text.append(' ').append(e.getKey()).append(' ').append(v);
                if (v.startsWith("/")) {
                    d.add(new TextField(PATH, v, Field.Store.NO));
                    d.add(new StringField(PATH_EXACT, trimSlash(v), Field.Store.NO));
                }
            }
        } catch (IOException e) {
            text.append(' ').append(r.data());
        }
        d.add(new TextField(TEXT, text.toString(), Field.Store.NO));
        return d;
    }

    private static String trimSlash(String p) {
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /** A literal address only: never a name lookup. */
    private static InetAddress address(String s) {
        // only digits, dots and hex with colons, so getByName parses a literal and never asks DNS
        if (s == null || !(s.matches("\\d{1,3}(\\.\\d{1,3}){3}") || s.matches("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*"))) {
            return null;
        }
        try {
            return InetAddress.getByName(s);
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    /** Rebuilds in the background; without the background loop (in tests), now. */
    @Override
    public void rebuild() {
        rebuildRequested = true;
        if (!started) {
            try {
                indexNow();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Builds a fresh index from the DB beside the live one, which keeps answering until the new one is complete. */
    private void doRebuild() throws IOException {
        Path next = root.resolve("index-next");
        IndexFiles.wipe(next);
        Files.createDirectories(next);
        long seq = 0;
        Long ts = null;
        try (Directory nd = IndexFiles.directory(next, key); IndexWriter nw = new IndexWriter(nd, writerConfig())) {
            while (true) {
                List<AuditStore.Row> rows = store.after(seq, 1000);
                if (rows.isEmpty()) {
                    break;
                }
                for (AuditStore.Row r : rows) {
                    nw.addDocument(facets.build(document(r)));
                    seq = r.seq();
                    ts = r.ts();
                }
            }
            nw.setLiveCommitData(Map.of(SEQ_DATA, Long.toString(seq)).entrySet());
            nw.commit();
        }
        SearcherManager oldManager = manager;
        writer.close();
        dir.close();
        Path live = root.resolve("index");
        IndexFiles.wipe(live);
        Files.move(next, live);
        open(live);
        oldManager.close();
        applied = seq;
        appliedTs = ts;
        needsRebuild = false;
        log.info("audit index rebuilt up to event {}", seq);
    }

    private void rebuildNow() throws IOException {
        rebuilding = true;
        rebuildRequested = false;
        try {
            doRebuild();
        } finally {
            rebuilding = false;
        }
    }

    // ---- searching ----

    private static FdropException invalid(String detail) {
        return FdropException.badRequest(detail).withType("invalid-audit-search");
    }

    @Override
    public Result search(AuditIndex.Query q) {
        if (q.q() != null && q.q().length() > MAX_QUERY) {
            throw invalid("the search is longer than " + MAX_QUERY + " characters");
        }
        org.apache.lucene.search.Query text = parse(q.q());
        org.apache.lucene.search.Query groups = anyOf(GROUP, q.groups().stream().map(g -> AuditEvents.slug(g)).toList());
        org.apache.lucene.search.Query outcomes = anyOf(OUTCOME, q.outcomes().stream().map(o -> o.toLowerCase(Locale.ROOT)).toList());
        BooleanQuery.Builder rest = new BooleanQuery.Builder().add(text, Occur.MUST);
        rest.add(anyOf(EVENT, q.events()), Occur.FILTER);
        if (q.user() != null) {
            rest.add(new TermQuery(new Term(USER_EXACT, q.user().toLowerCase(Locale.ROOT))), Occur.FILTER);
        }
        if (q.ip() != null) {
            rest.add(ip(q.ip()), Occur.FILTER);
        }
        if (q.from() != null || q.to() != null) {
            rest.add(LongPoint.newRangeQuery(TS, q.from() == null ? Long.MIN_VALUE : q.from(),
                    q.to() == null ? Long.MAX_VALUE : q.to() - 1), Occur.FILTER);
        }
        org.apache.lucene.search.Query base = rest.build();
        org.apache.lucene.search.Query all = new BooleanQuery.Builder().add(base, Occur.MUST).add(groups, Occur.FILTER)
                .add(outcomes, Occur.FILTER).build();
        IndexSearcher s = null;
        try {
            s = manager.acquire();
            long total = s.count(all);
            org.apache.lucene.search.Query page = all;
            if (q.beforeSeq() != null) {
                page = new BooleanQuery.Builder().add(all, Occur.MUST)
                        .add(LongPoint.newRangeQuery(SEQ, Long.MIN_VALUE, q.beforeSeq() - 1), Occur.FILTER).build();
            }
            TopDocs top = s.search(page, Math.max(1, q.limit()), new Sort(new SortField(SEQ, SortField.Type.LONG,
                    true)));
            List<Long> seqs = new ArrayList<>();
            for (ScoreDoc sd : top.scoreDocs) {
                seqs.add(s.storedFields().document(sd.doc).getField(SEQ).numericValue().longValue());
            }
            // each dimension counts as if its own chips were not selected, so the other choices stay visible
            Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
            counts.put(DIM_GROUP, counts(s, new BooleanQuery.Builder().add(base, Occur.MUST)
                    .add(outcomes, Occur.FILTER).build(), DIM_GROUP));
            counts.put(DIM_OUTCOME, counts(s, new BooleanQuery.Builder().add(base, Occur.MUST)
                    .add(groups, Occur.FILTER).build(), DIM_OUTCOME));
            return new Result(total, seqs, counts, appliedTs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IndexSearcher.TooManyClauses e) {
            throw invalid("the search has too many terms");
        } finally {
            if (s != null) {
                try {
                    manager.release(s);
                } catch (IOException e) {
                    log.debug("release: {}", e.toString());
                }
            }
        }
    }

    private Map<String, Long> counts(IndexSearcher s, org.apache.lucene.search.Query q, String dim) throws IOException {
        Map<String, Long> out = new LinkedHashMap<>();
        if (s.getIndexReader().numDocs() == 0) {
            return out;
        }
        try {
            SortedSetDocValuesReaderState state = new DefaultSortedSetDocValuesReaderState(s.getIndexReader(),
                    facets);
            FacetsCollector fc = s.search(q, new FacetsCollectorManager());
            Facets f = new SortedSetDocValuesFacetCounts(state, fc);
            FacetResult r = f.getTopChildren(50, dim);
            if (r != null) {
                for (LabelAndValue lv : r.labelValues) {
                    out.put(lv.label, lv.value.longValue());
                }
            }
        } catch (IllegalArgumentException e) {
            // no event in the index has this dimension yet
        }
        return out;
    }

    private static org.apache.lucene.search.Query anyOf(String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (String v : values) {
            b.add(new TermQuery(new Term(field, v)), Occur.SHOULD);
        }
        return b.build();
    }

    /** {@code 10.0.0.1}, or a CIDR range such as {@code 10.20.0.0/16} or {@code 2001:db8::/32}. */
    static org.apache.lucene.search.Query ip(String value) {
        String v = value.strip();
        int slash = v.indexOf('/');
        InetAddress a = address(slash < 0 ? v : v.substring(0, slash));
        if (a == null) {
            throw invalid("ip: needs an address or a CIDR range, such as 10.20.0.0/16");
        }
        if (slash < 0) {
            return new BooleanQuery.Builder().add(InetAddressPoint.newExactQuery(IP, a), Occur.SHOULD)
                    .add(new TermQuery(new Term(IP_EXACT, v)), Occur.SHOULD).build();
        }
        int bits;
        try {
            bits = Integer.parseInt(v.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw invalid("ip: the prefix length after / must be a number");
        }
        int max = a.getAddress().length * 8;
        if (bits < 0 || bits > max) {
            throw invalid("ip: the prefix length must be between 0 and " + max);
        }
        return InetAddressPoint.newPrefixQuery(IP, a, bits);
    }

    /**
     * Field terms ({@code event:}, {@code group:}, {@code user:}, {@code ip:}, {@code path:}, {@code outcome:},
     * {@code node:}, {@code protocol:}, each negatable with '-'), and the rest as free text over every field with the
     * same syntax as file search.
     */
    org.apache.lucene.search.Query parse(String q) {
        if (q == null || q.isBlank()) {
            return new MatchAllDocsQuery();
        }
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        Matcher m = FIELD.matcher(q);
        StringBuilder rest = new StringBuilder();
        int clauses = 0;
        boolean positive = false;
        int last = 0;
        while (m.find()) {
            rest.append(q, last, m.start()).append(' ');
            last = m.end();
            String value = m.group(3);
            if (value.startsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.isEmpty()) {
                continue;
            }
            if (++clauses > MAX_CLAUSES) {
                throw invalid("the search has too many terms");
            }
            boolean not = m.group(1).equals("-");
            positive |= !not;
            b.add(field(m.group(2).toLowerCase(Locale.ROOT), value), not ? Occur.MUST_NOT : Occur.FILTER);
        }
        rest.append(q.substring(last));
        String free = rest.toString().strip();
        if (!free.isEmpty()) {
            SimpleQueryParser p = new SimpleQueryParser(analyzer, Map.of(TEXT, 1f), SimpleQueryParser.AND_OPERATOR
                    | SimpleQueryParser.NOT_OPERATOR | SimpleQueryParser.OR_OPERATOR | SimpleQueryParser.PHRASE_OPERATOR
                    | SimpleQueryParser.PREFIX_OPERATOR | SimpleQueryParser.WHITESPACE_OPERATOR
                    | SimpleQueryParser.PRECEDENCE_OPERATORS);
            p.setDefaultOperator(Occur.MUST);
            org.apache.lucene.search.Query t = p.parse(free);
            if (t != null) {
                b.add(t, Occur.MUST);
                positive = true;
            }
        }
        if (!positive) {
            // only exclusions: everything else matches
            b.add(new MatchAllDocsQuery(), Occur.MUST);
        }
        return b.build();
    }

    private static org.apache.lucene.search.Query field(String name, String value) {
        boolean prefix = value.endsWith("*") && value.length() > 1;
        String v = prefix ? value.substring(0, value.length() - 1) : value;
        return switch (name) {
            case "event" -> prefix ? new PrefixQuery(new Term(EVENT, v)) : new TermQuery(new Term(EVENT, v));
            case "group" -> new TermQuery(new Term(GROUP, AuditEvents.slug(v)));
            case "outcome" -> new TermQuery(new Term(OUTCOME, v.toLowerCase(Locale.ROOT)));
            case "protocol" -> new TermQuery(new Term(PROTOCOL, v.toLowerCase(Locale.ROOT)));
            case "node" -> prefix ? new PrefixQuery(new Term(NODE, v)) : new TermQuery(new Term(NODE, v));
            case "user" -> prefix ? new PrefixQuery(new Term(USER_EXACT, v.toLowerCase(Locale.ROOT)))
                    : new TermQuery(new Term(USER_EXACT, v.toLowerCase(Locale.ROOT)));
            case "ip" -> ip(value);
            case "path" -> {
                String p = trimSlash(v.startsWith("/") ? v : "/" + v);
                yield new BooleanQuery.Builder().add(new TermQuery(new Term(PATH_EXACT, p)), Occur.SHOULD)
                        .add(new PrefixQuery(new Term(PATH_EXACT, p.equals("/") ? "/" : p + "/")), Occur.SHOULD)
                        .build();
            }
            default -> throw invalid("unknown field " + name);
        };
    }

    // ---- status ----

    @Override
    public Status status() {
        long docs = 0;
        IndexSearcher s = null;
        try {
            s = manager.acquire();
            docs = s.getIndexReader().numDocs();
        } catch (IOException e) {
            log.debug("audit index status: {}", e.toString());
        } finally {
            if (s != null) {
                try {
                    manager.release(s);
                } catch (IOException e) {
                    log.debug("release: {}", e.toString());
                }
            }
        }
        return new Status(true, docs, IndexFiles.size(root.resolve("index")), applied, store.latestSeq(),
                lagSeconds(), rebuilding || rebuildRequested);
    }

    @Override
    public synchronized void close() {
        closed = true;
        loop.shutdownNow();
        try {
            if (dirty) {
                commit();
            }
            manager.close();
            writer.close();
            dir.close();
        } catch (IOException e) {
            log.warn("closing the audit index: {}", e.toString());
        }
    }
}
