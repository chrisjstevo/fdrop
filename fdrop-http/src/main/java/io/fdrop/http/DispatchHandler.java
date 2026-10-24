package io.fdrop.http;

import io.fdrop.core.Fdrop;
import io.fdrop.core.db.Db;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.util.TrustedProxies;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-connection dispatcher. Upload routes stream their body to the handler through a {@link BodyChannel}; every
 * other route's body is aggregated up to 1 MiB. Handlers run on virtual threads, never on the event loop, so
 * blocking storage and DB calls are fine there. Pipelined requests wait their turn.
 */
final class DispatchHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DispatchHandler.class);
    private static final int MAX_AGGREGATED = 1024 * 1024;

    private final Fdrop app;
    private final Router router;
    private final Authenticator auth;
    private final ResponseWriter writer;
    private final TrustedProxies proxies;
    private final StaticFiles statics;
    private final boolean mtlsListener;

    private final ArrayDeque<Object> backlog = new ArrayDeque<>();
    private boolean inFlight;
    /** True from a request's headers until its last body chunk. */
    private boolean reading;
    private HttpRequest request;
    private Router.Match match;
    private CompositeByteBuf aggregate;
    private BodyChannel body;
    private boolean tooLarge;
    private long startNanos;

    DispatchHandler(Fdrop app, Router router, Authenticator auth, ResponseWriter writer, TrustedProxies proxies,
                    StaticFiles statics, boolean mtlsListener) {
        this.mtlsListener = mtlsListener;
        this.app = app;
        this.router = router;
        this.auth = auth;
        this.writer = writer;
        this.proxies = proxies;
        this.statics = statics;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (inFlight && !reading) {
            backlog.add(msg);
            return;
        }
        handle(ctx, msg);
    }

    private void handle(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof HttpRequest req) {
                startRequest(ctx, req);
            }
            if (msg instanceof HttpContent c) {
                content(ctx, c);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void startRequest(ChannelHandlerContext ctx, HttpRequest req) {
        inFlight = true;
        reading = true;
        request = req;
        startNanos = System.nanoTime();
        tooLarge = false;
        if (req.decoderResult().isFailure()) {
            respondNow(ctx, Problems.of(FdropException.badRequest("malformed request")), "unmatched", true);
            return;
        }
        QueryStringDecoder q = new QueryStringDecoder(req.uri());
        // the application listener serves the API only: no browser login, no UI
        match = mtlsListener && !q.rawPath().startsWith("/api/v1/") ? null
                : router.match(req.method().name(), q.rawPath());
        if (match != null && match.route().streaming()) {
            body = new BodyChannel(ctx.channel());
            Ctx c = context(ctx, req, q, null);
            Router.Match m = match;
            Thread.ofVirtual().name("http-stream").start(() -> run(ctx, c, m));
        } else {
            long declared = HttpUtil.getContentLength(req, -1L);
            if (declared > MAX_AGGREGATED) {
                tooLarge = true;
            }
            aggregate = ctx.alloc().compositeBuffer(64);
        }
    }

    private void content(ChannelHandlerContext ctx, HttpContent c) {
        if (body != null) {
            body.offer(c.content().retain());
            if (c instanceof LastHttpContent) {
                reading = false;
                body.end();
                // keep reading off until the response is out, so the next request waits its turn
                ctx.channel().config().setAutoRead(false);
            }
            return;
        }
        if (aggregate == null) {
            if (c instanceof LastHttpContent) {
                reading = false;
            }
            return;
        }
        if (!tooLarge) {
            if (aggregate.readableBytes() + c.content().readableBytes() > MAX_AGGREGATED) {
                tooLarge = true;
                aggregate.release();
                aggregate = ctx.alloc().compositeBuffer(1);
            } else {
                aggregate.addComponent(true, c.content().retain());
            }
        }
        if (c instanceof LastHttpContent) {
            reading = false;
            ByteBuf full = aggregate;
            aggregate = null;
            ctx.channel().config().setAutoRead(false);
            if (tooLarge) {
                full.release();
                respondNow(ctx, Problems.of(FdropException.tooLarge("request body larger than 1 MiB")),
                        match == null ? "unmatched" : match.route().template(), false);
                return;
            }
            HttpRequest req = request;
            Router.Match m = match;
            QueryStringDecoder q = new QueryStringDecoder(req.uri());
            Ctx cx = context(ctx, req, q, full);
            Thread.ofVirtual().name("http").start(() -> {
                try {
                    if (m == null && mtlsListener) {
                        finish(ctx, Problems.of(FdropException.notFound("no such API route")), "unmatched", false);
                    } else if (m == null) {
                        finish(ctx, statics.serve(cx, router.pathExists(q.rawPath())), "static", false);
                    } else {
                        run(ctx, cx, m);
                    }
                } finally {
                    full.release();
                }
            });
        }
    }

    private Ctx context(ChannelHandlerContext ctx, HttpRequest req, QueryStringDecoder q, ByteBuf full) {
        InetSocketAddress peer = (InetSocketAddress) ctx.channel().remoteAddress();
        String forwarded = req.headers().get("Forwarded");
        String xff = req.headers().get("X-Forwarded-For");
        String ip = proxies.clientIp(peer.getAddress(), forwarded, xff);
        Map<String, String> params = match == null ? new LinkedHashMap<>() : match.params();
        return new Ctx(req, q, params, ctx.channel(), ip, full, body, connection(ctx, peer));
    }

    private Ctx.Connection connection(ChannelHandlerContext ctx, InetSocketAddress peer) {
        SslHandler ssl = ctx.pipeline().get(SslHandler.class);
        List<X509Certificate> certs = new ArrayList<>();
        if (ssl != null) {
            try {
                for (Certificate c : ssl.engine().getSession().getPeerCertificates()) {
                    if (c instanceof X509Certificate x) {
                        certs.add(x);
                    }
                }
            } catch (SSLPeerUnverifiedException e) {
                // no client certificate
            }
        }
        return new Ctx.Connection(ssl != null, mtlsListener, proxies.isTrusted(peer.getAddress()), List.copyOf(certs));
    }

    private void run(ChannelHandlerContext ctx, Ctx c, Router.Match m) {
        Response r;
        try {
            if (m.route().access() == Router.Access.USER) {
                auth.authenticate(c, m.route().csrf());
                if (m.route().template().startsWith("/api/v1/admin/")) {
                    auth.checkAdminTwoFactor(c);
                }
            }
            r = m.route().handler().handle(c);
        } catch (FdropException e) {
            r = Problems.of(e);
        } catch (Db.DbException e) {
            log.error("database error on {} {}", c.method(), m.route().template(), e);
            r = Problems.of(new FdropException(500, "Internal error", "database error"));
        } catch (UncheckedIOException | IOException e) {
            log.warn("I/O error on {} {}: {}", c.method(), m.route().template(), e.toString());
            r = Problems.of(new FdropException(500, "Internal error", "storage error: " + e.getMessage()));
        } catch (Exception e) {
            log.error("unhandled error on {} {}", c.method(), m.route().template(), e);
            r = Problems.of(new FdropException(500, "Internal error", "unexpected error"));
        }
        boolean close = false;
        if (body != null) {
            // a body the handler did not read to the end cannot be skipped on a kept-alive connection
            close = !body.fullyRead() && !body.complete();
            if (!body.fullyRead() && body.complete()) {
                drain(body);
            }
        }
        finish(ctx, r, m.route().template(), close);
    }

    private static void drain(BodyChannel b) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(64 * 1024);
        try {
            while (b.read(buf) >= 0) {
                buf.clear();
            }
        } catch (IOException ignored) {
            // nothing left to read
        }
    }

    private void respondNow(ChannelHandlerContext ctx, Response r, String route, boolean close) {
        ctx.channel().config().setAutoRead(false);
        Thread.ofVirtual().start(() -> finish(ctx, r, route, close));
    }

    private void finish(ChannelHandlerContext ctx, Response r, String route, boolean close) {
        boolean keepAlive = request != null && HttpUtil.isKeepAlive(request) && !close;
        String method = request == null ? "GET" : request.method().name();
        writer.write(ctx.channel(), r, !keepAlive).addListener(f -> {
            app.metrics.httpRequest(method, route, r.status(), System.nanoTime() - startNanos);
            if (!keepAlive) {
                ctx.close();
                return;
            }
            ctx.executor().execute(() -> {
                if (body != null) {
                    body.close();
                    body = null;
                }
                inFlight = false;
                reading = false;
                request = null;
                match = null;
                ctx.channel().config().setAutoRead(true);
                while (!inFlight && !backlog.isEmpty()) {
                    handle(ctx, backlog.poll());
                }
                ctx.read();
            });
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (body != null) {
            body.fail(new IOException("client disconnected"));
        }
        if (aggregate != null) {
            aggregate.release();
            aggregate = null;
        }
        backlog.forEach(ReferenceCountUtil::release);
        backlog.clear();
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause.getCause() instanceof javax.net.ssl.SSLException) {
            // failed handshakes, e.g. no client certificate on the mTLS listener, are the client's problem
            log.debug("TLS handshake failed: {}", cause.getCause().toString());
        } else if (!(cause instanceof IOException)) {
            log.warn("HTTP pipeline error: {}", cause.toString());
        }
        ctx.close();
    }
}
