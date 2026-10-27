package io.fdrop.http.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fdrop.core.Fdrop;
import io.fdrop.core.audit.AuditIndex;
import io.fdrop.core.audit.AuditService;
import io.fdrop.core.audit.AuditStore;
import io.fdrop.http.Ctx;
import io.fdrop.http.Response;
import io.fdrop.http.Router;
import io.netty.handler.codec.http.HttpHeaders;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/v1/admin/audit}: the audit log for admins (see "Audit log" in SPEC.md). Like every admin route, the
 * dispatcher requires a two-factor session; the service checks the admin role.
 */
public final class AuditApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Fdrop app;

    public AuditApi(Fdrop app) {
        this.app = app;
    }

    public void register(Router r) {
        r.get("/api/v1/admin/audit", this::list);
        r.get("/api/v1/admin/audit/export", this::export);
        r.get("/api/v1/admin/audit/events", ctx -> Response.json(Map.of("events", app.audit.events(ctx.principal())
                .stream().map(e -> Map.of("event", e[0], "group", e[1], "count", e[2])).toList())));
        r.post("/api/v1/admin/audit/verify", ctx -> Response.json(Map.of("nodes",
                app.audit.verify(ctx.principal(), ctx.clientIp()).stream().map(AuditApi::check).toList())));
        r.get("/api/v1/admin/audit/index", ctx -> Response.json(index(app.audit.indexStatus(ctx.principal()))));
        r.post("/api/v1/admin/audit/index/rebuild", ctx -> {
            app.audit.rebuildIndex(ctx.principal(), ctx.clientIp());
            return Response.json(202, index(app.audit.indexStatus(ctx.principal())));
        });
        // after the fixed paths, so /export and /events are not taken for ids
        r.get("/api/v1/admin/audit/{id}", ctx -> Response.json(detail(app.audit.get(ctx.principal(),
                ctx.param("id")))));
    }

    static AuditService.Filter filter(Ctx ctx) {
        return new AuditService.Filter(ctx.query("q"), list(ctx.query("group")), list(ctx.query("event")),
                ctx.query("user"), ctx.query("ip"), list(ctx.query("outcome")),
                AccountApi.time(ctx.query("from"), false), AccountApi.time(ctx.query("to"), true));
    }

    /** Several values, comma separated: {@code group=Sign-in,Files}. */
    private static List<String> list(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return Arrays.stream(v.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private Response list(Ctx ctx) {
        Long limit = ctx.queryLong("limit");
        AuditService.Page p = app.audit.list(ctx.principal(), filter(ctx), ctx.query("cursor"),
                limit == null ? 50 : limit.intValue(), ctx.clientIp());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.total());
        out.put("indexedUpTo", p.indexedUpTo() == null ? null : Instant.ofEpochMilli(p.indexedUpTo()).toString());
        out.put("facets", p.facets());
        out.put("nextCursor", p.nextCursor());
        out.put("events", p.events().stream().map(AuditApi::row).toList());
        return Response.json(out);
    }

    private static Map<String, Object> row(AuditStore.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("seq", r.seq());
        m.put("ts", Instant.ofEpochMilli(r.ts()).toString());
        m.put("node", r.node());
        m.put("event", r.event());
        m.put("group", r.group());
        m.put("user", r.user());
        m.put("ip", r.ip());
        m.put("protocol", r.protocol());
        m.put("outcome", r.outcome());
        m.put("summary", r.summary());
        return m;
    }

    private static Map<String, Object> detail(AuditStore.Row r) {
        Map<String, Object> m = row(r);
        try {
            m.put("data", JSON.readTree(r.data()));
        } catch (Exception e) {
            m.put("data", r.data());
        }
        m.put("chain", Map.of("node", r.node(), "nodeSeq", r.nodeSeq(), "prevHash", r.prevHash(), "hash", r.hash()));
        return m;
    }

    private static Map<String, Object> check(AuditStore.ChainCheck c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", c.node());
        m.put("checked", c.checked());
        m.put("first", c.firstTs() == null ? null : Instant.ofEpochMilli(c.firstTs()).toString());
        m.put("last", c.lastTs() == null ? null : Instant.ofEpochMilli(c.lastTs()).toString());
        m.put("intact", c.intact());
        m.put("brokenAt", c.brokenAt());
        m.put("reason", c.reason());
        return m;
    }

    private static Map<String, Object> index(AuditIndex.Status s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", s.available());
        m.put("documents", s.documents());
        m.put("sizeBytes", s.sizeBytes());
        m.put("appliedSeq", s.appliedSeq());
        m.put("latestSeq", s.latestSeq());
        m.put("lagSeconds", s.lagSeconds());
        m.put("rebuilding", s.rebuilding());
        return m;
    }

    private Response export(Ctx ctx) {
        AuditService.Export e = app.audit.export(ctx.principal(), filter(ctx), ctx.query("format"), ctx.clientIp());
        HttpHeaders h = Response.newHeaders();
        h.set("Content-Type", e.contentType());
        h.set("Content-Disposition", "attachment; filename=\"" + e.filename() + "\"");
        h.set("Cache-Control", "no-store");
        h.set("X-Fdrop-Audit-Events", Long.toString(e.count()));
        return new Response.Stream(200, -1, e.body(), null, 0, null, h);
    }
}
