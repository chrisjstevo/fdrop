package io.fdrop.http.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.Fdrop;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.auth.TwoFactorService;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.WentTo;
import io.fdrop.core.model.Records.SshKey;
import io.fdrop.core.model.Records.UploadRecord;
import io.fdrop.core.model.Records.User;
import io.fdrop.core.service.AccountService;
import io.fdrop.http.Ctx;
import io.fdrop.http.Response;
import io.fdrop.http.Router;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The signed-in user: {@code /api/v1/me}, SSH keys, My uploads, user search, assigned destinations. */
public final class AccountApi {

    private final Fdrop app;

    public AccountApi(Fdrop app) {
        this.app = app;
    }

    public void register(Router r) {
        r.get("/api/v1/me", this::me);
        r.get("/api/v1/me/ssh-keys", ctx -> Response.json(app.accounts.keys(ctx.principal()).stream()
                .map(AccountApi::key).toList()));
        r.post("/api/v1/me/ssh-keys", this::addKey);
        r.delete("/api/v1/me/ssh-keys/{id}", ctx -> {
            app.accounts.deleteKey(ctx.principal(), ctx.param("id"));
            return Response.empty(204);
        });
        r.delete("/api/v1/me/two-factor/{id}", ctx -> {
            app.twoFactor.removeOwn(ctx.principal(), ctx.twoFactor(), ctx.param("id"));
            return Response.empty(204);
        });
        r.get("/api/v1/me/uploads", this::uploads);
        r.get("/api/v1/users", ctx -> Response.json(app.accounts.searchUsers(ctx.principal(), ctx.query("search"))
                .stream().filter(u -> !u.sub().equals(ctx.principal().sub()))
                .map(u -> Map.of("username", u.username(), "kind", u.kind().name().toLowerCase(Locale.ROOT)))
                .toList()));
        r.get("/api/v1/forward-destinations", ctx -> Response.json(app.destinationService.forUser(ctx.principal())));
    }

    private Response me(Ctx ctx) {
        Principal p = ctx.principal();
        User u = app.accounts.me(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sub", p.sub());
        m.put("username", p.username());
        m.put("email", p.email());
        m.put("kind", p.app() ? "application" : "user");
        m.put("roles", Map.of("standard", p.user(), "admin", p.admin()));
        m.put("quotaBytes", u.quotaBytes());
        m.put("usedBytes", u.usedBytes());
        m.put("reservedBytes", app.accounts.reserved(p));
        if (ctx.session() != null) {
            m.put("csrfToken", ctx.session().csrf());
        }
        m.put("twoFactor", twoFactor(ctx));
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("simpleUploadMax", app.config.http.simpleUploadMax.bytes());
        settings.put("maxFileSize", app.config.limits.maxFileSize.bytes());
        settings.put("maxParallelChunks", app.config.upload.maxParallelChunks);
        settings.put("clientHashMax", app.config.upload.clientHashMax.bytes());
        settings.put("userManagement", app.config.auth.userManagement.enabled);
        settings.put("forwarding", app.hostPolicy.enabled() && app.destinationService.secrets() != null);
        settings.put("sftpPort", app.config.sftp.enabled ? app.config.sftp.port : null);
        m.put("settings", settings);
        return Response.json(m);
    }

    private Map<String, Object> twoFactor(Ctx ctx) {
        Principal p = ctx.principal();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policy", app.twoFactor.policy().name().toLowerCase(Locale.ROOT));
        m.put("required", app.twoFactor.policyRequires(p));
        m.put("thisSession", ctx.twoFactor());
        m.put("sftpCodes", app.twoFactor.config().sftpPasswordWithCode);
        try {
            m.put("factors", app.twoFactor.factors(p.sub()).stream().map(f -> {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("id", f.id());
                fm.put("kind", f.kind());
                fm.put("label", f.label());
                fm.put("createdAt", f.createdAt() > 0 ? Instant.ofEpochMilli(f.createdAt()).toString() : null);
                fm.put("remaining", f.remaining());
                return fm;
            }).toList());
        } catch (FdropException e) {
            // Keycloak's admin API is unreachable: the page still loads, without the list
            m.put("factors", null);
        }
        return m;
    }

    static Map<String, Object> key(SshKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.id());
        m.put("name", k.name());
        m.put("type", k.keyType());
        m.put("fingerprint", k.fingerprint());
        m.put("createdAt", Instant.ofEpochMilli(k.createdAt()).toString());
        m.put("lastUsedAt", k.lastUsedAt() == null ? null : Instant.ofEpochMilli(k.lastUsedAt()).toString());
        return m;
    }

    private Response addKey(Ctx ctx) {
        if (!ctx.twoFactor() && app.twoFactor.needed(ctx.principal())) {
            throw TwoFactorService.required("sign in with your second factor before adding an SSH key");
        }
        JsonNode b = ctx.jsonTree();
        SshKey k = app.accounts.addKey(ctx.principal(), b.hasNonNull("name") ? b.get("name").asText() : null,
                FilesApi.text(b, "publicKey"));
        return Response.json(201, key(k));
    }

    private Response uploads(Ctx ctx) {
        String wentTo = ctx.query("wentTo");
        WentTo w = null;
        boolean foldersOnly = false;
        if ("forward".equalsIgnoreCase(wentTo)) {
            w = WentTo.FORWARD;
        } else if ("folders".equalsIgnoreCase(wentTo)) {
            foldersOnly = true;
        }
        int limit = ctx.queryLong("limit") == null ? 50 : (int) Math.min(100, Math.max(1, ctx.queryLong("limit")));
        RecordRepo.Filter f = new RecordRepo.Filter(w, foldersOnly, ctx.query("q"), time(ctx.query("from"), false),
                time(ctx.query("to"), true), ctx.query("sort"), ctx.query("cursor"), limit);
        AccountService.HistoryPage page = app.accounts.history(ctx.principal(), f);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalCount", page.totalCount());
        out.put("totalBytes", page.totalBytes());
        out.put("nextCursor", page.nextCursor());
        out.put("rows", page.rows().stream().map(AccountApi::row).toList());
        return Response.json(out);
    }

    /** Accepts epoch millis, an ISO instant, or a date (whole day, in UTC). */
    static Long time(String v, boolean endOfDay) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignored) {
            // not millis
        }
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // not an instant
        }
        try {
            LocalDate d = LocalDate.parse(v);
            return (endOfDay ? d.plusDays(1) : d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw FdropException.badRequest("dates are ISO-8601 (2026-10-22) or epoch milliseconds");
        }
    }

    static Map<String, Object> row(AccountService.HistoryRow h) {
        UploadRecord r = h.record();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("fileName", r.fileName());
        m.put("size", r.size());
        m.put("wentTo", r.wentTo().name());
        m.put("folderName", h.folderName());
        m.put("destinationName", h.destinationName());
        m.put("pathAtUpload", r.pathAtUpload());
        m.put("currentPath", h.reachable() ? r.currentPath() : null);
        m.put("status", r.status().name());
        m.put("via", r.via().name());
        m.put("clientIp", r.clientIp());
        m.put("startIp", r.startIp() != null && !r.startIp().equals(r.clientIp()) ? r.startIp() : null);
        m.put("completedAt", Instant.ofEpochMilli(r.completedAt()).toString());
        m.put("canDownload", h.reachable() && (r.status().name().equals("STORED") || r.status().name().equals("MOVED")
                || r.wentTo() == WentTo.FORWARD));
        m.put("canRetry", h.canRetry());
        m.put("jobId", h.jobId());
        m.put("folderId", r.folderId());
        return m;
    }
}
