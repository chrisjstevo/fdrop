package io.fdrop.http.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.Fdrop;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Enums.AppMethod;
import io.fdrop.core.model.Enums.KeyState;
import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.service.AppAccountService;
import io.fdrop.core.service.DestinationService;
import io.fdrop.core.service.UserAdminService;
import io.fdrop.http.Ctx;
import io.fdrop.http.Response;
import io.fdrop.http.Router;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code /api/v1/admin}: users, application accounts and forward destinations. Every route requires the admin
 * role.
 */
public final class AdminApi {

    private final Fdrop app;

    public AdminApi(Fdrop app) {
        this.app = app;
    }

    public void register(Router r) {
        r.get("/api/v1/admin/users", ctx -> Response.json(app.userAdmin.list(ctx.principal(), ctx.query("search"),
                ctx.queryLong("first") == null ? 0 : ctx.queryLong("first").intValue(), 50)));
        r.post("/api/v1/admin/users", this::createUser);
        r.delete("/api/v1/admin/users/{id}", ctx -> {
            app.userAdmin.delete(ctx.principal(), ctx.param("id"));
            return Response.empty(204);
        });
        r.post("/api/v1/admin/users/{id}/two-factor/reset", ctx -> Response.json(Map.of("removed",
                app.userAdmin.resetTwoFactor(ctx.principal(), ctx.param("id")))));

        r.get("/api/v1/admin/app-accounts", ctx -> Response.json(app.appAccounts.list(ctx.principal())));
        r.post("/api/v1/admin/app-accounts", ctx -> {
            JsonNode b = ctx.jsonTree();
            return Response.json(201, app.appAccounts.create(ctx.principal(), new AppAccountService.CreateInput(
                    FilesApi.text(b, "username"), opt(b, "description"), opt(b, "responsible"), methods(b),
                    strings(b, "allowedSources"))));
        });
        r.get("/api/v1/admin/app-accounts/{id}", ctx -> Response.json(
                app.appAccounts.get(ctx.principal(), ctx.param("id"))));
        r.patch("/api/v1/admin/app-accounts/{id}", ctx -> {
            JsonNode b = ctx.jsonTree();
            return Response.json(app.appAccounts.update(ctx.principal(), ctx.param("id"),
                    new AppAccountService.UpdateInput(opt(b, "description"), opt(b, "responsible"), methods(b),
                            strings(b, "allowedSources"), b.hasNonNull("enabled") ? b.get("enabled").asBoolean()
                            : null)));
        });
        r.delete("/api/v1/admin/app-accounts/{id}", ctx -> {
            app.appAccounts.delete(ctx.principal(), ctx.param("id"));
            return Response.empty(204);
        });
        r.post("/api/v1/admin/app-accounts/{id}/password", ctx -> Response.json(
                app.appAccounts.newPassword(ctx.principal(), ctx.param("id"))));
        r.post("/api/v1/admin/app-accounts/{id}/certificates", ctx -> {
            JsonNode b = ctx.jsonTree();
            return Response.json(201, app.appAccounts.addCertificate(ctx.principal(), ctx.param("id"),
                    new AppAccountService.CertInput(opt(b, "pem"), opt(b, "subject"), opt(b, "san"))));
        });
        r.delete("/api/v1/admin/app-accounts/{id}/certificates/{certId}", ctx -> Response.json(
                app.appAccounts.retireCertificate(ctx.principal(), ctx.param("id"), ctx.param("certId"))));
        r.post("/api/v1/admin/app-accounts/{id}/ssh-keys", ctx -> {
            JsonNode b = ctx.jsonTree();
            return Response.json(201, app.appAccounts.addSshKey(ctx.principal(), ctx.param("id"), opt(b, "name"),
                    FilesApi.text(b, "publicKey"), app.ssh));
        });
        r.delete("/api/v1/admin/app-accounts/{id}/ssh-keys/{keyId}", ctx -> Response.json(
                app.appAccounts.deleteSshKey(ctx.principal(), ctx.param("id"), ctx.param("keyId"))));

        r.get("/api/v1/admin/forward-destinations", ctx -> Response.json(app.destinationService.list(ctx.principal())));
        r.post("/api/v1/admin/forward-destinations", ctx -> Response.json(201,
                app.destinationService.create(ctx.principal(), input(ctx.jsonTree()))));
        r.post("/api/v1/admin/forward-destinations/probe", this::probe);
        r.get("/api/v1/admin/forward-destinations/{id}", ctx -> Response.json(
                app.destinationService.get(ctx.principal(), ctx.param("id"))));
        r.patch("/api/v1/admin/forward-destinations/{id}", ctx -> Response.json(
                app.destinationService.update(ctx.principal(), ctx.param("id"), input(ctx.jsonTree()))));
        r.delete("/api/v1/admin/forward-destinations/{id}", ctx -> {
            app.destinationService.delete(ctx.principal(), ctx.param("id"));
            return Response.empty(204);
        });
        r.post("/api/v1/admin/forward-destinations/{id}/test", ctx -> Response.json(app.destinationService.test(
                ctx.principal(), ctx.param("id"), ctx.query("keyId"))));
        r.post("/api/v1/admin/forward-destinations/{id}/keys", ctx -> Response.json(201,
                app.destinationService.generateKey(ctx.principal(), ctx.param("id"))));
        r.put("/api/v1/admin/forward-destinations/{id}/keys/{keyId}", this::updateKey);
        r.put("/api/v1/admin/forward-destinations/{id}/assignments/{username}", ctx -> {
            app.destinationService.assign(ctx.principal(), ctx.param("id"), ctx.param("username"));
            return Response.empty(204);
        });
        r.delete("/api/v1/admin/forward-destinations/{id}/assignments/{username}", ctx -> {
            app.destinationService.unassign(ctx.principal(), ctx.param("id"), ctx.param("username"));
            return Response.empty(204);
        });
    }

    private Response createUser(Ctx ctx) {
        JsonNode b = ctx.jsonTree();
        UserAdminService.Created c = app.userAdmin.create(ctx.principal(), FilesApi.text(b, "username"),
                opt(b, "email"), opt(b, "firstName"), opt(b, "lastName"), b.path("admin").asBoolean(false));
        return Response.json(201, c);
    }

    private Response probe(Ctx ctx) {
        JsonNode b = ctx.jsonTree();
        int port = b.path("port").asInt(22);
        return Response.json(app.destinationService.probe(ctx.principal(), FilesApi.text(b, "host"), port));
    }

    private Response updateKey(Ctx ctx) {
        JsonNode b = ctx.jsonTree();
        if (b.has("certificate")) {
            app.destinationService.setKeyCertificate(ctx.principal(), ctx.param("id"), ctx.param("keyId"),
                    b.get("certificate").isNull() ? null : b.get("certificate").asText());
        }
        if (b.hasNonNull("state")) {
            KeyState s;
            try {
                s = KeyState.valueOf(b.get("state").asText().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw FdropException.badRequest("state must be ACTIVE, PENDING or RETIRED");
            }
            app.destinationService.setKeyState(ctx.principal(), ctx.param("id"), ctx.param("keyId"), s);
        }
        return Response.json(app.destinationService.get(ctx.principal(), ctx.param("id")));
    }

    static DestinationService.DestinationInput input(JsonNode b) {
        ServerAuth auth = null;
        if (b.hasNonNull("serverAuth")) {
            try {
                auth = ServerAuth.valueOf(b.get("serverAuth").asText().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw FdropException.badRequest("serverAuth must be HOST_KEY or HOST_CA");
            }
        }
        return new DestinationService.DestinationInput(opt(b, "name"), opt(b, "description"), opt(b, "host"),
                b.hasNonNull("port") ? b.get("port").asInt() : null, opt(b, "remoteDir"), opt(b, "username"), auth,
                opt(b, "hostKeySha256"), opt(b, "hostCaPublicKey"),
                b.hasNonNull("overwrite") ? b.get("overwrite").asBoolean() : null,
                b.hasNonNull("enabled") ? b.get("enabled").asBoolean() : null);
    }

    /** {@code allowedMethods}: a list of {@code MTLS}, {@code PASSWORD}, {@code PUBLICKEY}; null when absent. */
    static Set<AppMethod> methods(JsonNode b) {
        List<String> names = strings(b, "allowedMethods");
        if (names == null) {
            return null;
        }
        Set<AppMethod> out = EnumSet.noneOf(AppMethod.class);
        for (String n : names) {
            try {
                out.add(AppMethod.valueOf(n.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw FdropException.badRequest("allowedMethods are MTLS, PASSWORD and PUBLICKEY");
            }
        }
        return out;
    }

    /** A JSON array of strings, or null when the field is absent. */
    static List<String> strings(JsonNode b, String field) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isArray()) {
            throw FdropException.badRequest(field + " must be a list");
        }
        List<String> out = new ArrayList<>();
        v.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String opt(JsonNode b, String field) {
        JsonNode v = b.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
