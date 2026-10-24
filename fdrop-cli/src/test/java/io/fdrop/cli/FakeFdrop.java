package io.fdrop.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.fdrop.core.TestCerts;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scripted stand-in for fdrop's upload API over HTTPS, requiring a client certificate. Tests queue faults for
 * the next requests (a dropped connection, a status code) and read back what the client sent.
 */
final class FakeFdrop implements AutoCloseable {

    /** What to do instead of answering normally. */
    sealed interface Fault permits Drop, Status {
    }

    /** Read part of the body, then close the connection without a response. */
    record Drop() implements Fault {
    }

    record Status(int code, String detail) implements Fault {
    }

    static final class Session {
        final String id;
        final String path;
        final long size;
        final String sha256;
        final Map<Integer, byte[]> chunks = new ConcurrentHashMap<>();
        volatile String state = "OPEN";

        Session(String id, String path, long size, String sha256) {
            this.id = id;
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }

        byte[] assembled() {
            byte[] out = new byte[(int) size];
            int pos = 0;
            for (byte[] c : new TreeMap<>(chunks).values()) {
                System.arraycopy(c, 0, out, pos, c.length);
                pos += c.length;
            }
            return out;
        }
    }

    final TestCerts.Issued ca = TestCerts.ca("Fake fdrop CA");
    final int chunkSize;
    final Map<String, Session> sessions = new ConcurrentHashMap<>();
    final Deque<Fault> createFaults = new ArrayDeque<>();
    final Deque<Fault> chunkFaults = new ArrayDeque<>();
    /** Chunks answered normally before {@link #chunkFaultsAfter} applies to every later one. */
    volatile int okChunksBeforeFailing = Integer.MAX_VALUE;
    volatile Fault chunkFaultsAfter;
    volatile boolean assembleLater;
    volatile boolean assemblyFails;
    final AtomicInteger chunkPuts = new AtomicInteger();
    final AtomicInteger statusPolls = new AtomicInteger();
    final AtomicInteger deletes = new AtomicInteger();
    final List<String> userAgents = new CopyOnWriteArrayList<>();
    final List<Boolean> clientCertSeen = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final HttpsServer server;

    FakeFdrop(int chunkSize) throws Exception {
        this.chunkSize = chunkSize;
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        SSLContext ssl = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(TestCerts.server(ca).keyStore(new char[0]), new char[0]);
        ssl.init(kmf.getKeyManagers(), new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl) {
            @Override
            public void configure(HttpsParameters params) {
                // HttpsServer ignores setNeedClientAuth unless the whole SSLParameters are given
                SSLParameters p = getSSLContext().getDefaultSSLParameters();
                p.setNeedClientAuth(true);
                params.setSSLParameters(p);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "https://localhost:" + server.getAddress().getPort();
    }

    /** A session as if an earlier run had created it and sent some chunks. */
    Session preCreate(String path, byte[] data, String sha256, int... chunksReceived) {
        Session s = new Session("upl_" + ids.incrementAndGet(), path, data.length, sha256);
        for (int i : chunksReceived) {
            int from = i * chunkSize;
            s.chunks.put(i, java.util.Arrays.copyOfRange(data, from, Math.min(data.length, from + chunkSize)));
        }
        sessions.put(s.id, s);
        return s;
    }

    Session only() {
        return sessions.values().stream().filter(s -> s.state.equals("COMPLETED")).findFirst().orElseThrow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            try {
                clientCertSeen.add(((HttpsExchange) ex).getSSLSession().getPeerCertificates().length > 0);
            } catch (SSLPeerUnverifiedException e) {
                clientCertSeen.add(false);
            }
            userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
            String path = ex.getRequestURI().getPath();
            String m = ex.getRequestMethod();
            String[] p = path.split("/");
            if (m.equals("GET") && path.equals("/api/v1/me")) {
                send(ex, 200, Api.JSON.createObjectNode().put("username", "app-test").put("kind", "application"));
            } else if (m.equals("POST") && path.equals("/api/v1/uploads")) {
                create(ex);
            } else if (p.length == 5 && path.startsWith("/api/v1/uploads/") && m.equals("GET")) {
                statusPolls.incrementAndGet();
                Session s = sessions.get(p[4]);
                if (s == null) {
                    problem(ex, 404, "no such upload");
                } else {
                    if (s.state.equals("ASSEMBLING")) {
                        s.state = assemblyFails ? "FAILED" : "COMPLETED";
                    }
                    send(ex, 200, status(s));
                }
            } else if (p.length == 5 && m.equals("DELETE")) {
                deletes.incrementAndGet();
                sessions.remove(p[4]);
                ex.sendResponseHeaders(204, -1);
            } else if (p.length == 7 && p[5].equals("chunks") && m.equals("PUT")) {
                chunk(ex, sessions.get(p[4]), Integer.parseInt(p[6]));
            } else if (p.length == 6 && p[5].equals("complete") && m.equals("POST")) {
                complete(ex, sessions.get(p[4]));
            } else {
                problem(ex, 404, "no route " + m + " " + path);
            }
        }
    }

    private void create(HttpExchange ex) throws IOException {
        JsonNode b = Api.JSON.readTree(ex.getRequestBody());
        Fault f;
        synchronized (createFaults) {
            f = createFaults.poll();
        }
        if (f != null && fault(ex, f)) {
            return;
        }
        Session s = new Session("upl_" + ids.incrementAndGet(), b.path("path").asText(), b.path("size").asLong(),
                b.path("sha256").asText());
        sessions.put(s.id, s);
        send(ex, 201, status(s));
    }

    private void chunk(HttpExchange ex, Session s, int index) throws IOException {
        int n = chunkPuts.incrementAndGet();
        Fault f;
        synchronized (chunkFaults) {
            f = chunkFaults.poll();
        }
        if (f == null && n > okChunksBeforeFailing) {
            f = chunkFaultsAfter;
        }
        if (f != null && fault(ex, f)) {
            return;
        }
        if (s == null) {
            problem(ex, 404, "no such upload");
            return;
        }
        byte[] body = ex.getRequestBody().readAllBytes();
        if (!sha(body).equals(ex.getRequestHeaders().getFirst("X-Chunk-SHA256"))) {
            problem(ex, 400, "chunk " + index + " does not match X-Chunk-SHA256");
            return;
        }
        s.chunks.put(index, body);
        ex.sendResponseHeaders(204, -1);
    }

    private void complete(HttpExchange ex, Session s) throws IOException {
        ex.getRequestBody().readAllBytes();
        if (s == null) {
            problem(ex, 404, "no such upload");
            return;
        }
        int count = (int) ((s.size + chunkSize - 1) / chunkSize);
        if (s.chunks.size() < count) {
            problem(ex, 409, "chunks still missing");
            return;
        }
        if (!sha(s.assembled()).equals(s.sha256)) {
            problem(ex, 400, "whole-file SHA-256 does not match");
            return;
        }
        if (assembleLater) {
            s.state = "ASSEMBLING";
            send(ex, 202, status(s));
            return;
        }
        s.state = "COMPLETED";
        send(ex, 200, status(s));
    }

    /** True when the fault answered the request. */
    private boolean fault(HttpExchange ex, Fault f) throws IOException {
        if (f instanceof Drop) {
            InputStream in = ex.getRequestBody();
            in.readNBytes(Math.min(100, chunkSize / 2));
            // close the socket without answering: the client sees the connection die mid-request
            throw new IOException("dropped by test");
        }
        Status st = (Status) f;
        problem(ex, st.code(), st.detail());
        return true;
    }

    private ObjectNode status(Session s) {
        ObjectNode n = Api.JSON.createObjectNode();
        n.put("id", s.id);
        n.put("path", s.path);
        n.put("size", s.size);
        n.put("chunkSize", chunkSize);
        n.put("chunkCount", (int) ((s.size + chunkSize - 1) / chunkSize));
        n.put("state", s.state);
        ArrayNode ranges = n.putArray("received");
        for (int i : new TreeMap<>(s.chunks).keySet()) {
            ranges.addArray().add(i).add(i);
        }
        if (s.state.equals("FAILED")) {
            n.put("error", "disk full");
        }
        return n;
    }

    private static void problem(HttpExchange ex, int status, String detail) throws IOException {
        try {
            ex.getRequestBody().readAllBytes();
        } catch (IOException e) {
            // already read (and closed by Jackson)
        }
        ex.getResponseHeaders().set("Content-Type", "application/problem+json");
        send(ex, status, Api.JSON.createObjectNode().put("title", "Fake").put("status", status).put("detail", detail));
    }

    private static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    static String sha(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
