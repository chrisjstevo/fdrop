package io.fdrop.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.error.FdropException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One request as a handler sees it. */
public final class Ctx {

    private final HttpRequest request;
    private final QueryStringDecoder query;
    private final Map<String, String> pathParams;
    private final Channel channel;
    private final String clientIp;
    private final ByteBuf body;
    private final BodyChannel bodyChannel;
    private final Connection connection;
    Principal principal;
    SessionStore.WebSession session;
    boolean twoFactor;

    /**
     * How the request arrived.
     *
     * @param tls           the connection itself is TLS
     * @param mtlsListener  it came in on the application mTLS listener
     * @param trustedPeer   the peer is one of {@code http.trustedProxies}
     * @param clientCerts   the client's certificate chain from the TLS handshake, leaf first; empty if none
     */
    record Connection(boolean tls, boolean mtlsListener, boolean trustedPeer,
                      List<java.security.cert.X509Certificate> clientCerts) {
        static final Connection PLAIN = new Connection(false, false, false, List.of());
    }

    Ctx(HttpRequest request, QueryStringDecoder query, Map<String, String> pathParams, Channel channel,
        String clientIp, ByteBuf body, BodyChannel bodyChannel) {
        this(request, query, pathParams, channel, clientIp, body, bodyChannel, Connection.PLAIN);
    }

    Ctx(HttpRequest request, QueryStringDecoder query, Map<String, String> pathParams, Channel channel,
        String clientIp, ByteBuf body, BodyChannel bodyChannel, Connection connection) {
        this.connection = connection;
        this.request = request;
        this.query = query;
        this.pathParams = pathParams;
        this.channel = channel;
        this.clientIp = clientIp;
        this.body = body;
        this.bodyChannel = bodyChannel;
    }

    public String method() {
        return request.method().name();
    }

    public String path() {
        return query.path();
    }

    public HttpHeaders headers() {
        return request.headers();
    }

    public String header(String name) {
        return request.headers().get(name);
    }

    public String clientIp() {
        return clientIp;
    }

    public Channel channel() {
        return channel;
    }

    Connection connection() {
        return connection;
    }

    public String param(String name) {
        String v = pathParams.get(name);
        if (v == null) {
            throw new IllegalStateException("no path parameter " + name);
        }
        return v;
    }

    public String query(String name) {
        List<String> v = query.parameters().get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    public String requireQuery(String name) {
        String v = query(name);
        if (v == null || v.isEmpty()) {
            throw FdropException.badRequest("query parameter '" + name + "' is required");
        }
        return v;
    }

    public boolean queryBool(String name) {
        String v = query(name);
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"));
    }

    public Long queryLong(String name) {
        String v = query(name);
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw FdropException.badRequest("'" + name + "' must be a number");
        }
    }

    public Optional<String> cookie(String name) {
        String header = request.headers().get("Cookie");
        if (header == null) {
            return Optional.empty();
        }
        Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(header);
        return cookies.stream().filter(c -> c.name().equals(name)).map(Cookie::value).findFirst();
    }

    /** The signed-in user. Only valid on routes that require authentication. */
    public Principal principal() {
        if (principal == null) {
            throw FdropException.unauthorized("not signed in");
        }
        return principal;
    }

    public Principal principalOrNull() {
        return principal;
    }

    public SessionStore.WebSession session() {
        return session;
    }

    /** The caller signed in with a second factor (or is a script's service account, which has none). */
    public boolean twoFactor() {
        return twoFactor;
    }

    /** The aggregated body as JSON (non-streaming routes only). */
    public <T> T json(Class<T> type) {
        if (body == null || !body.isReadable()) {
            throw FdropException.badRequest("a JSON body is required");
        }
        try {
            return Json.MAPPER.readValue((java.io.InputStream) new ByteBufInputStream(body.duplicate()), type);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw FdropException.badRequest("invalid JSON: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw FdropException.badRequest("invalid JSON: " + e.getMessage());
        }
    }

    public JsonNode jsonTree() {
        return json(JsonNode.class);
    }

    public String bodyText() {
        return body == null ? "" : body.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The streamed body (streaming routes only). */
    public ReadableByteChannel bodyChannel() {
        if (bodyChannel == null) {
            throw new IllegalStateException("not a streaming route");
        }
        return bodyChannel;
    }

    public long contentLength() {
        String v = request.headers().get("Content-Length");
        if (v == null) {
            throw new FdropException(411, "Length required", "Content-Length is required");
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw FdropException.badRequest("bad Content-Length");
        }
    }
}
