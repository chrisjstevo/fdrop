package io.fdrop.http.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.Fdrop;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Enums.RetrievalState;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.model.Records.RemoteRetrieval;
import io.fdrop.core.service.FolderService;
import io.fdrop.core.service.RemoteBrowseService;
import io.fdrop.http.Json;
import io.fdrop.http.Ctx;
import io.fdrop.http.Response;
import io.fdrop.http.Router;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code /api/v1/folders}: shared and forward folders. */
public final class FoldersApi {

    private final Fdrop app;

    public FoldersApi(Fdrop app) {
        this.app = app;
    }

    public void register(Router r) {
        r.get("/api/v1/folders", ctx -> Response.json(app.folderService.list(ctx.principal())));
        r.post("/api/v1/folders", this::create);
        r.get("/api/v1/folders/{id}", ctx -> Response.json(app.folderService.get(ctx.principal(), ctx.param("id"))));
        r.patch("/api/v1/folders/{id}", this::update);
        r.delete("/api/v1/folders/{id}", ctx -> {
            app.folderService.delete(ctx.principal(), ctx.param("id"));
            return Response.empty(204);
        });
        r.put("/api/v1/folders/{id}/members/{username}", this::putMember);
        r.delete("/api/v1/folders/{id}/members/{username}", ctx -> {
            app.folderService.removeMember(ctx.principal(), ctx.param("id"), ctx.param("username"));
            return Response.empty(204);
        });
        r.post("/api/v1/folders/{id}/owner", ctx -> Response.json(app.folderService.transferOwnership(
                ctx.principal(), ctx.param("id"), FilesApi.text(ctx.jsonTree(), "username"))));
        r.get("/api/v1/folders/{id}/forward-jobs", ctx -> Response.json(app.folderService.jobs(ctx.principal(),
                ctx.param("id"), 500).stream().map(this::job).toList()));
        r.post("/api/v1/folders/{id}/forward-jobs/{jobId}/retry", ctx -> {
            app.folderService.retry(ctx.principal(), ctx.param("id"), ctx.param("jobId"));
            return Response.empty(204);
        });
        r.post("/api/v1/folders/{id}/forward-jobs/{jobId}/cancel", ctx -> {
            JsonNode b = ctx.jsonTree();
            app.forwarding.cancel(ctx.principal(), ctx.param("id"), ctx.param("jobId"),
                    b.hasNonNull("reason") ? b.get("reason").asText() : null);
            return Response.empty(204);
        });
        r.get("/api/v1/folders/{id}/destinations/{destId}/remote", ctx -> Response.json(listing(app.browse.list(
                ctx.principal(), ctx.param("id"), ctx.param("destId"), ctx.query("path"), ctx.queryBool("refresh")))));
        r.post("/api/v1/folders/{id}/destinations/{destId}/retrievals", ctx -> {
            RemoteRetrieval rr = app.browse.retrieve(ctx.principal(), ctx.param("id"), ctx.param("destId"),
                    FilesApi.text(ctx.jsonTree(), "path"));
            return Response.json(rr.state() == RetrievalState.READY ? 200 : 202, retrieval(rr));
        });
        r.get("/api/v1/folders/{id}/retrievals/{retrievalId}", ctx -> Response.json(retrieval(app.browse.retrieval(
                ctx.principal(), ctx.param("id"), ctx.param("retrievalId")))));
        r.get("/api/v1/folders/{id}/retrievals/{retrievalId}/content", this::retrievedContent);
    }

    /** A destination tab's listing of the remote directory. */
    private static Map<String, Object> listing(RemoteBrowseService.Listing l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("destinationId", l.destinationId());
        m.put("destinationName", l.destinationName());
        m.put("remoteDir", l.remoteDir());
        m.put("path", l.path());
        m.put("listedAt", Instant.ofEpochMilli(l.listedAt()).toString());
        m.put("truncated", l.truncated());
        m.put("maxEntries", l.maxEntries());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (RemoteBrowseService.EntryView e : l.entries()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("name", e.name());
            em.put("path", e.path());
            em.put("type", e.directory() ? "folder" : "file");
            em.put("size", e.directory() ? null : e.size());
            em.put("modifiedAt", e.modifiedAt() == 0 ? null : Instant.ofEpochMilli(e.modifiedAt()).toString());
            em.put("cameFrom", e.cameFrom() == null ? null : e.cameFrom().name());
            em.put("retrieval", e.retrieval() == null ? null : retrieval(e.retrieval()));
            entries.add(em);
        }
        m.put("entries", entries);
        return m;
    }

    /** A copy of a remote file in fdrop, or on its way. */
    static Map<String, Object> retrieval(RemoteRetrieval r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("destinationId", r.destinationId());
        m.put("path", r.remotePath());
        m.put("state", r.state().name());
        m.put("size", r.remoteSize());
        m.put("bytesDone", r.state() == RetrievalState.READY ? r.remoteSize() : r.bytesDone());
        m.put("error", r.error());
        m.put("readyAt", r.readyAt() == null ? null : Instant.ofEpochMilli(r.readyAt()).toString());
        m.put("expiresAt", r.expiresAt() == null ? null : Instant.ofEpochMilli(r.expiresAt()).toString());
        return m;
    }

    private Response retrievedContent(Ctx ctx) throws java.io.IOException {
        RemoteBrowseService.Content c = app.browse.content(ctx.principal(), ctx.param("id"), ctx.param("retrievalId"));
        long size = c.info().size();
        String etag = "\"" + c.retrieval().id() + "-" + size + "\"";
        io.netty.handler.codec.http.HttpHeaders h = Response.newHeaders();
        h.set("Content-Disposition", FilesApi.disposition(c.name()));
        return FilesApi.serve(ctx, h, size, etag, c.info().lastModified(),
                (offset, length) -> app.browse.openRead(c, offset, length),
                length -> io.fdrop.core.audit.Audit.log("download", "user", ctx.principal().username(), "ip",
                        ctx.clientIp(), "protocol", "HTTP", "path", "retrieved:/" + c.retrieval().remotePath(),
                        "destination", c.retrieval().destinationId(), "bytes", length));
    }

    /** One job: one file to one destination. Jobs of the same file share {@code uploadRecordId}. */
    Map<String, Object> job(ForwardJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.id());
        m.put("uploadRecordId", j.uploadRecordId());
        m.put("destinationId", j.destinationId());
        m.put("destinationName", app.forwarding.destinationName(j.destinationId()));
        m.put("fileName", j.fileName());
        m.put("size", j.size());
        m.put("state", j.state().name());
        m.put("attempts", j.attempts());
        m.put("lastError", j.lastError());
        m.put("createdAt", Instant.ofEpochMilli(j.createdAt()).toString());
        m.put("sentAt", j.sentAt() == null ? null : Instant.ofEpochMilli(j.sentAt()).toString());
        m.put("nextAttemptAt", j.nextAttemptAt() == 0 ? null : Instant.ofEpochMilli(j.nextAttemptAt()).toString());
        m.put("cancelReason", j.cancelReason());
        return m;
    }

    private Response create(Ctx ctx) {
        JsonNode b = ctx.jsonTree();
        FolderType type;
        try {
            type = FolderType.valueOf(FilesApi.text(b, "type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw FdropException.badRequest("type must be 'shared' or 'forward'");
        }
        return Response.json(201, app.folderService.create(ctx.principal(), type, FilesApi.text(b, "name"),
                destinationIds(b), b.hasNonNull("keepDays") ? b.get("keepDays").asInt() : null));
    }

    private Response update(Ctx ctx) {
        JsonNode b = ctx.jsonTree();
        String defaultExpiry = setting(b, "defaultExpiry");
        String maxExpiry = setting(b, "maxExpiry");
        int shortened = 0;
        if (defaultExpiry != null || maxExpiry != null) {
            shortened = app.folderService.updateExpiry(ctx.principal(), ctx.param("id"), defaultExpiry, maxExpiry,
                    b.path("applyToExisting").asBoolean(false));
        }
        FolderService.FolderView v = app.folderService.update(ctx.principal(), ctx.param("id"),
                b.hasNonNull("name") ? b.get("name").asText() : null, destinationIds(b),
                b.path("sendExisting").asBoolean(false), b.hasNonNull("keepDays") ? b.get("keepDays").asInt() : null);
        Map<String, Object> out = Json.MAPPER.convertValue(v, LinkedHashMap.class);
        out.put("expiryFilesChanged", shortened);
        return Response.json(out);
    }

    /**
     * A forward folder's destinations: {@code destinationIds} (an array), or the single {@code destinationId} older
     * clients send. Null when neither is given.
     */
    private static List<String> destinationIds(JsonNode b) {
        if (b.has("destinationIds") && !b.get("destinationIds").isNull()) {
            JsonNode ids = b.get("destinationIds");
            if (!ids.isArray()) {
                throw FdropException.badRequest("destinationIds must be an array of destination ids");
            }
            List<String> out = new ArrayList<>();
            ids.forEach(n -> out.add(n.isTextual() ? n.asText() : null));
            return out;
        }
        return b.hasNonNull("destinationId") ? List.of(b.get("destinationId").asText()) : null;
    }

    /** An expiry setting in a PATCH: absent means unchanged, null or blank means cleared. */
    private static String setting(JsonNode b, String field) {
        if (!b.has(field)) {
            return null;
        }
        return b.get(field).isNull() ? "" : b.get(field).asText();
    }

    private Response putMember(Ctx ctx) {
        JsonNode b = ctx.jsonTree();
        Permission p;
        try {
            p = Permission.valueOf(b.path("permission").asText("WRITE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw FdropException.badRequest("permission must be READ or WRITE");
        }
        return Response.json(app.folderService.putMember(ctx.principal(), ctx.param("id"), ctx.param("username"), p));
    }
}
