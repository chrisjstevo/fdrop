package io.fdrop.http;

import io.fdrop.core.Fdrop;
import io.fdrop.core.storage.StoragePath;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The management port (default 8081): {@code /metrics} for Prometheus, {@code /healthz} for liveness and
 * {@code /readyz} for readiness. Its own small listener, so busy user traffic cannot starve scrapes. Must never be
 * published to the internet.
 */
public final class ManagementServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ManagementServer.class);

    private final Fdrop app;
    private final ScheduledExecutorService checks = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("readiness").daemon().factory());
    private volatile boolean keycloakUp;
    private EventLoopGroup group;
    private Channel channel;

    public ManagementServer(Fdrop app) {
        this.app = app;
    }

    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public ManagementServer start() throws InterruptedException {
        checks.scheduleWithFixedDelay(() -> keycloakUp = app.oidc.reachable(), 0, 30, TimeUnit.SECONDS);
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        ServerBootstrap b = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(8192), new Handler());
                    }
                });
        channel = b.bind(app.config.management.bind, app.config.management.port).sync().channel();
        log.info("management listening on {}:{}", app.config.management.bind, port());
        return this;
    }

    private final class Handler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            String path = new QueryStringDecoder(req.uri()).path();
            boolean keepAlive = HttpUtil.isKeepAlive(req);
            String accept = req.headers().get("Accept");
            String authz = req.headers().get("Authorization");
            Thread.ofVirtual().start(() -> {
                FullHttpResponse resp = switch (path) {
                    case "/metrics" -> metrics(accept, authz);
                    case "/healthz" -> text(200, "ok\n", "text/plain; charset=utf-8");
                    case "/readyz" -> ready();
                    default -> text(404, "not found\n", "text/plain; charset=utf-8");
                };
                HttpUtil.setKeepAlive(resp, keepAlive);
                var f = ctx.writeAndFlush(resp);
                if (!keepAlive) {
                    f.addListener(ChannelFutureListener.CLOSE);
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private FullHttpResponse metrics(String accept, String authz) {
        String token = app.config.management.metricsToken;
        if (token != null && !token.isBlank()) {
            String expected = "Bearer " + token;
            if (authz == null || !MessageDigest.isEqual(authz.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8))) {
                FullHttpResponse r = text(401, "unauthorized\n", "text/plain; charset=utf-8");
                r.headers().set("WWW-Authenticate", "Bearer realm=\"fdrop-metrics\"");
                return r;
            }
        }
        boolean openMetrics = accept != null && accept.contains("application/openmetrics-text");
        String type = openMetrics ? "application/openmetrics-text; version=1.0.0; charset=utf-8"
                : "text/plain; version=0.0.4; charset=utf-8";
        return text(200, app.metrics.scrape(accept), type);
    }

    private FullHttpResponse ready() {
        boolean db = app.db.ping();
        boolean storage;
        try {
            app.storage.stat(StoragePath.ROOT);
            storage = true;
        } catch (Exception e) {
            storage = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", db && storage ? (keycloakUp ? "ready" : "degraded") : "not ready");
        body.put("db", db ? "up" : "down");
        body.put("storage", storage ? "up" : "down");
        body.put("keycloak", keycloakUp ? "up" : "unreachable");
        return text(db && storage ? 200 : 503, new String(Json.bytes(body), StandardCharsets.UTF_8) + "\n",
                "application/json");
    }

    private static FullHttpResponse text(int status, String body, String type) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                Unpooled.wrappedBuffer(bytes));
        r.headers().set("Content-Type", type);
        r.headers().setInt("Content-Length", bytes.length);
        r.headers().set("Cache-Control", "no-store");
        return r;
    }

    @Override
    public void close() {
        checks.shutdownNow();
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        if (group != null) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
