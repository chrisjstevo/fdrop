package io.fdrop.http;

import io.fdrop.core.metrics.FdropMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Writes a {@link Response} from a handler's virtual thread. Streams wait for the channel to drain whenever it
 * stops being writable, so a slow client throttles reading from storage instead of filling memory.
 */
final class ResponseWriter {

    private static final int CHUNK = 256 * 1024;

    private final FdropMetrics metrics;

    ResponseWriter(FdropMetrics metrics) {
        this.metrics = metrics;
    }

    /** Writes the response and returns the future of its last write. */
    ChannelFuture write(Channel ch, Response r, boolean close) {
        HttpResponseStatus status = HttpResponseStatus.valueOf(r.status());
        HttpHeaders h = r.headers();
        h.set("X-Content-Type-Options", "nosniff");
        if (close) {
            h.set(HttpHeaderNames.CONNECTION, "close");
        }
        return switch (r) {
            case Response.Json j -> full(ch, status, h, Json.bytes(j.body()), "application/json");
            case Response.Bytes b -> full(ch, status, h, b.body(), b.contentType());
            case Response.Empty e -> full(ch, status, h, new byte[0], null);
            case Response.Stream s -> stream(ch, status, h, s);
        };
    }

    private ChannelFuture full(Channel ch, HttpResponseStatus status, HttpHeaders h, byte[] body, String type) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(body));
        resp.headers().set(h);
        if (type != null && !resp.headers().contains(HttpHeaderNames.CONTENT_TYPE)) {
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
        }
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return ch.writeAndFlush(resp);
    }

    private ChannelFuture stream(Channel ch, HttpResponseStatus status, HttpHeaders h, Response.Stream s) {
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        head.headers().set(h);
        head.headers().set(HttpHeaderNames.CONTENT_LENGTH, s.length());
        ch.write(head);
        long sent = 0;
        try (var body = s.body()) {
            boolean zeroCopy = s.fileChannel() != null && ch.pipeline().get(SslHandler.class) == null;
            if (zeroCopy) {
                ch.write(new DefaultFileRegion(s.fileChannel(), s.fileOffset(), s.length()));
                sent = s.length();
                ChannelFuture last = ch.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                // the region owns the file channel now; keep it open until written
                last.syncUninterruptibly();
                return last;
            }
            ChannelFuture last = null;
            while (sent < s.length()) {
                ByteBuf buf = ch.alloc().buffer((int) Math.min(CHUNK, s.length() - sent));
                ByteBuffer nio = buf.nioBuffer(0, buf.capacity());
                int n;
                try {
                    n = body.read(nio);
                } catch (IOException e) {
                    buf.release();
                    throw e;
                }
                if (n < 0) {
                    buf.release();
                    break;
                }
                buf.writerIndex(n);
                sent += n;
                last = ch.writeAndFlush(new DefaultHttpContent(buf));
                if (!ch.isWritable()) {
                    last.syncUninterruptibly();
                }
                if (!ch.isActive()) {
                    break;
                }
            }
            if (sent < s.length()) {
                // the source ended early: the client must not take a short body as complete
                ch.close();
                return ch.newSucceededFuture();
            }
            return ch.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } catch (IOException | RuntimeException e) {
            ch.close();
            return ch.newSucceededFuture();
        } finally {
            if (s.protocolForMetrics() != null) {
                metrics.transfer(s.protocolForMetrics(), "download", sent);
            }
        }
    }
}
