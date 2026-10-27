package io.fdrop.client;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A TLS context from PEM files. Errors name the file, never its contents. */
final class Pem {

    private static final Pattern KEY = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private Pem() {
    }

    static SSLContext sslContext(Path cert, Path key, Path ca) {
        try {
            TrustManagerFactory tmf = null;
            if (ca != null) {
                KeyStore trust = KeyStore.getInstance("PKCS12");
                trust.load(null, null);
                int i = 0;
                for (X509Certificate c : certificates(ca)) {
                    trust.setCertificateEntry("ca" + i++, c);
                }
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trust);
            }
            KeyManagerFactory kmf = null;
            if (cert != null || key != null) {
                if (cert == null || key == null) {
                    throw new FdropClientException("a client certificate needs both its certificate and key file");
                }
                KeyStore keys = KeyStore.getInstance("PKCS12");
                keys.load(null, null);
                char[] none = new char[0];
                keys.setKeyEntry("client", privateKey(key), none, certificates(cert).toArray(new X509Certificate[0]));
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keys, none);
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), null);
            return ctx;
        } catch (IOException | GeneralSecurityException e) {
            throw new FdropClientException("cannot set up TLS: " + e.getMessage(), e);
        }
    }

    static List<X509Certificate> certificates(Path file) throws IOException {
        try {
            List<X509Certificate> out = new ArrayList<>();
            for (Certificate c : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(Files.readAllBytes(file)))) {
                out.add((X509Certificate) c);
            }
            if (out.isEmpty()) {
                throw new IOException("no certificate in " + file);
            }
            return out;
        } catch (java.security.cert.CertificateException e) {
            throw new IOException(file + " is not a PEM certificate");
        }
    }

    static PrivateKey privateKey(Path file) throws IOException {
        Matcher m = KEY.matcher(Files.readString(file, StandardCharsets.US_ASCII));
        if (!m.find() || !m.group(1).equals("PRIVATE KEY")) {
            throw new IOException(file + " is not an unencrypted PKCS#8 key (BEGIN PRIVATE KEY); convert it with"
                    + " openssl pkcs8 -topk8 -nocrypt");
        }
        byte[] der = Base64.getMimeDecoder().decode(m.group(2));
        for (String alg : List.of("EC", "RSA", "Ed25519")) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                // try the next
            }
        }
        throw new IOException(file + " holds an unsupported key type (use EC, RSA or Ed25519)");
    }
}
