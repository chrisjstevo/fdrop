package io.fdrop.http.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.Fdrop;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.FolderType;
import io.fdrop.core.model.Enums.Permission;
import io.fdrop.core.model.Records.ForwardJob;
import io.fdrop.core.service.FolderService;
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
