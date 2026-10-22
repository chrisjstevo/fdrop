package io.fdrop.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Route table. Templates use {@code {name}} for one path segment. The template, not the raw URL, labels the
 * {@code fdrop_http_requests_seconds} metric, so the number of time series stays bounded.
 */
public final class Router {

    /** How a route is reached. */
    public enum Access {
        /** No sign-in needed. */
        PUBLIC,
        /** A browser session or a bearer token. */
        USER
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Ctx ctx) throws Exception;
    }

    /**
     * @param streaming the body is streamed to the handler instead of aggregated (uploads)
     * @param csrf      cookie-authenticated calls must carry the CSRF header
     */
    public record Route(String method, String template, String[] parts, Access access, boolean streaming,
                        boolean csrf, Handler handler) {
    }

    public record Match(Route route, Map<String, String> params) {
    }

    private final List<Route> routes = new ArrayList<>();

    public Router get(String template, Handler h) {
        return add("GET", template, Access.USER, false, h);
    }

    public Router post(String template, Handler h) {
        return add("POST", template, Access.USER, false, h);
    }

    public Router put(String template, Handler h) {
        return add("PUT", template, Access.USER, false, h);
    }

    public Router patch(String template, Handler h) {
        return add("PATCH", template, Access.USER, false, h);
    }

    public Router delete(String template, Handler h) {
        return add("DELETE", template, Access.USER, false, h);
    }

    public Router putStreaming(String template, Handler h) {
        return add("PUT", template, Access.USER, true, h);
    }

    public Router publicGet(String template, Handler h) {
        return add("GET", template, Access.PUBLIC, false, h);
    }

    public Router publicPost(String template, Handler h) {
        routes.add(new Route("POST", template, split(template), Access.PUBLIC, false, false, h));
        return this;
    }

    private Router add(String method, String template, Access access, boolean streaming, Handler h) {
        boolean csrf = !method.equals("GET") && access == Access.USER;
        routes.add(new Route(method, template, split(template), access, streaming, csrf, h));
        return this;
    }

    private static String[] split(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p.isEmpty() ? new String[0] : p.split("/", -1);
    }

    /** The route for a method and path, or null. A path that matches another method gives a 405 elsewhere. */
    public Match match(String method, String path) {
        String[] segs = split(path);
        for (Route r : routes) {
            if (!r.method().equals(method)) {
                continue;
            }
            Map<String, String> params = matchParts(r.parts(), segs);
            if (params != null) {
                return new Match(r, params);
            }
        }
        return null;
    }

    public boolean pathExists(String path) {
        String[] segs = split(path);
        return routes.stream().anyMatch(r -> matchParts(r.parts(), segs) != null);
    }

    private static Map<String, String> matchParts(String[] parts, String[] segs) {
        if (parts.length != segs.length) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.startsWith("{") && p.endsWith("}")) {
                if (segs[i].isEmpty()) {
                    return null;
                }
                params.put(p.substring(1, p.length() - 1),
                        java.net.URLDecoder.decode(segs[i].replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8));
            } else if (!p.equals(segs[i])) {
                return null;
            }
        }
        return params;
    }
}
