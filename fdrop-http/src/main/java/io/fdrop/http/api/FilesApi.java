package io.fdrop.http.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.Fdrop;
import io.fdrop.core.audit.Audit;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.expiry.Expiry;
import io.fdrop.core.model.Enums.ExpirySource;
import io.fdrop.core.model.Enums.Via;
import io.fdrop.core.model.Records.UploadSession;
import io.fdrop.core.service.FileService;
import io.fdrop.core.service.ExpiryService;
import io.fdrop.core.service.FileService.Entry;
import io.fdrop.core.service.UploadService;
import io.fdrop.core.service.VirtualPath;
import io.fdrop.http.Ctx;
import io.fdrop.http.Response;
import io.fdrop.http.Router;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code /api/v1/files} and {@code /api/v1/uploads}. */
public final class FilesApi {

    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    private final Fdrop app;

    public FilesApi(Fdrop app) {
        this.app = app;
    }

    public void register(Router r) {
        r.get("/api/v1/files", this::list);
        r.get("/api/v1/files/content", this::download);
        r.putStreaming("/api/v1/files/content", this::simpleUpload);
        r.post("/api/v1/files/mkdir", this::mkdir);
        r.post("/api/v1/files/move", this::move);
        r.delete("/api/v1/files", this::delete);
        r.patch("/api/v1/files", this::changeExpiry);

        r.post("/api/v1/uploads", this::createUpload);
        r.get("/api/v1/uploads/{id}", this::uploadStatus);
        r.putStreaming("/api/v1/uploads/{id}/chunks/{index}", this::putChunk);
        r.post("/api/v1/uploads/{id}/complete", this::complete);
        r.delete("/api/v1/uploads/{id}", this::abort);
    }

    static Map<String, Object> entry(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.name());
        m.put("path", e.path());
        m.put("type", e.type().name());
        m.put("size", e.size());
        m.put("modified", e.modified() == 0 ? null : Instant.ofEpochMilli(e.modified()).toString());
        m.put("writable", e.writable());
        if (e.folderId() != null) {
            m.put("folderId", e.folderId());
        }
        if (e.type() == FileService.EntryType.FILE) {
            m.put("expiresAt", e.expiresAt() == null ? null : Instant.ofEpochMilli(e.expiresAt()).toString());
            m.put("expiresIn", e.expiresIn());
            m.put("expirySource", source(e.expirySource()));
        }
        return m;
    }

    /** {@code FOLDER_DEFAULT} → {@code folderDefault}. */
    static String source(ExpirySource s) {
        if (s == null) {
            return null;
        }
        String[] parts = s.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder b = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            b.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return b.toString();
    }

    /** What an upload into this folder gets, so the UI can show it without another call. */
    private Map<String, Object> folderExpiry(Ctx ctx, String path) {
        VirtualPath vp = app.files.resolver().resolve(ctx.principal(), path);
        if (vp.isSynthetic()) {
            return null;
        }
        ExpiryService.Policy pol = app.expiry.policy(vp);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", app.expiry.enabled());
        m.put("allowed", pol.allowed() && app.expiry.enabled());
        m.put("default", pol.defaultExpiry() == null ? null : pol.defaultExpiry().canonical());
        m.put("max", pol.areaMax() == null ? null : pol.areaMax().canonical());
        m.put("serverMax", app.expiry.serverMax().canonical());
        return m;
    }

    /** The expiry given with an upload, from a body field, the query or the header; two different ones are refused. */
    private String expiryInput(Ctx ctx, String fromBody) {
        String header = ctx.header("X-Fdrop-Expires-In");
        String query = ctx.query("expiresIn");
        String value = null;
        for (String v : new String[]{fromBody, query, header}) {
            if (v == null) {
                continue;
            }
            if (value != null && !sameExpiry(value, v)) {
                throw app.expiry.conflicting(ctx.principal(), Via.HTTP, value, v);
            }
            value = value == null ? v : value;
        }
        return value;
    }

    private static boolean sameExpiry(String a, String b) {
        try {
            return Expiry.parse(a).equals(Expiry.parse(b));
        } catch (FdropException e) {
            return a.strip().equalsIgnoreCase(b.strip());
        }
    }

    private Response list(Ctx ctx) {
        String path = ctx.query("path") == null ? "/" : ctx.query("path");
        Entry self = app.files.stat(ctx.principal(), path);
        List<Entry> entries = app.files.list(ctx.principal(), path);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", self.path());
        out.put("self", entry(self));
        out.put("entries", entries.stream().map(FilesApi::entry).toList());
        out.put("folderExpiry", self.directory() ? folderExpiry(ctx, self.path()) : null);
        return Response.json(out);
    }

    private Response download(Ctx ctx) throws IOException {
        FileService.ReadTarget t = app.files.readTarget(ctx.principal(), ctx.requireQuery("path"));
        long size = t.info().size();
        String etag = "\"" + (t.info().etag() == null ? size + "-" + t.info().lastModified() : t.info().etag()) + "\"";
        HttpHeaders h = Response.newHeaders();
        h.set("ETag", etag);
        h.set("Accept-Ranges", "bytes");
        h.set("Cache-Control", "private, no-cache");
        h.set("Content-Type", "application/octet-stream");
        h.set("Content-Disposition", disposition(t.path().name()));
        if (t.expiresAt() != null) {
            h.set("X-Fdrop-Expires-At", Instant.ofEpochMilli(t.expiresAt()).toString());
        }
        if (t.info().lastModified() != null) {
            h.set("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    t.info().lastModified().atZone(java.time.ZoneOffset.UTC)));
        }
        String inm = ctx.header("If-None-Match");
        if (inm != null && (inm.equals(etag) || inm.equals("*"))) {
            return new Response.Empty(304, h);
        }
        long start = 0;
        long end = size - 1;
        int status = 200;
        String range = ctx.header("Range");
        String ifRange = ctx.header("If-Range");
        if (range != null && !range.contains(",") && (ifRange == null || ifRange.equals(etag))) {
            Matcher m = RANGE.matcher(range.trim());
            if (m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty())) {
                if (m.group(1).isEmpty()) {
                    long suffix = Long.parseLong(m.group(2));
                    start = Math.max(0, size - suffix);
                } else {
                    start = Long.parseLong(m.group(1));
                    end = m.group(2).isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(m.group(2)));
                }
                if (start >= size || start > end) {
                    h.set("Content-Range", "bytes */" + size);
                    return new Response.Empty(416, h);
                }
                status = 206;
                h.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
        }
        long length = size == 0 ? 0 : end - start + 1;
        ReadableByteChannel ch = app.files.openRead(t, start, status == 206 ? length : -1);
        FileChannel fc = status == 200 && ch instanceof FileChannel f ? f : null;
        Audit.log("download", "user", ctx.principal().username(), "ip", ctx.clientIp(), "protocol", "HTTP",
                "path", t.path().virtual(), "bytes", length);
        return new Response.Stream(status, length, ch, fc, start, "http", h);
    }

    static String disposition(String name) {
        String ascii = name.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "_").replace("\\", "_");
        String enc = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + enc;
    }

    private Response simpleUpload(Ctx ctx) throws IOException {
        String path = ctx.requireQuery("path");
        long length = ctx.contentLength();
        long max = app.config.http.simpleUploadMax.bytes();
        if (length > max) {
            throw FdropException.tooLarge("simple uploads are limited to " + max
                    + " bytes; use the resumable upload API for larger files");
        }
        try (FileService.FileWrite w = app.files.openWrite(ctx.principal(), path, length, ctx.queryBool("overwrite"),
                Via.HTTP, ctx.clientIp(), expiryInput(ctx, null))) {
            ReadableByteChannel body = ctx.bodyChannel();
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            long total = 0;
            while (total < length) {
                buf.clear();
                if (length - total < buf.capacity()) {
                    buf.limit((int) (length - total));
                }
                int n = body.read(buf);
                if (n < 0) {
                    break;
                }
                buf.flip();
                while (buf.hasRemaining()) {
                    w.write(buf);
                }
                total += n;
            }
            if (total != length) {
                w.abort();
                throw FdropException.badRequest("body ended after " + total + " of " + length + " bytes");
            }
            w.commit();
            app.metrics.transfer("http", "upload", total);
            return Response.json(201, entry(app.files.stat(ctx.principal(), w.path().virtual())));
        }
    }

    private Response mkdir(Ctx ctx) {
        JsonNode b = ctx.jsonTree();
        return Response.json(201, entry(app.files.mkdir(ctx.principal(), text(b, "path"))));
    }

    private Response move(Ctx ctx) {
        JsonNode b = ctx.jsonTree();
        FileService.Moved moved = app.files.moveWithExpiry(ctx.principal(), text(b, "from"), text(b, "to"));
        Map<String, Object> m = entry(moved.entry());
        m.put("expiryShortened", moved.expiryShortened());
        return Response.json(m);
    }

    /** {@code PATCH /api/v1/files?path=…}: {@code expiresIn} (a duration from now, or never) or {@code expiresAt}. */
    private Response changeExpiry(Ctx ctx) {
        String path = ctx.requireQuery("path");
        JsonNode b = ctx.jsonTree();
        ExpiryService.Change change;
        if (b.has("expiresAt")) {
            if (b.has("expiresIn")) {
                throw FdropException.badRequest("give either expiresIn or expiresAt, not both")
                        .withType(Expiry.PROBLEM_CONFLICTING);
            }
            JsonNode at = b.get("expiresAt");
            if (at.isNull()) {
                change = ExpiryService.Change.clear();
            } else {
                try {
                    change = new ExpiryService.Change(null, Instant.parse(at.asText()));
                } catch (java.time.format.DateTimeParseException e) {
                    throw FdropException.badRequest("expiresAt must be an RFC 3339 time such as"
                            + " 2026-11-30T17:00:00Z").withType(Expiry.PROBLEM_INVALID);
                }
            }
        } else if (b.has("expiresIn")) {
            change = b.get("expiresIn").isNull() ? ExpiryService.Change.clear()
                    : new ExpiryService.Change(b.get("expiresIn").asText(), null);
        } else {
            throw FdropException.badRequest("give expiresIn or expiresAt");
        }
        app.expiry.change(ctx.principal(), path, change, Via.HTTP, "user");
        return Response.json(entry(app.files.stat(ctx.principal(), path)));
    }

    private Response delete(Ctx ctx) {
        app.files.delete(ctx.principal(), ctx.requireQuery("path"), ctx.queryBool("recursive"));
        return Response.empty(204);
    }

    static String text(JsonNode b, String field) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw FdropException.badRequest("'" + field + "' is required");
        }
        return v.asText();
    }

    // ---- resumable uploads ----

    private static Map<String, Object> session(UploadSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("path", s.virtualPath());
        m.put("size", s.size());
        m.put("chunkSize", s.chunkSize());
        m.put("chunkCount", s.chunkCount());
        m.put("state", s.state().name());
        m.put("expiresAt", Instant.ofEpochMilli(s.expiresAt()).toString());
        m.put("fileExpiresIn", s.expiresIn());
        if (s.error() != null) {
            m.put("error", s.error());
        }
        return m;
    }

    private Response createUpload(Ctx ctx) {
        JsonNode b = ctx.jsonTree();
        if (!b.hasNonNull("size") || !b.get("size").canConvertToLong()) {
            throw FdropException.badRequest("'size' is required");
        }
        UploadService.CreateRequest req = new UploadService.CreateRequest(text(b, "path"), b.get("size").asLong(),
                b.hasNonNull("lastModified") ? b.get("lastModified").asLong() : null,
                b.hasNonNull("sha256") ? b.get("sha256").asText() : null,
                b.path("overwrite").asBoolean(false),
                expiryInput(ctx, b.hasNonNull("expiresIn") ? b.get("expiresIn").asText() : null));
        UploadSession s = app.uploadService.create(ctx.principal(), req, ctx.clientIp());
        HttpHeaders h = Response.newHeaders();
        h.set("Location", "/api/v1/uploads/" + s.id());
        return new Response.Json(201, session(s), h);
    }

    private Response uploadStatus(Ctx ctx) {
        UploadService.Status st = app.uploadService.status(ctx.principal(), ctx.param("id"));
        Map<String, Object> m = session(st.session());
        m.put("received", st.received());
        m.put("bytesReceived", st.bytesReceived());
        return Response.json(m);
    }

    private Response putChunk(Ctx ctx) {
        int index;
        try {
            index = Integer.parseInt(ctx.param("index"));
        } catch (NumberFormatException e) {
            throw FdropException.badRequest("chunk index must be a number");
        }
        UploadService.ChunkResult r = app.uploadService.putChunk(ctx.principal(), ctx.param("id"), index,
                ctx.bodyChannel(), ctx.contentLength(), ctx.header("X-Chunk-SHA256"));
        HttpHeaders h = Response.newHeaders();
        h.set("X-Chunk-Result", r.name().toLowerCase());
        return new Response.Empty(204, h);
    }

    private Response complete(Ctx ctx) {
        String sha = null;
        if (!ctx.bodyText().isBlank()) {
            JsonNode b = ctx.jsonTree();
            sha = b.hasNonNull("sha256") ? b.get("sha256").asText() : null;
        }
        UploadSession s = app.uploadService.complete(ctx.principal(), ctx.param("id"), ctx.clientIp(), sha);
        Map<String, Object> m = session(s);
        if (s.state() == io.fdrop.core.model.Enums.UploadState.COMPLETED) {
            app.files.statIfExists(ctx.principal(), s.virtualPath()).ifPresent(e -> m.put("file", entry(e)));
            return Response.json(m);
        }
        return Response.json(202, m);
    }

    private Response abort(Ctx ctx) {
        app.uploadService.abort(ctx.principal(), ctx.param("id"));
        return Response.empty(204);
    }
}
