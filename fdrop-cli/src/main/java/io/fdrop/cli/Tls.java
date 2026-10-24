package io.fdrop.cli;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * The client's TLS setup: its certificate and key for client authentication, and the JDK's default trust store
 * plus an optional extra CA bundle for the server's certificate. Server certificate and hostname checks are
 * always on; there is deliberately no way to turn them off.
 */
final class Tls {

    private Tls() {
    }

    static SSLContext context(Credentials client, Path extraCas) {
        try {
            char[] none = new char[0];
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("client", client.key(), none, client.chain().toArray(new X509Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, none);

            List<X509TrustManager> trust = new ArrayList<>();
            trust.add(trustManager(null));
            if (extraCas != null) {
                KeyStore cas = KeyStore.getInstance("PKCS12");
                cas.load(null, null);
                int i = 0;
                for (X509Certificate c : Credentials.certificates(extraCas)) {
                    cas.setCertificateEntry("ca" + i++, c);
                }
                trust.add(trustManager(cas));
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), new TrustManager[]{new AnyOf(trust)}, null);
            return ctx;
        } catch (IOException | GeneralSecurityException e) {
            throw new CliException(CliException.USAGE, "cannot set up TLS: " + e.getMessage());
        }
    }

    /** TLS 1.2 and 1.3 only, with the hostname checked against the server certificate. */
    static SSLParameters parameters() {
        SSLParameters p = new SSLParameters();
        p.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        p.setEndpointIdentificationAlgorithm("HTTPS");
        return p;
    }

    private static X509TrustManager trustManager(KeyStore ks) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) {
                return x;
            }
        }
        throw new GeneralSecurityException("no X.509 trust manager");
    }

    /** Trusts a server certificate any of the trust managers trusts. */
    private record AnyOf(List<X509TrustManager> managers) implements X509TrustManager {
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager m : managers) {
                try {
                    m.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException e) {
                    last = e;
                }
            }
            throw last == null ? new CertificateException("untrusted server certificate") : last;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("not a server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return managers.stream().flatMap(m -> java.util.Arrays.stream(m.getAcceptedIssuers()))
                    .toArray(X509Certificate[]::new);
        }
    }
}
