package io.fdrop.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * fdrop's REST API over HTTPS with the client certificate. Every response is either JSON, a {@link CliException}
 * with the exit code the spec gives its status, or a {@link Retryable} for network errors and {@code 5xx}, which
 * the upload loop retries.
 */
class Api {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A failure worth retrying: the network, a timeout, or a {@code 5xx}. */
    static final class Retryable extends Exception {
        Retryable(String message) {
            super(message, null, false, false);
        }
    }

    /** A response that is neither a success nor one of the mapped failures; the caller decides. */
    record Reply(int status, JsonNode body) {
        String detail() {
            return Api.detail(body, status);
        }
    }

    private final HttpClient http;
    private final URI base;
    private final String userAgent;

    Api(URI base, SSLContext tls, String userAgent) {
        if (!"https".equalsIgnoreCase(base.getScheme())) {
            throw new CliException(CliException.USAGE, "--url must be an https:// URL, not " + base);
        }
        this.base = URI.create(base.toString().replaceAll("/+$", ""));
        this.userAgent = userAgent;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .sslContext(tls)
                .sslParameters(Tls.parameters())
                .build();
    }

    URI base() {
        return base;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");
    }

    JsonNode get(String path) throws Retryable {
        return expect(send(request(path).GET().build()), path);
    }

    /** A call whose non-2xx answers the caller handles itself, after 401 and 5xx are mapped. */
    Reply call(String method, String path, Object body) throws Retryable {
        HttpRequest.BodyPublisher p = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(write(body));
        HttpRequest.Builder b = request(path).method(method, p);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return send(b.build());
    }

    /** Sends one chunk, streamed from the file region, with its hash. */
    Reply putChunk(String uploadId, int index, Path file, long offset, long length, String sha256) throws Retryable {
        HttpRequest req = request("/api/v1/uploads/" + uploadId + "/chunks/" + index)
                .header("Content-Type", "application/octet-stream")
                .header("X-Chunk-SHA256", sha256)
                .PUT(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(
                        () -> region(file, offset, length)), length))
                .build();
        return send(req);
    }

    private Reply send(HttpRequest req) throws Retryable {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (SSLException e) {
            if (connectionLost(e)) {
                throw new Retryable("connection lost during TLS: " + e.getMessage());
            }
            throw new CliException(CliException.AUTH, "TLS handshake with " + base.getHost() + " failed: "
                    + e.getMessage() + " (is the server certificate trusted? is the client certificate accepted?)");
        } catch (IOException e) {
            throw new Retryable(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Retryable("interrupted");
        }
        JsonNode body = parse(resp.body());
        int s = resp.statusCode();
        if (s == 401) {
            throw new CliException(CliException.AUTH, "fdrop refused the client certificate: " + detail(body, s));
        }
        if (s >= 500 || s == 429) {
            throw new Retryable("server answered " + s + ": " + detail(body, s));
        }
        return new Reply(s, body);
    }

    /**
     * A TLS failure caused by the connection dropping (a restarting server or load balancer), as opposed to a
     * certificate the other side refused: only the first is worth retrying.
     */
    static boolean connectionLost(SSLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.io.EOFException || t instanceof java.net.SocketException) {
                return true;
            }
            String m = t.getMessage();
            if (m != null && (m.contains("Remote host terminated the handshake") || m.contains("Connection reset")
                    || m.contains("peer shut down incorrectly"))) {
                return true;
            }
        }
        return false;
    }

    /** Success, or the exit code for 403, 413 and anything else unexpected. */
    static JsonNode expect(Reply r, String what) {
        int s = r.status();
        if (s >= 200 && s < 300) {
            return r.body();
        }
        if (s == 403 || s == 413) {
            throw new CliException(CliException.NOT_ALLOWED, r.detail());
        }
        throw new CliException(CliException.SERVER, what + ": " + r.detail());
    }

    /** The problem JSON's title and detail, never the request. */
    static String detail(JsonNode body, int status) {
        if (body == null || !body.isObject()) {
            return "HTTP " + status;
        }
        String title = body.path("title").asText("");
        String detail = body.path("detail").asText("");
        return (title.isEmpty() ? "HTTP " + status : title) + (detail.isEmpty() ? "" : ": " + detail);
    }

    private static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return JSON.nullNode();
        }
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            return JSON.nullNode();
        }
    }

    private static String write(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An input stream over part of a file, read through a small buffer. */
    static InputStream region(Path file, long offset, long length) {
        try {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
            return new InputStream() {
                private final ByteBuffer buf = ByteBuffer.allocate(64 * 1024).flip();
                private long pos = offset;
                private final long end = offset + length;

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (!buf.hasRemaining()) {
                        if (pos >= end) {
                            return -1;
                        }
                        buf.clear().limit((int) Math.min(buf.capacity(), end - pos));
                        int n = ch.read(buf, pos);
                        if (n < 0) {
                            throw new IOException("file shrank while uploading");
                        }
                        pos += n;
                        buf.flip();
                    }
                    int n = Math.min(len, buf.remaining());
                    buf.get(b, off, n);
                    return n;
                }

                @Override
                public void close() throws IOException {
                    ch.close();
                }
            };
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
