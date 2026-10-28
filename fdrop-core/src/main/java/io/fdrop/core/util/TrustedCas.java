package io.fdrop.core.util;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Trust for one kind of outbound call (Keycloak, the S3 store): the CAs in a PEM bundle as well as the JDK's own trust
 * store, without changing the JVM-wide default (see "Security" in SPEC.md).
 */
public final class TrustedCas {

    private TrustedCas() {
    }

    /** A trust manager for the JDK's CAs and those in {@code pemFile}; the JDK's alone when it is null or blank. */
    public static X509TrustManager trustManager(String pemFile) {
        try {
            X509TrustManager jdk = manager(null);
            if (pemFile == null || pemFile.isBlank()) {
                return jdk;
            }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(Files.readAllBytes(Path.of(pemFile))))) {
                ks.setCertificateEntry("ca" + i++, c);
            }
            if (i == 0) {
                throw new IllegalStateException("no certificate in " + pemFile);
            }
            X509TrustManager extra = manager(ks);
            return new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    jdk.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        extra.checkServerTrusted(chain, authType);
                    } catch (CertificateException e) {
                        jdk.checkServerTrusted(chain, authType);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    List<X509Certificate> all = new ArrayList<>(List.of(jdk.getAcceptedIssuers()));
                    all.addAll(List.of(extra.getAcceptedIssuers()));
                    return all.toArray(new X509Certificate[0]);
                }
            };
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot read the trusted CAs in " + pemFile + ": " + e.getMessage(), e);
        }
    }

    /** A TLS 1.2+ context trusting the JDK's CAs and those in {@code pemFile}. */
    public static SSLContext context(String pemFile) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{trustManager(pemFile)}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Refuses a URL that is not https: fdrop never calls another service over plain HTTP. */
    public static void requireHttps(String setting, String url) {
        if (url != null && !url.isBlank() && !url.regionMatches(true, 0, "https://", 0, 8)) {
            throw new IllegalStateException(setting + " must be an https:// URL, not " + url
                    + ": fdrop never calls another service over plain HTTP");
        }
    }

    private static X509TrustManager manager(KeyStore ks) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) {
                return x;
            }
        }
        throw new GeneralSecurityException("no X.509 trust manager");
    }
}
