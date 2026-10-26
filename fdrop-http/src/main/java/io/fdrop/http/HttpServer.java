package io.fdrop.http;

import io.fdrop.core.Fdrop;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.util.TrustedProxies;
import io.fdrop.http.api.AccountApi;
import io.fdrop.http.api.AdminApi;
import io.fdrop.http.api.FilesApi;
import io.fdrop.http.api.FoldersApi;
import io.fdrop.http.api.SearchApi;
import io.micrometer.core.instrument.binder.netty4.NettyAllocatorMetrics;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The user-facing HTTP server: REST API under {@code /api/v1}, browser login under {@code /auth}, and the React UI
 * at {@code /}. Event loops only parse frames; every handler runs on a virtual thread.
 */
public final class HttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    private final Fdrop app;
    private final SessionStore sessions;
    private final Router router = new Router();
    private final ResponseWriter writer;
    private final Authenticator auth;
    private final TrustedProxies proxies;
    private final StaticFiles statics;
    private final ScheduledExecutorService tlsReloader = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("tls-reload").daemon().factory());
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel channel;
    private Channel mtlsChannel;
    private volatile SslContext ssl;
    private volatile SslContext mtlsSsl;
    private long certModified;
    private long keyModified;

    public HttpServer(Fdrop app) {
        this.app = app;
        FdropConfig.Session s = app.config.auth.session;
        this.sessions = new SessionStore(s.idleDuration(), s.absoluteDuration());
        this.writer = new ResponseWriter(app.metrics);
        this.auth = new Authenticator(app, sessions);
        this.proxies = new TrustedProxies(app.config.http.trustedProxies);
        this.statics = new StaticFiles(app.config.http.tls.enabled);
        new AuthRoutes(app, sessions).register(router);
        new AccountApi(app).register(router);
        new FilesApi(app).register(router);
        new FoldersApi(app).register(router);
        new AdminApi(app).register(router);
        new SearchApi(app).register(router);
        app.userAdmin.addTerminator(sub -> sessions.removeWhere(sub, null));
    }

    public SessionStore sessions() {
        return sessions;
    }

    public Router router() {
        return router;
    }

    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /** The application mTLS listener's port, or -1 when it is off. */
    public int mtlsPort() {
        return mtlsChannel == null ? -1 : ((InetSocketAddress) mtlsChannel.localAddress()).getPort();
    }

    public HttpServer start() throws Exception {
        FdropConfig.Http cfg = app.config.http;
        if (cfg.tls.enabled || cfg.mtls.enabled) {
            reloadTls();
            tlsReloader.scheduleWithFixedDelay(this::reloadTlsQuietly, 60, 60, TimeUnit.SECONDS);
        }
        boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workers = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        new NettyAllocatorMetrics(PooledByteBufAllocator.DEFAULT).bindTo(app.metrics.registry());
        channel = bootstrap(false).bind(cfg.bind, cfg.port).sync().channel();
        log.info("HTTP{} listening on {}:{}", cfg.tls.enabled ? "S" : "", cfg.bind, port());
        if (cfg.mtls.enabled) {
            mtlsChannel = bootstrap(true).bind(cfg.mtls.bind, cfg.mtls.port).sync().channel();
            log.info("HTTPS for application client certificates listening on {}:{}", cfg.mtls.bind, mtlsPort());
        }
        return this;
    }

    /**
     * The main listener, or the application listener: always TLS, client certificate required, API only. Its
     * handshake accepts any client certificate; {@code AppAccountService} then decides whether a pinned
     * fingerprint or a trusted CA and subject match, so an unknown certificate gets the same 401 as a wrong one.
     */
    private ServerBootstrap bootstrap(boolean mtls) {
        return new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        SslContext current = mtls ? mtlsSsl : ssl;
                        if (current != null) {
                            ch.pipeline().addLast("tls", current.newHandler(ch.alloc()));
                        }
                        ch.pipeline().addLast("count", new ConnectionCounter());
                        ch.pipeline().addLast("codec", new HttpServerCodec(4096, 16 * 1024, 64 * 1024));
                        ch.pipeline().addLast("keepalive", new HttpServerKeepAliveHandler());
                        ch.pipeline().addLast("continue", new HttpServerExpectContinueHandler());
                        ch.pipeline().addLast("idle", new IdleStateHandler(0, 0, 120));
                        ch.pipeline().addLast("dispatch", new DispatchHandler(app, router, auth, writer, proxies,
                                statics, mtls));
                    }
                });
    }

    private void reloadTlsQuietly() {
        try {
            reloadTls();
        } catch (Exception e) {
            log.warn("TLS reload failed, keeping the previous certificate: {}", e.toString());
        }
    }

    private synchronized void reloadTls() throws Exception {
        File cert = new File(app.config.http.tls.cert);
        File key = new File(app.config.http.tls.key);
        if (ssl != null && cert.lastModified() == certModified && key.lastModified() == keyModified) {
            return;
        }
        if (app.config.http.tls.enabled) {
            ssl = SslContextBuilder.forServer(cert, key).protocols("TLSv1.3", "TLSv1.2").build();
        }
        if (app.config.http.mtls.enabled) {
            mtlsSsl = SslContextBuilder.forServer(cert, key).protocols("TLSv1.3", "TLSv1.2")
                    .clientAuth(ClientAuth.REQUIRE).trustManager(new AnyClientCertificate()).build();
        }
        certModified = cert.lastModified();
        keyModified = key.lastModified();
        log.info("loaded TLS certificate {}", cert);
    }

    /**
     * Completes the handshake for any client certificate the client proves it holds the key for; which ones are
     * accepted is decided per request against the registered certificates. No CA list is sent, because clients
     * such as Java's then refuse to offer a pinned self-signed certificate.
     */
    private final class AnyClientCertificate implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("a client certificate is required");
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("not a client");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** Keeps {@code fdrop_http_connections_active} up to date. */
    private final class ConnectionCounter extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            app.metrics.httpConnections().incrementAndGet();
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            app.metrics.httpConnections().decrementAndGet();
            ctx.fireChannelInactive();
        }
    }

    /** Stops accepting, then waits up to {@code grace} for in-flight requests. */
    public void stop(Duration grace) {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        if (mtlsChannel != null) {
            mtlsChannel.close().syncUninterruptibly();
        }
        tlsReloader.shutdownNow();
        if (workers != null) {
            workers.shutdownGracefully(0, grace.toMillis(), TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Override
    public void close() {
        stop(Duration.ofSeconds(30));
    }
}
