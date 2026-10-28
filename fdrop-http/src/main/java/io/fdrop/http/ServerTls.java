package io.fdrop.http;

import io.fdrop.core.config.FdropConfig;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The TLS of every fdrop listener: the web and API port, the application mTLS port and the management port all use
 * {@code http.tls.cert} and {@code http.tls.key}, TLS 1.3 or 1.2, and nothing listens on plain HTTP (see "Security" in
 * SPEC.md). The files are checked every minute and reloaded when they change, so a renewed certificate needs no
 * restart.
 */
public final class ServerTls implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ServerTls.class);

    private final File cert;
    private final File key;
    private final ScheduledExecutorService reloader = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("tls-reload").daemon().factory());
    private volatile SslContext server;
    private volatile SslContext mtls;
    private long certModified;
    private long keyModified;

    /** Loads the certificate; fdrop does not start without one, since it serves HTTPS only. */
    public ServerTls(FdropConfig.Tls cfg) {
        if (cfg.cert == null || cfg.cert.isBlank() || cfg.key == null || cfg.key.isBlank()) {
            throw new IllegalStateException("http.tls.cert and http.tls.key are required: fdrop serves HTTPS only");
        }
        this.cert = new File(cfg.cert);
        this.key = new File(cfg.key);
        if (!cert.canRead() || !key.canRead()) {
            throw new IllegalStateException("cannot read the TLS certificate " + cert + " or its key " + key
                    + " (http.tls.cert, http.tls.key): fdrop serves HTTPS only");
        }
        try {
            reload();
        } catch (Exception e) {
            throw new IllegalStateException("cannot load the TLS certificate " + cert + ": " + e.getMessage(), e);
        }
        reloader.scheduleWithFixedDelay(this::reloadQuietly, 60, 60, TimeUnit.SECONDS);
    }

    /** For the web and management ports. */
    public SslContext server() {
        return server;
    }

    /** For the application port: a client certificate is required, and judged per request (see below). */
    public SslContext mtls() {
        return mtls;
    }

    private void reloadQuietly() {
        try {
            reload();
        } catch (Exception e) {
            log.warn("TLS reload failed, keeping the previous certificate: {}", e.toString());
        }
    }

    private synchronized void reload() throws Exception {
        if (server != null && cert.lastModified() == certModified && key.lastModified() == keyModified) {
            return;
        }
        server = SslContextBuilder.forServer(cert, key).protocols("TLSv1.3", "TLSv1.2").build();
        mtls = SslContextBuilder.forServer(cert, key).protocols("TLSv1.3", "TLSv1.2")
                .clientAuth(ClientAuth.REQUIRE).trustManager(new AnyClientCertificate()).build();
        certModified = cert.lastModified();
        keyModified = key.lastModified();
        log.info("loaded TLS certificate {}", cert);
    }

    @Override
    public void close() {
        reloader.shutdownNow();
    }

    /**
     * Completes the handshake for any client certificate the client proves it holds the key for; which ones are
     * accepted is decided per request against the registered certificates. No CA list is sent, because clients
     * such as Java's then refuse to offer a pinned self-signed certificate.
     */
    private static final class AnyClientCertificate implements X509TrustManager {
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
}
