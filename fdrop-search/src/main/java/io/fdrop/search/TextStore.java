package io.fdrop.search;

import io.fdrop.core.audit.Audit;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.SearchRepo;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.storage.WriteHandle;
import io.fdrop.core.storage.WriteOptions;
import io.fdrop.core.util.Hashing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Each file version's text, extracted once across all nodes: the first node to claim it extracts and stores the text
 * as an object under {@code system/search-text/} (through the encryption layer), and the others read it from there.
 */
final class TextStore {

    /** A file's text for the index; {@code state} is ok, unsupported, too_large, protected, failed or pending. */
    record Text(String state, String type, String text) {
    }

    static final String PREFIX = "system/search-text/";
    private static final int HEAD = 64 * 1024;

    private final FdropConfig.SearchExtract cfg;
    private final StorageBackend storage;
    private final SearchRepo repo;
    private final Extractor extractor;
    private final FdropMetrics metrics;
    private final String node;

    TextStore(FdropConfig.SearchExtract cfg, StorageBackend storage, SearchRepo repo, Extractor extractor,
              FdropMetrics metrics, String node) {
        this.cfg = cfg;
        this.storage = storage;
        this.repo = repo;
        this.extractor = extractor;
        this.metrics = metrics;
        this.node = node;
    }

    static String version(ObjectInfo o) {
        return o.etag() + "|" + o.size() + "|" + (o.lastModified() == null ? 0 : o.lastModified().toEpochMilli());
    }

    private static String textKey(String key, String version) {
        return PREFIX + Hashing.sha256Hex((key + "|" + version).getBytes(StandardCharsets.UTF_8));
    }

    Text get(StoragePath path, ObjectInfo info) throws IOException {
        String key = path.toString();
        String version = version(info);
        long limit = Durations.parse(cfg.timeout).toMillis() + 30_000;
        long deadline = System.currentTimeMillis() + limit;
        while (true) {
            Optional<SearchRepo.Extract> e = repo.extract(key, version);
            if (e.isPresent() && !e.get().state().equals("PENDING")) {
                return stored(e.get());
            }
            long now = System.currentTimeMillis();
            if (repo.claim(key, version, node, now + limit, now)) {
                return extract(path, info, version);
            }
            if (now > deadline) {
                return new Text("pending", e.map(SearchRepo.Extract::type).orElse("other"), null);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new Text("pending", "other", null);
            }
        }
    }

    private Text stored(SearchRepo.Extract e) throws IOException {
        String state = e.state().toLowerCase(Locale.ROOT);
        String type = e.type() == null ? "other" : e.type();
        if (!e.state().equals("OK") || e.textKey() == null) {
            return new Text(state, type, null);
        }
        try (ReadableByteChannel ch = storage.openRead(StoragePath.of(e.textKey()), 0, -1)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Channels.newInputStream(ch).transferTo(out);
            return new Text("ok", type, out.toString(StandardCharsets.UTF_8));
        } catch (java.nio.file.NoSuchFileException ex) {
            return new Text("failed", type, null);
        }
    }

    private Text extract(StoragePath path, ObjectInfo info, String version) throws IOException {
        String key = path.toString();
        long start = System.nanoTime();
        byte[] head;
        try (ReadableByteChannel ch = storage.openRead(path, 0, Math.min(HEAD, info.size()))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Channels.newInputStream(ch).transferTo(out);
            head = out.toByteArray();
        }
        String type = extractor.family(head, path.name());
        if (type.equals("other")) {
            return finish(key, version, "UNSUPPORTED", null, type, start);
        }
        if (info.size() > cfg.maxFileSize.bytes()) {
            return finish(key, version, "TOO_LARGE", null, type, start);
        }
        Extractor.Outcome o;
        try (InputStream in = Channels.newInputStream(storage.openRead(path, 0, -1))) {
            o = extractor.extract(in);
        } catch (IOException | RuntimeException e) {
            o = new Extractor.Outcome("FAILED", null);
        }
        if (!o.state().equals("OK")) {
            String state = o.state().equals("TIMEOUT") ? "FAILED" : o.state();
            metrics.searchExtract(o.state().toLowerCase(Locale.ROOT), System.nanoTime() - start);
            repo.finish(key, version, state, null, type);
            Audit.log("search.extract.failed", "storageKey", key, "reason", o.state().toLowerCase(Locale.ROOT));
            return new Text(state.toLowerCase(Locale.ROOT), type, null);
        }
        String textKey = textKey(key, version);
        byte[] bytes = o.text().getBytes(StandardCharsets.UTF_8);
        try (WriteHandle h = storage.openWrite(StoragePath.of(textKey), new WriteOptions(null, true, bytes.length))) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            while (b.hasRemaining()) {
                h.write(b);
            }
            h.commit();
        }
        metrics.searchExtract("ok", System.nanoTime() - start);
        repo.finish(key, version, "OK", textKey, type);
        return new Text("ok", type, o.text());
    }

    private Text finish(String key, String version, String state, String textKey, String type, long start) {
        metrics.searchExtract(state.toLowerCase(Locale.ROOT), System.nanoTime() - start);
        repo.finish(key, version, state, textKey, type);
        return new Text(state.toLowerCase(Locale.ROOT), type, null);
    }

    /** Removes the cached text of a file, or of everything under a folder. */
    void forget(String key) {
        for (SearchRepo.Extract e : repo.under(key)) {
            if (e.textKey() != null) {
                try {
                    storage.delete(StoragePath.of(e.textKey()));
                } catch (IOException ignored) {
                    // left behind: a later rebuild of the cache ignores it
                }
            }
            repo.deleteExtract(e.storageKey(), e.version());
        }
    }
}
