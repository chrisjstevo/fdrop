package io.fdrop.http;

import io.fdrop.core.error.FdropException;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.Map;

/** RFC 9457 problem JSON. */
final class Problems {

    private Problems() {
    }

    static Response of(FdropException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", e.type() == null ? "about:blank" : e.type());
        body.put("title", e.title());
        body.put("status", e.status());
        if (e.getMessage() != null) {
            body.put("detail", e.getMessage());
        }
        HttpHeaders h = Response.newHeaders();
        h.set("Content-Type", "application/problem+json");
        h.set("Cache-Control", "no-store");
        if (e.status() == 401) {
            h.set("WWW-Authenticate", "Bearer realm=\"fdrop\"");
        }
        return new Response.Json(e.status(), body, h);
    }
}
