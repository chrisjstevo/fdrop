package io.fdrop.http.api;

import io.fdrop.core.Fdrop;
import io.fdrop.core.search.SearchService;
import io.fdrop.http.Ctx;
import io.fdrop.http.Response;
import io.fdrop.http.Router;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code /api/v1/search}: names and contents of the files the caller can open (see "Search" in SPEC.md). */
public final class SearchApi {

    private final Fdrop app;

    public SearchApi(Fdrop app) {
        this.app = app;
    }

    public void register(Router r) {
        r.get("/api/v1/search", this::search);
        r.get("/api/v1/search/suggest", ctx -> Response.json(app.search.suggest(ctx.principal(), ctx.query("q"))));
        r.get("/api/v1/admin/search", ctx -> Response.json(app.search.status(ctx.principal())));
        r.post("/api/v1/admin/search/rebuild", ctx -> {
            app.search.rebuild(ctx.principal());
            return Response.json(202, app.search.status(ctx.principal()));
        });
    }

    private Response search(Ctx ctx) {
        Long limit = ctx.queryLong("limit");
        SearchService.Result res = app.search.search(ctx.principal(), new SearchService.Query(ctx.query("q"),
                ctx.query("scope") == null ? "all" : ctx.query("scope"), ctx.query("path"), ctx.query("type"),
                AccountApi.time(ctx.query("modifiedFrom"), false), ctx.query("sort"), ctx.query("cursor"),
                limit == null ? 20 : limit.intValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", res.total());
        out.put("nextCursor", res.nextCursor());
        out.put("results", res.results().stream().map(SearchApi::hit).toList());
        return Response.json(out);
    }

    private static Map<String, Object> hit(SearchService.Hit h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", h.path());
        m.put("name", h.name());
        m.put("location", h.location());
        m.put("type", h.type());
        m.put("folder", h.folder());
        m.put("size", h.size());
        m.put("modified", h.modified() == 0 ? null : Instant.ofEpochMilli(h.modified()).toString());
        m.put("expiresAt", h.expiresAt() == null ? null : Instant.ofEpochMilli(h.expiresAt()).toString());
        m.put("snippets", h.snippets() == null ? List.of() : h.snippets());
        m.put("contents", h.contents());
        return m;
    }
}
