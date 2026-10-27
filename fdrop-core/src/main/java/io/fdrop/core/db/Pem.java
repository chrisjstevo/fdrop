package io.fdrop.core.db;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PEM certificates and PKCS#8 keys for the DB connection. Messages name the file, never its contents. */
final class Pem {

    private static final Pattern BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private Pem() {
    }

    static List<X509Certificate> certificates(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            List<X509Certificate> out = new ArrayList<>();
            for (Certificate c : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(bytes))) {
                out.add((X509Certificate) c);
            }
            if (out.isEmpty()) {
                throw new IOException("no certificate found in " + file);
            }
            return List.copyOf(out);
        } catch (CertificateException e) {
            throw new IOException(file + " is not a PEM certificate");
        }
    }

    static PrivateKey privateKey(Path file) throws IOException {
        Matcher m = BLOCK.matcher(Files.readString(file, StandardCharsets.US_ASCII));
        if (!m.find()) {
            throw new IOException(file + " is not a PEM private key");
        }
        String type = m.group(1);
        if (type.equals("RSA PRIVATE KEY") || type.equals("EC PRIVATE KEY")) {
            throw new IOException(file + " is a traditional " + type.replace(" PRIVATE KEY", "") + " key; convert it"
                    + " to PKCS#8 with: openssl pkcs8 -topk8 -nocrypt -in " + file + " -out client.pk8.pem");
        }
        if (!type.equals("PRIVATE KEY")) {
            throw new IOException(file + " holds a " + type + ", not an unencrypted PKCS#8 private key");
        }
        byte[] der = Base64.getMimeDecoder().decode(m.group(2));
        for (String alg : List.of("EC", "RSA", "Ed25519")) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                // try the next algorithm
            }
        }
        throw new IOException(file + " holds a key type that is not supported (use EC, RSA or Ed25519)");
    }

    /** Whether {@code key} belongs to {@code cert}: signs a random message and checks it with the certificate. */
    static boolean matches(PrivateKey key, X509Certificate cert) {
        String alg = switch (key.getAlgorithm()) {
            case "EC" -> "SHA256withECDSA";
            case "RSA" -> "SHA256withRSA";
            default -> key.getAlgorithm();
        };
        try {
            byte[] msg = new byte[32];
            new SecureRandom().nextBytes(msg);
            Signature sign = Signature.getInstance(alg);
            sign.initSign(key);
            sign.update(msg);
            byte[] sig = sign.sign();
            Signature verify = Signature.getInstance(alg);
            verify.initVerify(cert.getPublicKey());
            verify.update(msg);
            return verify.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
