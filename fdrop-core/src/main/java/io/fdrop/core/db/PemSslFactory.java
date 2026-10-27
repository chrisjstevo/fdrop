package io.fdrop.core.db;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;

/**
 * pgJDBC's {@code sslfactory} for the metadata DB: TLS 1.3 or 1.2, trusting only {@code db.tls.ca} and presenting
 * {@code db.tls.cert} with its PEM key, which pgJDBC cannot read itself. pgJDBC makes one per connection, so the files
 * are read again for every new connection and a renewed certificate is picked up without a restart. With
 * {@code sslmode=verify-full}, pgJDBC checks the server's host name after the handshake.
 */
public final class PemSslFactory extends SSLSocketFactory {

    /** Connection properties naming the files; set by {@link DbTls}. */
    static final String CA = "fdropTlsCa";
    static final String CERT = "fdropTlsCert";
    static final String KEY = "fdropTlsKey";
    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final SSLSocketFactory delegate;

    public PemSslFactory(Properties info) {
        try {
            this.delegate = context(Path.of(info.getProperty(CA)), Path.of(info.getProperty(CERT)),
                    Path.of(info.getProperty(KEY))).getSocketFactory();
        } catch (IOException e) {
            throw new UncheckedIOException("metadata DB TLS: " + e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("metadata DB TLS: " + e.getMessage(), e);
        }
    }

    static SSLContext context(Path ca, Path cert, Path key) throws IOException, GeneralSecurityException {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        int i = 0;
        for (X509Certificate c : Pem.certificates(ca)) {
            trust.setCertificateEntry("ca" + i++, c);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);

        List<X509Certificate> chain = Pem.certificates(cert);
        PrivateKey pk = Pem.privateKey(key);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        char[] none = new char[0];
        keys.setKeyEntry("client", pk, none, chain.toArray(new X509Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, none);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    private static Socket limit(Socket s) {
        if (s instanceof SSLSocket ssl) {
            ssl.setEnabledProtocols(PROTOCOLS);
        }
        return s;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return limit(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return limit(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return limit(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return limit(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return limit(delegate.createSocket(address, port, localAddress, localPort));
    }
}
