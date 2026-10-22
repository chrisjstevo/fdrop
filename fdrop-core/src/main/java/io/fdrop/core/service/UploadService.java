package io.fdrop.core.service;

import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.UploadRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.model.Enums.UploadState;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Records.UploadChunk;
import io.fdrop.core.model.Records.UploadSession;
import io.fdrop.core.storage.MultipartUpload;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.PartInfo;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.storage.WriteHandle;
import io.fdrop.core.storage.WriteOptions;
import io.fdrop.core.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resumable browser uploads: a session per file, one durable record per acknowledged chunk, and assembly into the
 * final file on {@code complete}. A chunk row is written only after its bytes are durable in the backend, so the
 * database never claims a chunk that is not stored.
 */
public final class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final long MIN_CHUNK = 5L * 1024 * 1024;
    private static final int MAX_PARTS = 10_000;
    private static final long WAIT_FOR_ASSEMBLY_MS = 2_000;

    /** What the client asks for when starting an upload. */
    public record CreateRequest(String path, long size, Long lastModified, String sha256, boolean overwrite) {
    }

    /** Session state as the status endpoint reports it. */
    public record Status(UploadSession session, List<long[]> received, long bytesReceived) {
    }

    private final FdropConfig cfg;
    private final StorageBackend storage;
    private final FileService files;
    private final UploadRepo repo;
    private final UserRepo users;
    private final UploadRecorder recorder;
    private final RecordRepo records;
    private final FdropMetrics metrics;
    private final Map<String, Boolean> chunkLocks = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<UploadSession>> assemblies = new ConcurrentHashMap<>();

    public UploadService(FdropConfig cfg, StorageBackend storage, FileService files, UploadRepo repo, UserRepo users,
                         UploadRecorder recorder, RecordRepo records, FdropMetrics metrics) {
        this.cfg = cfg;
        this.storage = storage;
        this.files = files;
        this.repo = repo;
        this.users = users;
        this.recorder = recorder;
        this.records = records;
        this.metrics = metrics;
    }

    /** Server-chosen chunk size: the configured size, raised so a file never needs more than 10,000 parts. */
    public long chunkSizeFor(long size) {
        long cs = Math.max(cfg.upload.chunkSize.bytes(), MIN_CHUNK);
        long needed = (size + MAX_PARTS - 1) / MAX_PARTS;
        if (needed > cs) {
            long mib = 1024L * 1024L;
            cs = ((needed + mib - 1) / mib) * mib;
        }
        return cs;
    }

    public UploadSession create(Principal p, CreateRequest req, String clientIp) {
        if (req.size() < 0) {
            throw FdropException.badRequest("size must not be negative");
        }
        VirtualPath vp = files.resolver().resolve(p, req.path());
        Optional<ObjectInfo> existing = files.checkWritable(p, vp, req.overwrite());
        files.checkSize(vp.quotaOwner(), req.size(), existing.map(ObjectInfo::size).orElse(0L));
        if (req.sha256() != null && !req.sha256().matches("[0-9a-fA-F]{64}")) {
            throw FdropException.badRequest("sha256 must be 64 hex characters");
        }
        long chunkSize = chunkSizeFor(req.size());
        int chunkCount = req.size() == 0 ? 0 : (int) ((req.size() + chunkSize - 1) / chunkSize);
        String id = Hashing.newId("upl");
        String backendId = null;
        if (chunkCount > 0) {
            try {
                MultipartUpload mp = storage.beginMultipart(vp.key(), new WriteOptions(null, req.overwrite(),
                        req.size()));
                backendId = mp.id();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        long now = System.currentTimeMillis();
        UploadSession s = new UploadSession(id, p.sub(), vp.virtual(), vp.key().toString(),
                vp.folder() == null ? null : vp.folder().id(), vp.wentTo(), vp.name(), req.size(), chunkSize,
                chunkCount, req.sha256() == null ? null : req.sha256().toLowerCase(Locale.ROOT), UploadState.OPEN,
                backendId, req.overwrite(), clientIp, req.lastModified(), null, now, now, now + ttlMillis());
        repo.insert(s);
        return s;
    }

    private long ttlMillis() {
        return Durations.parse(cfg.upload.ttl).toMillis();
    }

    public UploadSession get(Principal p, String id) {
        UploadSession s = repo.find(id).orElseThrow(() -> FdropException.notFound("no such upload"));
        if (!s.userSub().equals(p.sub())) {
            throw FdropException.notFound("no such upload");
        }
        return s;
    }

    public Status status(Principal p, String id) {
        UploadSession s = get(p, id);
        List<UploadChunk> chunks = repo.chunks(id);
        List<long[]> ranges = new ArrayList<>();
        long bytes = 0;
        for (UploadChunk c : chunks) {
            bytes += c.size();
            if (!ranges.isEmpty() && ranges.get(ranges.size() - 1)[1] == c.idx() - 1) {
                ranges.get(ranges.size() - 1)[1] = c.idx();
            } else {
                ranges.add(new long[]{c.idx(), c.idx()});
            }
        }
        return new Status(s, ranges, bytes);
    }

    public long expectedChunkLength(UploadSession s, int index) {
        if (index < 0 || index >= s.chunkCount()) {
            throw FdropException.badRequest("chunk index out of range 0.." + (s.chunkCount() - 1));
        }
        return index == s.chunkCount() - 1 ? s.size() - s.chunkSize() * index : s.chunkSize();
    }

    /** Outcome of a chunk PUT. */
    public enum ChunkResult { STORED, DUPLICATE }

    /**
     * Stores one chunk, reading exactly {@code length} bytes from {@code body}. Resending a stored chunk with the
     * same hash is a no-op; a different hash is a conflict, as is a concurrent PUT of the same index.
     */
    public ChunkResult putChunk(Principal p, String id, int index, ReadableByteChannel body, long length,
                                String sha256Hex) {
        UploadSession s = get(p, id);
        if (s.state() != UploadState.OPEN) {
            throw FdropException.conflict("upload is " + s.state());
        }
        long expected = expectedChunkLength(s, index);
        if (length != expected) {
            throw FdropException.badRequest("chunk " + index + " must be exactly " + expected + " bytes");
        }
        String sha = sha256Hex == null ? null : sha256Hex.toLowerCase(Locale.ROOT);
        Optional<UploadChunk> stored = repo.chunk(id, index);
        if (stored.isPresent()) {
            drain(body);
            if (sha != null && stored.get().sha256() != null && !stored.get().sha256().equals(sha)) {
                metrics.chunk("rejected");
                throw FdropException.conflict("chunk " + index + " was already stored with different content");
            }
            metrics.chunk("duplicate");
            return ChunkResult.DUPLICATE;
        }
        String lock = id + ":" + index;
        if (chunkLocks.putIfAbsent(lock, Boolean.TRUE) != null) {
            throw FdropException.conflict("chunk " + index + " is already being uploaded");
        }
        try {
            MultipartUpload mp = storage.resumeMultipart(StoragePath.of(s.storageKey()), s.backendUploadId());
            Hashing.DigestingChannel digesting = new Hashing.DigestingChannel(body, length);
            PartInfo part;
            try {
                part = mp.putPart(index + 1, digesting, length, sha);
            } catch (IOException e) {
                metrics.chunk("rejected");
                if (e.getMessage() != null && e.getMessage().contains("SHA-256")) {
                    throw FdropException.badRequest("chunk " + index + " does not match X-Chunk-SHA256");
                }
                if (e.getMessage() != null && e.getMessage().contains("short")) {
                    throw FdropException.badRequest("chunk " + index + " body ended early");
                }
                throw e;
            }
            repo.insertChunk(new UploadChunk(id, index, part.size(), digesting.hex(), part.etag(),
                    System.currentTimeMillis()));
            repo.extend(id, System.currentTimeMillis() + ttlMillis());
            metrics.chunk("stored");
            metrics.transfer("http", "upload", part.size());
            return ChunkResult.STORED;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            chunkLocks.remove(lock);
        }
    }

    private static void drain(ReadableByteChannel body) {
        ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
        try {
            while (body.read(buf) >= 0) {
                buf.clear();
            }
        } catch (IOException ignored) {
            // the client is gone; nothing to keep
        }
    }

    /**
     * Assembles the chunks. Returns the session once {@code COMPLETED}; if assembly takes longer than two seconds,
     * returns it still {@code ASSEMBLING} and finishes in the background (the client polls status).
     */
    public UploadSession complete(Principal p, String id, String clientIp) {
        return complete(p, id, clientIp, null);
    }

    /**
     * As {@link #complete(Principal, String, String)}; {@code sha256Hex} is the whole-file hash when the client
     * could only compute it while sending (browsers hash chunk by chunk), used if none was given at create.
     */
    public UploadSession complete(Principal p, String id, String clientIp, String sha256Hex) {
        UploadSession s = get(p, id);
        if (sha256Hex != null && !sha256Hex.isBlank()) {
            if (!sha256Hex.matches("[0-9a-fA-F]{64}")) {
                throw FdropException.badRequest("sha256 must be 64 hex characters");
            }
            if (s.sha256() != null && !s.sha256().equalsIgnoreCase(sha256Hex)) {
                throw FdropException.conflict("sha256 differs from the one given at create");
            }
        }
        String expectedSha = s.sha256() != null ? s.sha256()
                : sha256Hex == null || sha256Hex.isBlank() ? null : sha256Hex.toLowerCase(Locale.ROOT);
        if (s.state() == UploadState.COMPLETED || s.state() == UploadState.ASSEMBLING) {
            return waitFor(s);
        }
        if (s.state() != UploadState.OPEN) {
            throw FdropException.conflict("upload is " + s.state());
        }
        List<UploadChunk> chunks = repo.chunks(id);
        if (chunks.size() != s.chunkCount()) {
            List<Integer> missing = new ArrayList<>();
            boolean[] have = new boolean[s.chunkCount()];
            chunks.forEach(c -> have[c.idx()] = true);
            for (int i = 0; i < have.length && missing.size() < 20; i++) {
                if (!have[i]) {
                    missing.add(i);
                }
            }
            throw FdropException.conflict("chunks still missing: " + missing);
        }
        if (!repo.transition(id, UploadState.OPEN, UploadState.ASSEMBLING, null)) {
            return waitFor(get(p, id));
        }
        CompletableFuture<UploadSession> f = new CompletableFuture<>();
        assemblies.put(id, f);
        Thread.ofVirtual().name("assemble-" + id).start(() -> {
            try {
                f.complete(assemble(p, s, chunks, clientIp, expectedSha));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            } finally {
                assemblies.remove(id);
            }
        });
        return waitFor(s);
    }

    private UploadSession waitFor(UploadSession s) {
        CompletableFuture<UploadSession> f = assemblies.get(s.id());
        if (f != null) {
            try {
                return f.get(WAIT_FOR_ASSEMBLY_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return repo.find(s.id()).orElse(s);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof FdropException fe) {
                    throw fe;
                }
                throw new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        UploadSession now = repo.find(s.id()).orElse(s);
        if (now.state() == UploadState.FAILED) {
            throw FdropException.conflict("upload failed: " + now.error());
        }
        return now;
    }

    private UploadSession assemble(Principal p, UploadSession s, List<UploadChunk> chunks, String clientIp,
                                   String expectedSha) {
        long start = System.nanoTime();
        StoragePath key = StoragePath.of(s.storageKey());
        VirtualPath vp = files.resolver().resolve(p, s.virtualPath());
        try {
            Optional<ObjectInfo> existing = files.checkWritable(p, vp, s.overwrite());
            long replacing = existing.map(ObjectInfo::size).orElse(0L);
            ObjectInfo info;
            if (s.chunkCount() == 0) {
                try (WriteHandle h = storage.openWrite(key, new WriteOptions(null, s.overwrite(), 0))) {
                    info = h.commit();
                }
            } else {
                MultipartUpload mp = storage.resumeMultipart(key, s.backendUploadId());
                List<PartInfo> parts = new ArrayList<>();
                for (UploadChunk c : chunks) {
                    parts.add(new PartInfo(c.idx() + 1, c.size(), c.etag(), c.sha256()));
                }
                info = mp.complete(parts);
            }
            if (info.size() != s.size()) {
                storage.delete(key);
                throw FdropException.conflict("assembled size " + info.size() + " does not match " + s.size());
            }
            if (expectedSha != null) {
                String actual = sha256Of(key);
                if (!actual.equals(expectedSha)) {
                    storage.delete(key);
                    throw FdropException.conflict("the assembled file's SHA-256 does not match");
                }
            }
            users.addUsage(vp.quotaOwner(), info.size() - replacing);
            if (replacing > 0) {
                records.markDeleted(key.toString());
            }
            repo.transition(s.id(), UploadState.ASSEMBLING, UploadState.COMPLETED, null);
            metrics.assembly(storage.type(), System.nanoTime() - start);
            recorder.record(p, vp, info, Via.HTTP, clientIp, s.startIp());
            return repo.find(s.id()).orElseThrow();
        } catch (RuntimeException | IOException e) {
            String msg = e instanceof FdropException fe ? fe.getMessage() : e.toString();
            log.warn("assembly of upload {} failed: {}", s.id(), msg);
            repo.transition(s.id(), UploadState.ASSEMBLING, UploadState.FAILED, truncate(msg));
            abortBackend(s);
            if (e instanceof FdropException fe) {
                throw fe;
            }
            throw FdropException.conflict("assembly failed: " + msg);
        }
    }

    private String sha256Of(StoragePath key) throws IOException {
        Hashing.DigestingChannel ch = new Hashing.DigestingChannel(storage.openRead(key, 0, -1), -1);
        try (ch) {
            ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
            while (ch.read(buf) >= 0) {
                buf.clear();
            }
        }
        return ch.hex();
    }

    public void abort(Principal p, String id) {
        UploadSession s = get(p, id);
        if (s.state() == UploadState.COMPLETED) {
            throw FdropException.conflict("upload already completed");
        }
        if (repo.transition(id, UploadState.OPEN, UploadState.ABORTED, null)) {
            abortBackend(s);
        }
    }

    /** Expires one session: used by the janitor. */
    public void expire(UploadSession s) {
        if (repo.transition(s.id(), UploadState.OPEN, UploadState.EXPIRED, null)) {
            abortBackend(s);
        }
    }

    private void abortBackend(UploadSession s) {
        if (s.backendUploadId() == null) {
            return;
        }
        try {
            storage.resumeMultipart(StoragePath.of(s.storageKey()), s.backendUploadId()).abort();
        } catch (IOException | RuntimeException e) {
            log.warn("could not discard parts of upload {}: {}", s.id(), e.toString());
        }
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 900 ? s.substring(0, 900) : s;
    }
}
