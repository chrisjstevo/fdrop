package io.fdrop.http;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;

/** What a route handler returns; {@link ResponseWriter} turns it into Netty messages. */
public sealed interface Response {

    int status();

    HttpHeaders headers();

    /** A JSON body. */
    record Json(int status, Object body, HttpHeaders headers) implements Response {
    }

    /** No body. */
    record Empty(int status, HttpHeaders headers) implements Response {
    }

    /** Raw bytes, e.g. a static asset or the metrics text. */
    record Bytes(int status, byte[] body, String contentType, HttpHeaders headers) implements Response {
    }

    /**
     * A streamed body of known length, read on the handler's virtual thread. {@code fileChannel} is set when the
     * bytes come straight from a local file, allowing a zero-copy send.
     */
    record Stream(int status, long length, ReadableByteChannel body, FileChannel fileChannel, long fileOffset,
                  String protocolForMetrics, HttpHeaders headers) implements Response {
    }

    static HttpHeaders newHeaders() {
        return new DefaultHttpHeaders();
    }

    static Json json(Object body) {
        return new Json(200, body, newHeaders());
    }

    static Json json(int status, Object body) {
        return new Json(status, body, newHeaders());
    }

    static Empty empty(int status) {
        return new Empty(status, newHeaders());
    }

    static Empty redirect(String location) {
        HttpHeaders h = newHeaders();
        h.set("Location", location);
        h.set("Cache-Control", "no-store");
        return new Empty(302, h);
    }
}
