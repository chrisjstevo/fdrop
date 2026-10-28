package io.fdrop.http;

import io.fdrop.core.error.FdropException;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the React UI from {@code static/} on the classpath (the {@code fdrop-web} jar). Unknown non-API paths
 * fall back to {@code index.html} so client-side routes work on reload. Hashed assets are cached forever;
 * {@code index.html} never is.
 */
final class StaticFiles {

    static final String CSP = "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline';"
            + " font-src 'self'; connect-src 'self'; worker-src 'self' blob:; frame-ancestors 'none';"
            + " base-uri 'none'; form-action 'self'";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("webmanifest", "application/manifest+json"));

    private static final byte[] PLACEHOLDER = ("<!doctype html><meta charset=utf-8><title>fdrop</title>"
            + "<p>The fdrop UI is not built. Build fdrop-web (<code>./mvnw -pl fdrop-web package</code>)"
            + " or run <code>npm run dev</code>.</p>").getBytes(StandardCharsets.UTF_8);

    private record Asset(byte[] bytes, String type) {
    }

    private final Map<String, Optional<Asset>> cache = new ConcurrentHashMap<>();

    Response serve(Ctx ctx, boolean apiPathExists) {
        String path = ctx.path();
        if (path.startsWith("/api/") || path.startsWith("/auth/") || path.equals("/api") || path.equals("/auth")) {
            if (apiPathExists) {
                return Problems.of(new FdropException(405, "Method not allowed", ctx.method() + " is not allowed here"));
            }
            return Problems.of(FdropException.notFound("no such endpoint"));
        }
        if (!ctx.method().equals("GET") && !ctx.method().equals("HEAD")) {
            return Problems.of(new FdropException(405, "Method not allowed", "only GET is allowed here"));
        }
        String file = path.equals("/") ? "index.html" : path.substring(1);
        if (file.contains("..") || file.contains("\\")) {
            return Problems.of(FdropException.notFound("not found"));
        }
        Optional<Asset> asset = load(file);
        boolean isIndex = file.equals("index.html");
        if (asset.isEmpty()) {
            if (file.startsWith("assets/") || file.contains(".") && !file.endsWith(".html")) {
                return Problems.of(FdropException.notFound("not found"));
            }
            asset = load("index.html");
            isIndex = true;
        }
        HttpHeaders h = Response.newHeaders();
        h.set("Content-Security-Policy", CSP);
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "same-origin");
        h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        if (asset.isEmpty()) {
            h.set("Cache-Control", "no-cache");
            return new Response.Bytes(200, PLACEHOLDER, "text/html; charset=utf-8", h);
        }
        h.set("Cache-Control", isIndex ? "no-cache" : file.startsWith("assets/")
                ? "public, max-age=31536000, immutable" : "public, max-age=3600");
        return new Response.Bytes(200, asset.get().bytes(), asset.get().type(), h);
    }

    private Optional<Asset> load(String file) {
        return cache.computeIfAbsent(file, f -> {
            try (InputStream in = StaticFiles.class.getClassLoader().getResourceAsStream("static/" + f)) {
                if (in == null) {
                    return Optional.empty();
                }
                String ext = f.contains(".") ? f.substring(f.lastIndexOf('.') + 1) : "";
                return Optional.of(new Asset(in.readAllBytes(), TYPES.getOrDefault(ext, "application/octet-stream")));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }
}
