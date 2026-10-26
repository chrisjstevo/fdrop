package io.fdrop.search;

import io.fdrop.core.Fdrop;
import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.crypto.KeyProvider;
import io.fdrop.core.crypto.StreamFormat;
import io.fdrop.core.db.SearchRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Records.FileExpiry;
import io.fdrop.core.search.SearchAccess;
import io.fdrop.core.search.SearchAccess.Place;
import io.fdrop.core.search.SearchService;
import io.fdrop.core.service.DestinationService;
import io.fdrop.core.service.FileService;
import io.fdrop.core.service.VirtualPath;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.StoragePath;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Search with an embedded Lucene index on this node (see "Search" in SPEC.md). The index is built from the change
 * feed in the metadata DB; the last change applied is kept in each Lucene commit, so a restart continues where the
 * last commit ended. Every query is filtered to the caller's areas, read from the DB per query, and every hit is
 * checked again as the caller before it is returned.
 */
public final class LuceneSearchService implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(LuceneSearchService.class);
    private static final String SEQ = "fdrop.seq";
    private static final int MAX_QUERY = 500;
    private static final int MAX_TERMS = 32;
    private static final int SNIPPET = 160;
    private static final Pattern FIELD = Pattern.compile("(?i)(?<![\\w:])(type|name|content):(\"[^\"]*\"|\\S+)");
    private static final Pattern WORD = Pattern.compile("(?<![\\w-])(-?)([\\p{L}\\p{N}]+)(\\*?)");
    private static final Set<String> TYPES = Set.of("pdf", "doc", "sheet", "slides", "text", "email", "other",
            "folder");

    private final Fdrop app;
    private final FdropConfig.Search cfg;
    private final Analyzers analyzers = new Analyzers();
    private final SearchAccess access;
    private final SearchRepo repo;
    private final TextStore texts;
    private final Path root;
    private final byte[] indexKey;
    private final ExecutorService extractPool;
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("search-indexer").daemon().factory());
    private final Map<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    private final long commitEvery;

    private Directory dir;
    private IndexWriter writer;
    private volatile SearcherManager manager;
    private long applied;
    private boolean needsRebuild;
    private boolean dirty;
    private long lastCommit;
    private volatile boolean rebuilding;
    private volatile boolean rebuildRequested;
    private volatile boolean closed;

    public LuceneSearchService(Fdrop app) {
        this.app = app;
        this.cfg = app.config.search;
        this.access = new SearchAccess(app.folders, cfg.includeForwardFolders);
        this.repo = app.searchRepo;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            host = "node";
        }
        this.texts = new TextStore(cfg.extract, app.storage, repo, new Extractor(cfg.extract), app.metrics,
                host + "-" + UUID.randomUUID().toString().substring(0, 8));
        this.extractPool = Executors.newFixedThreadPool(Math.max(1, cfg.extract.workers),
                Thread.ofPlatform().name("search-extract-", 0).daemon().priority(Thread.MIN_PRIORITY).factory());
        this.commitEvery = Durations.parse(cfg.commitInterval).toMillis();
        this.root = Path.of(cfg.indexDir);
        try {
            Files.createDirectories(root);
            this.indexKey = indexKey();
            open(root.resolve("index"));
        } catch (IOException e) {
            throw new UncheckedIOException("could not open the search index in " + root, e);
        }
    }

    // ---- the index on disk ----

    /** The index key, wrapped by the storage KEK in {@code index.key}; null when encryption at rest is off. */
    private byte[] indexKey() throws IOException {
        KeyProvider kp = app.encryption.keyProvider();
        Path file = root.resolve("index.key");
        if (kp == null || !app.encryption.enabled()) {
            if (Files.exists(file) && kp != null) {
                return readKey(file, kp);
            }
            if (Files.exists(file)) {
                wipe(root.resolve("index"));
                Files.delete(file);
            }
            return null;
        }
        if (Files.exists(file)) {
            return readKey(file, kp);
        }
        wipe(root.resolve("index"));
        byte[] key = StreamFormat.newDataKey();
        writeKey(file, kp, key);
        return key;
    }

    private static byte[] readKey(Path file, KeyProvider kp) throws IOException {
        String[] f = Files.readString(file, StandardCharsets.UTF_8).strip().split("\\|", 2);
        byte[] key = kp.unwrap(f[0], Base64.getDecoder().decode(f[1]));
        if (!f[0].equals(kp.primaryKeyId()) && !kp.primaryKeyId().startsWith("vault:")) {
            writeKey(file, kp, key);
        }
        return key;
    }

    private static void writeKey(Path file, KeyProvider kp, byte[] key) throws IOException {
        KeyProvider.Wrapped w = kp.wrap(key);
        Path tmp = file.resolveSibling("index.key.tmp");
        Files.writeString(tmp, w.keyId() + "|" + Base64.getEncoder().encodeToString(w.wrapped()),
                StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private Directory directory(Path path) throws IOException {
        Directory fs = FSDirectory.open(path);
        return indexKey == null ? fs : new EncryptedDirectory(fs, indexKey);
    }

    private IndexWriterConfig writerConfig() {
        return new IndexWriterConfig(analyzers.perField).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
    }

    private void open(Path path) throws IOException {
        Files.createDirectories(path);
        dir = directory(path);
        writer = new IndexWriter(dir, writerConfig());
        Map<String, String> commit = new HashMap<>();
        Iterable<Map.Entry<String, String>> data = writer.getLiveCommitData();
        if (data != null) {
            data.forEach(e -> commit.put(e.getKey(), e.getValue()));
        }
        manager = new SearcherManager(writer, null);
        String seq = commit.get(SEQ);
        if (seq == null) {
            needsRebuild = true;
            applied = 0;
        } else {
            applied = Long.parseLong(seq);
            long oldest = repo.oldest();
            needsRebuild = oldest > applied + 1;
        }
        lastCommit = System.currentTimeMillis();
    }

    private static void wipe(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> s = Files.walk(path)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    // ---- indexing ----

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void start() {
        loop.scheduleWithFixedDelay(this::safeTick, 0, 1, TimeUnit.SECONDS);
    }

    private void safeTick() {
        try {
            tick(10);
        } catch (Exception e) {
            log.warn("search indexing failed: {}", e.toString());
        }
    }

    /** Applies every pending change and makes it searchable now. For tests and the admin screen. */
    public synchronized void indexNow() throws IOException {
        tick(Integer.MAX_VALUE);
        commit();
        manager.maybeRefreshBlocking();
    }

    private synchronized void tick(int batches) throws IOException {
        if (closed) {
            return;
        }
        if (needsRebuild || rebuildRequested) {
            rebuild();
        }
        for (int i = 0; i < batches; i++) {
            List<SearchRepo.Change> changes = repo.after(applied, 200);
            if (changes.isEmpty()) {
                break;
            }
            apply(changes);
        }
        if (dirty && System.currentTimeMillis() - lastCommit >= commitEvery) {
            commit();
        }
        manager.maybeRefresh();
        updateGauges();
    }

    private void updateGauges() {
        IndexSearcher s = null;
        try {
            s = manager.acquire();
            app.metrics.searchIndex(s.getIndexReader().numDocs(), lagSeconds());
        } catch (IOException e) {
            log.debug("search gauges: {}", e.toString());
        } finally {
            release(s);
        }
    }

    private double lagSeconds() {
        return repo.oldestAtAfter(applied).map(at -> Math.max(0, System.currentTimeMillis() - at) / 1000.0)
                .orElse(0.0);
    }

    private void commit() throws IOException {
        writer.setLiveCommitData(Map.of(SEQ, Long.toString(applied)).entrySet());
        writer.commit();
        dirty = false;
        lastCommit = System.currentTimeMillis();
    }

    /** One batch: texts for new files are fetched in parallel, then the changes are applied in feed order. */
    private void apply(List<SearchRepo.Change> changes) throws IOException {
        Map<Long, Future<Optional<Document>>> docs = new HashMap<>();
        for (SearchRepo.Change c : changes) {
            if (c.op() == SearchRepo.Op.INDEX) {
                docs.put(c.seq(), extractPool.submit(() -> document(c.storageKey())));
            }
        }
        for (SearchRepo.Change c : changes) {
            switch (c.op()) {
                case INDEX -> {
                    Optional<Document> d;
                    try {
                        d = docs.get(c.seq()).get();
                    } catch (Exception e) {
                        log.warn("could not index {}: {}", c.storageKey(), e.toString());
                        d = Optional.empty();
                    }
                    Term id = new Term(Analyzers.ID, c.storageKey());
                    if (d.isPresent()) {
                        writer.updateDocument(id, d.get());
                    } else {
                        writer.deleteDocuments(id);
                    }
                }
                case DELETE -> {
                    writer.deleteDocuments(new TermQuery(new Term(Analyzers.ID, c.storageKey())),
                            new PrefixQuery(new Term(Analyzers.ID, c.storageKey() + "/")));
                    texts.forget(c.storageKey());
                }
                case MOVE -> move(c.storageKey(), c.toKey());
            }
            applied = c.seq();
            dirty = true;
        }
    }

    /** The document for a stored file or folder, or empty when it is gone or not searchable. */
    private Optional<Document> document(String key) throws IOException {
        Optional<Place> place = SearchAccess.place(key);
        if (place.isEmpty() || place.get().relPath().isEmpty() || !access.indexed(place.get())) {
            return Optional.empty();
        }
        StoragePath path = StoragePath.of(key);
        Optional<ObjectInfo> o = app.storage.stat(path);
        if (o.isEmpty()) {
            return Optional.empty();
        }
        long modified = o.get().lastModified() == null ? 0 : o.get().lastModified().toEpochMilli();
        if (o.get().directory()) {
            return Optional.of(build(key, place.get(), path.name(), true, "folder", 0, modified, Long.MAX_VALUE, "ok",
                    null));
        }
        TextStore.Text t = texts.get(path, o.get());
        long expires = app.expiryRepo.find(key).map(FileExpiry::expiresAt).orElse(Long.MAX_VALUE);
        return Optional.of(build(key, place.get(), path.name(), false, t.type(), o.get().size(), modified, expires,
                t.state(), t.text()));
    }

    private Document build(String key, Place place, String name, boolean folder, String type, long size,
                           long modified, long expires, String state, String content) {
        Document d = new Document();
        d.add(new StringField(Analyzers.ID, key, Field.Store.YES));
        d.add(new StringField(Analyzers.AREA, place.area(), Field.Store.YES));
        d.add(new StringField(Analyzers.REL, place.relPath(), Field.Store.YES));
        d.add(new TextField(Analyzers.PATH, place.relPath(), Field.Store.NO));
        d.add(new TextField(Analyzers.NAME, name, Field.Store.YES));
        d.add(new TextField(Analyzers.NAME_PREFIX, name, Field.Store.NO));
        if (content != null && !content.isEmpty()) {
            d.add(new TextField(Analyzers.CONTENT, content, Field.Store.YES));
        }
        d.add(new StringField(Analyzers.TYPE, type, Field.Store.YES));
        d.add(new StringField(Analyzers.FOLDER, folder ? "1" : "0", Field.Store.YES));
        d.add(new StringField(Analyzers.STATE, state, Field.Store.YES));
        for (Object[] f : new Object[][]{{Analyzers.SIZE, size}, {Analyzers.MODIFIED, modified},
                {Analyzers.EXPIRES, expires}}) {
            String n = (String) f[0];
            long v = (Long) f[1];
            d.add(new LongPoint(n, v));
            d.add(new NumericDocValuesField(n, v));
            d.add(new StoredField(n, v));
        }
        return d;
    }

    /** A move or rename: documents are rewritten from their stored fields, without extracting again. */
    private void move(String from, String to) throws IOException {
        org.apache.lucene.search.Query old = new BooleanQuery.Builder()
                .add(new TermQuery(new Term(Analyzers.ID, from)), Occur.SHOULD)
                .add(new PrefixQuery(new Term(Analyzers.ID, from + "/")), Occur.SHOULD).build();
        List<Document> moved = new ArrayList<>();
        try (DirectoryReader r = DirectoryReader.open(writer)) {
            IndexSearcher s = new IndexSearcher(r);
            TopDocs top = s.search(old, Math.max(1, r.maxDoc()));
            for (ScoreDoc sd : top.scoreDocs) {
                Document stored = s.storedFields().document(sd.doc);
                String newKey = to + stored.get(Analyzers.ID).substring(from.length());
                Optional<Place> place = SearchAccess.place(newKey);
                if (place.isEmpty() || !access.indexed(place.get())) {
                    continue;
                }
                String name = stored.get(Analyzers.ID).equals(from) ? StoragePath.of(newKey).name()
                        : stored.get(Analyzers.NAME);
                moved.add(build(newKey, place.get(), name, "1".equals(stored.get(Analyzers.FOLDER)),
                        stored.get(Analyzers.TYPE), num(stored, Analyzers.SIZE), num(stored, Analyzers.MODIFIED),
                        num(stored, Analyzers.EXPIRES), stored.get(Analyzers.STATE), stored.get(Analyzers.CONTENT)));
            }
        }
        writer.deleteDocuments(old);
        for (Document d : moved) {
            writer.updateDocument(new Term(Analyzers.ID, d.get(Analyzers.ID)), d);
        }
        texts.forget(from);
    }

    private static long num(Document d, String field) {
        return d.getField(field).numericValue().longValue();
    }

    /**
     * Builds a fresh index from a listing of the storage beside the live one, which keeps answering until the new
     * one is complete; then swaps them.
     */
    private void rebuild() throws IOException {
        rebuilding = true;
        rebuildRequested = false;
        try {
            long seq = repo.latest();
            Path next = root.resolve("index-next");
            wipe(next);
            Files.createDirectories(next);
            try (Directory nd = directory(next); IndexWriter nw = new IndexWriter(nd, writerConfig())) {
                for (String top : List.of("users", "folders")) {
                    List<ObjectInfo> all;
                    try (Stream<ObjectInfo> s = app.storage.list(StoragePath.of(top), true)) {
                        all = s.toList();
                    } catch (NoSuchFileException | FdropException e) {
                        continue;
                    }
                    List<Future<Optional<Document>>> pending = new ArrayList<>();
                    for (ObjectInfo o : all) {
                        String key = o.path().toString();
                        pending.add(extractPool.submit(() -> document(key)));
                    }
                    for (Future<Optional<Document>> f : pending) {
                        try {
                            Optional<Document> d = f.get();
                            if (d.isPresent()) {
                                nw.addDocument(d.get());
                            }
                        } catch (Exception e) {
                            log.warn("rebuild: could not index a file: {}", e.toString());
                        }
                    }
                }
                nw.setLiveCommitData(Map.of(SEQ, Long.toString(seq)).entrySet());
                nw.commit();
            }
            SearcherManager oldManager = manager;
            writer.close();
            dir.close();
            Path live = root.resolve("index");
            wipe(live);
            Files.move(next, live);
            open(live);
            oldManager.close();
            needsRebuild = false;
            log.info("search index rebuilt up to change {}", seq);
        } finally {
            rebuilding = false;
        }
    }

    // ---- searching ----

    private void limitRate(Principal p) {
        long now = System.currentTimeMillis();
        Deque<Long> q = rate.computeIfAbsent(p.sub(), k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < now - 60_000) {
                q.pollFirst();
            }
            if (q.size() >= cfg.rateLimitPerMinute) {
                app.metrics.searchQuery("rate_limited", 0);
                throw new FdropException(429, "Too many searches", "Too many searches; wait a minute and try"
                        + " again").withType("search-rate-limited");
            }
            q.addLast(now);
        }
    }

    private static FdropException invalid(String detail) {
        return FdropException.badRequest(detail).withType("invalid-search");
    }

    /** The query terms to highlight: words not excluded with '-', and prefixes ending in '*'. */
    private record Terms(Set<String> exact, List<String> prefixes) {
        boolean matches(String token) {
            if (exact.contains(token)) {
                return true;
            }
            for (String p : prefixes) {
                if (token.startsWith(p)) {
                    return true;
                }
            }
            return false;
        }
    }

    private Terms highlightTerms(String text, Analyzer analyzer) {
        Set<String> exact = new HashSet<>();
        List<String> prefixes = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            if ("-".equals(m.group(1))) {
                continue;
            }
            for (String t : tokens(analyzer, m.group(2))) {
                if (m.group(3).isEmpty()) {
                    exact.add(t);
                } else {
                    prefixes.add(t);
                }
            }
        }
        return new Terms(exact, prefixes);
    }

    private static List<String> tokens(Analyzer analyzer, String text) {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream("q", new StringReader(text))) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                out.add(term.toString());
            }
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private org.apache.lucene.search.Query parse(String text, Map<String, Float> weights) {
        int flags = SimpleQueryParser.AND_OPERATOR | SimpleQueryParser.NOT_OPERATOR | SimpleQueryParser.OR_OPERATOR
                | SimpleQueryParser.PHRASE_OPERATOR | SimpleQueryParser.PREFIX_OPERATOR
                | SimpleQueryParser.PRECEDENCE_OPERATORS | SimpleQueryParser.ESCAPE_OPERATOR
                | SimpleQueryParser.WHITESPACE_OPERATOR;
        SimpleQueryParser p = new SimpleQueryParser(analyzers.perField, weights, flags);
        p.setDefaultOperator(Occur.MUST);
        return p.parse(text);
    }

    @Override
    public Result search(Principal p, SearchService.Query q) {
        long start = System.nanoTime();
        if (!p.canUseFiles()) {
            throw FdropException.forbidden("the standard role is required to search files");
        }
        limitRate(p);
        try {
            Result r = run(p, q);
            app.metrics.searchQuery("ok", System.nanoTime() - start);
            Audit.log("search", "user", p.username(), "scope", q.scope(), "results", r.total(),
                    "millis", (System.nanoTime() - start) / 1_000_000,
                    "query", cfg.auditQueries ? q.q() : null);
            return r;
        } catch (FdropException e) {
            app.metrics.searchQuery("invalid-search".equals(e.type()) ? "invalid" : "error", 0);
            throw e;
        } catch (IOException | RuntimeException e) {
            app.metrics.searchQuery("error", 0);
            throw e instanceof RuntimeException re ? re : new UncheckedIOException((IOException) e);
        }
    }

    private Result run(Principal p, SearchService.Query q) throws IOException {
        String text = q.q() == null ? "" : q.q().strip();
        if (text.length() > MAX_QUERY) {
            throw invalid("Searches can be at most " + MAX_QUERY + " characters long");
        }
        if (text.split("\\s+").length > MAX_TERMS) {
            throw invalid("Searches can have at most " + MAX_TERMS + " words");
        }
        Matcher shortPrefix = Pattern.compile("(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}]?\\*").matcher(text);
        if (shortPrefix.find()) {
            throw invalid("Put at least 2 letters before a '*'");
        }
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        String type = q.type();
        StringBuilder free = new StringBuilder();
        Matcher m = FIELD.matcher(text);
        int last = 0;
        boolean any = false;
        StringBuilder nameTerms = new StringBuilder();
        StringBuilder contentTerms = new StringBuilder();
        while (m.find()) {
            free.append(text, last, m.start());
            last = m.end();
            String field = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(2);
            switch (field) {
                case "type" -> {
                    if (!TYPES.contains(value.toLowerCase(Locale.ROOT))) {
                        throw invalid("type: can be pdf, doc, sheet, slides, text, email, other or folder");
                    }
                    type = value.toLowerCase(Locale.ROOT);
                }
                case "name" -> {
                    b.add(parse(value, Map.of(Analyzers.NAME, 1f)), Occur.MUST);
                    nameTerms.append(' ').append(value);
                    any = true;
                }
                default -> {
                    b.add(parse(value, Map.of(Analyzers.CONTENT, 1f)), Occur.MUST);
                    contentTerms.append(' ').append(value);
                    any = true;
                }
            }
        }
        free.append(text.substring(last));
        String rest = free.toString().strip();
        if (!rest.isEmpty()) {
            b.add(parse(rest, Map.of(Analyzers.NAME, 3f, Analyzers.PATH, 1.5f, Analyzers.CONTENT, 1f)), Occur.MUST);
            any = true;
        }
        if (!any && type == null) {
            throw invalid("Type a word to search for");
        }
        if (!any) {
            b.add(new org.apache.lucene.search.MatchAllDocsQuery(), Occur.MUST);
        }
        filters(b, p, q.scope(), q.path());
        if (type != null && !type.isBlank()) {
            if (!TYPES.contains(type)) {
                throw invalid("Unknown type '" + type + "'");
            }
            b.add(new TermQuery(new Term(Analyzers.TYPE, type)), Occur.FILTER);
        }
        if (q.modifiedFrom() != null) {
            b.add(LongPoint.newRangeQuery(Analyzers.MODIFIED, q.modifiedFrom(), Long.MAX_VALUE), Occur.FILTER);
        }
        Sort sort = "newest".equals(q.sort())
                ? new Sort(new SortField(Analyzers.MODIFIED, SortField.Type.LONG, true))
                : new Sort(SortField.FIELD_SCORE, new SortField(Analyzers.MODIFIED, SortField.Type.LONG, true));
        int limit = Math.max(1, Math.min(50, q.limit() <= 0 ? 20 : q.limit()));
        int offset = cursor(q.cursor());
        if (offset >= cfg.maxResults) {
            return new Result(0, List.of(), null);
        }
        int want = Math.min(cfg.maxResults, offset + limit);
        Terms nameHl = highlightTerms(rest + nameTerms, analyzers.name);
        Terms contentHl = highlightTerms(rest + contentTerms, analyzers.content);
        IndexSearcher s = manager.acquire();
        try {
            TopDocs top = s.search(b.build(), want, sort, true);
            List<Hit> hits = new ArrayList<>();
            for (int i = offset; i < top.scoreDocs.length; i++) {
                Document d = s.storedFields().document(top.scoreDocs[i].doc);
                hit(p, d, nameHl, contentHl).ifPresent(hits::add);
            }
            long total = top.totalHits.value();
            String next = want < Math.min(total, cfg.maxResults)
                    ? Base64.getUrlEncoder().withoutPadding().encodeToString(Integer.toString(want)
                    .getBytes(StandardCharsets.US_ASCII)) : null;
            return new Result(total, hits, next);
        } finally {
            release(s);
        }
    }

    private static int cursor(String c) {
        if (c == null || c.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(new String(Base64.getUrlDecoder().decode(c),
                    StandardCharsets.US_ASCII)));
        } catch (IllegalArgumentException e) {
            throw invalid("That page of results has expired; search again");
        }
    }

    /** Only the caller's areas, only files not yet expired, and optionally one folder. */
    private void filters(BooleanQuery.Builder b, Principal p, String scope, String path) {
        if ("folder".equals(scope)) {
            if (path == null || path.isBlank()) {
                throw invalid("scope=folder needs a path");
            }
            VirtualPath vp = app.files.resolver().resolve(p, path);
            if (vp.key() == null) {
                throw invalid("search everywhere, or in one of your folders");
            }
            Place place = SearchAccess.place(vp.key().toString()).orElseThrow(() -> invalid("not a searchable folder"));
            b.add(new TermQuery(new Term(Analyzers.AREA, place.area())), Occur.FILTER);
            if (!place.relPath().isEmpty()) {
                b.add(new PrefixQuery(new Term(Analyzers.REL, place.relPath() + "/")), Occur.FILTER);
            }
        }
        List<BytesRef> areas = access.areas(p).stream().map(BytesRef::new).toList();
        b.add(areas.isEmpty() ? new MatchNoDocsQuery() : new TermInSetQuery(Analyzers.AREA, areas), Occur.FILTER);
        b.add(LongPoint.newRangeQuery(Analyzers.EXPIRES, app.expiry.now().toEpochMilli() + 1, Long.MAX_VALUE),
                Occur.FILTER);
    }

    /** A result, if the caller can still open the file as of now; snippets only for those. */
    private Optional<Hit> hit(Principal p, Document d, Terms nameHl, Terms contentHl) {
        Place place = new Place(d.get(Analyzers.AREA), d.get(Analyzers.REL));
        Optional<String> vpath = access.virtualPath(place);
        if (vpath.isEmpty()) {
            return Optional.empty();
        }
        FileService.Entry e;
        try {
            e = app.files.stat(p, vpath.get());
        } catch (FdropException | UncheckedIOException ex) {
            return Optional.empty();
        }
        String content = d.get(Analyzers.CONTENT);
        List<List<Segment>> snippets = content == null ? List.of() : snippets(content, contentHl);
        boolean folder = "1".equals(d.get(Analyzers.FOLDER));
        return Optional.of(new Hit(e.path(), name(d.get(Analyzers.NAME), nameHl),
                access.location(place).orElse(""), d.get(Analyzers.TYPE), folder, e.size(), e.modified(),
                e.expiresAt(), snippets, d.get(Analyzers.STATE)));
    }

    /** Offsets of matching tokens in {@code text}, up to {@code max}. */
    private static List<int[]> matches(String text, Analyzer analyzer, Terms terms, int max) {
        List<int[]> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream("h", new StringReader(text))) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            OffsetAttribute off = ts.addAttribute(OffsetAttribute.class);
            ts.reset();
            while (ts.incrementToken() && out.size() < max) {
                if (terms.matches(term.toString())) {
                    out.add(new int[]{off.startOffset(), off.endOffset()});
                }
            }
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparingInt(a -> a[0]));
        return out;
    }

    private static List<Segment> segments(String text, int from, int to, List<int[]> marks) {
        List<Segment> out = new ArrayList<>();
        int at = from;
        for (int[] mk : marks) {
            int s = Math.max(mk[0], at);
            int e = Math.min(mk[1], to);
            if (e <= s || s < from) {
                continue;
            }
            if (s > at) {
                out.add(new Segment(text.substring(at, s), false));
            }
            out.add(new Segment(text.substring(s, e), true));
            at = e;
        }
        if (at < to) {
            out.add(new Segment(text.substring(at, to), false));
        }
        return out;
    }

    private List<Segment> name(String name, Terms terms) {
        return segments(name, 0, name.length(), matches(name, analyzers.name, terms, 20));
    }

    /** Up to two snippets of about 160 characters around the first matches, as plain text segments. */
    private List<List<Segment>> snippets(String content, Terms terms) {
        List<int[]> marks = matches(content, analyzers.content, terms, 50);
        List<List<Segment>> out = new ArrayList<>();
        int covered = -1;
        for (int[] mk : marks) {
            if (out.size() == 2) {
                break;
            }
            if (mk[0] < covered) {
                continue;
            }
            int from = Math.max(0, mk[0] - 60);
            int to = Math.min(content.length(), from + SNIPPET);
            while (from > 0 && from > mk[0] - 80 && !Character.isWhitespace(content.charAt(from - 1))) {
                from--;
            }
            while (to < content.length() && to < from + SNIPPET + 20 && !Character.isWhitespace(content.charAt(to))) {
                to++;
            }
            List<Segment> seg = new ArrayList<>();
            if (from > 0) {
                seg.add(new Segment("…", false));
            }
            seg.addAll(segments(content.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '), from, to, marks));
            if (to < content.length()) {
                seg.add(new Segment("…", false));
            }
            out.add(seg);
            covered = to;
        }
        return out;
    }

    @Override
    public List<Suggestion> suggest(Principal p, String q) {
        if (!p.canUseFiles() || q == null || q.strip().length() < 2 || q.length() > MAX_QUERY) {
            return List.of();
        }
        List<String> words = tokens(analyzers.name, q);
        if (words.isEmpty() || words.size() > MAX_TERMS) {
            return List.of();
        }
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (String w : new LinkedHashMap<String, Boolean>() {{
            words.forEach(x -> put(x.length() > 20 ? x.substring(0, 20) : x, true));
        }}.keySet()) {
            b.add(new TermQuery(new Term(Analyzers.NAME_PREFIX, w)), Occur.MUST);
        }
        filters(b, p, "all", null);
        Terms hl = highlightTerms(q.replaceAll("([\\p{L}\\p{N}]+)", "$1*"), analyzers.name);
        IndexSearcher s = null;
        try {
            s = manager.acquire();
            TopDocs top = s.search(b.build(), 20, new Sort(SortField.FIELD_SCORE,
                    new SortField(Analyzers.MODIFIED, SortField.Type.LONG, true)), true);
            List<Suggestion> out = new ArrayList<>();
            for (ScoreDoc sd : top.scoreDocs) {
                if (out.size() == 8) {
                    break;
                }
                Document d = s.storedFields().document(sd.doc);
                hit(p, d, hl, hl).ifPresent(h -> out.add(new Suggestion(h.path(), d.get(Analyzers.NAME),
                        h.location(), h.folder())));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(s);
        }
    }

    private void release(IndexSearcher s) {
        if (s != null) {
            try {
                manager.release(s);
            } catch (IOException | org.apache.lucene.store.AlreadyClosedException e) {
                log.debug("searcher release: {}", e.toString());
            }
        }
    }

    // ---- admin ----

    @Override
    public Status status(Principal admin) {
        DestinationService.requireAdmin(admin);
        long docs = 0;
        long size = 0;
        IndexSearcher s = null;
        try {
            s = manager.acquire();
            docs = s.getIndexReader().numDocs();
            for (String f : dir.listAll()) {
                try {
                    size += dir.fileLength(f);
                } catch (NoSuchFileException ignored) {
                    // merged away meanwhile
                }
            }
        } catch (IOException e) {
            log.debug("search status: {}", e.toString());
        } finally {
            release(s);
        }
        return new Status(true, docs, size, applied, repo.latest(), lagSeconds(), repo.extractCounts(), rebuilding);
    }

    @Override
    public void rebuild(Principal admin) {
        DestinationService.requireAdmin(admin);
        rebuildRequested = true;
        Audit.log("search.rebuild", "user", admin.username());
        loop.execute(this::safeTick);
    }

    @Override
    public synchronized void close() {
        closed = true;
        loop.shutdownNow();
        extractPool.shutdownNow();
        try {
            if (dirty) {
                commit();
            }
            manager.close();
            writer.close();
            dir.close();
        } catch (IOException | RuntimeException e) {
            log.warn("closing the search index: {}", e.toString());
        }
    }
}
