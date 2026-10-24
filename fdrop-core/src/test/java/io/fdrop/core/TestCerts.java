package io.fdrop.core;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Certificates for tests: a CA, server certificates for {@code localhost}, and client certificates, all EC P-256,
 * with PEM output and ready-made {@link SSLContext}s.
 */
public final class TestCerts {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A key pair and its certificate chain, leaf first. */
    public record Issued(KeyPair keys, List<X509Certificate> chain) {
        public X509Certificate cert() {
            return chain.get(0);
        }

        public String certPem() {
            StringBuilder sb = new StringBuilder();
            for (X509Certificate c : chain) {
                sb.append(pem("CERTIFICATE", encoded(c)));
            }
            return sb.toString();
        }

        /** The private key as unencrypted PKCS#8 PEM ({@code BEGIN PRIVATE KEY}). */
        public String keyPem() {
            return pem("PRIVATE KEY", keys.getPrivate().getEncoded());
        }

        /** Writes {@code <name>.crt} and {@code <name>.key} into a directory. */
        public Path[] write(Path dir, String name) throws IOException {
            Path crt = dir.resolve(name + ".crt");
            Path key = dir.resolve(name + ".key");
            Files.writeString(crt, certPem());
            Files.writeString(key, keyPem());
            return new Path[]{crt, key};
        }

        /** A key store with this key and chain, for a client or server key manager. */
        public KeyStore keyStore(char[] password) throws GeneralSecurityException, IOException {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("key", keys.getPrivate(), password, chain.toArray(new X509Certificate[0]));
            return ks;
        }
    }

    private TestCerts() {
    }

    public static Issued ca(String cn) {
        KeyPair kp = keyPair();
        X509Certificate c = build(new X500Name("CN=" + cn), kp, new X500Name("CN=" + cn), kp.getPrivate(),
                Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)), true, null, null);
        return new Issued(kp, List.of(c));
    }

    public static Issued server(Issued ca) {
        return leaf(ca, "CN=localhost", Duration.ofDays(30), KeyPurposeId.id_kp_serverAuth,
                new GeneralName(GeneralName.dNSName, "localhost"), new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
    }

    /** A client certificate; {@code dn} like {@code CN=app1,O=Test}, SANs as {@code DNS:x} or {@code URI:x}. */
    public static Issued client(Issued ca, String dn, String... sans) {
        return client(ca, dn, Duration.ofDays(30), sans);
    }

    public static Issued client(Issued ca, String dn, Duration validFor, String... sans) {
        List<GeneralName> names = new ArrayList<>();
        for (String san : sans) {
            String value = san.substring(san.indexOf(':') + 1);
            names.add(switch (san.substring(0, san.indexOf(':'))) {
                case "DNS" -> new GeneralName(GeneralName.dNSName, value);
                case "URI" -> new GeneralName(GeneralName.uniformResourceIdentifier, value);
                case "EMAIL" -> new GeneralName(GeneralName.rfc822Name, value);
                default -> throw new IllegalArgumentException(san);
            });
        }
        return leaf(ca, dn, validFor, KeyPurposeId.id_kp_clientAuth, names.toArray(new GeneralName[0]));
    }

    /** A self-signed client certificate, for pinning. */
    public static Issued selfSigned(String dn) {
        return selfSigned(dn, "EC");
    }

    /** A self-signed client certificate with an {@code EC}, {@code RSA} or {@code Ed25519} key. */
    public static Issued selfSigned(String dn, String keyAlgorithm) {
        KeyPair kp = keyPair(keyAlgorithm);
        X509Certificate c = build(new X500Name(dn), kp, new X500Name(dn), kp.getPrivate(),
                Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(30)), false,
                KeyPurposeId.id_kp_clientAuth, null);
        return new Issued(kp, List.of(c));
    }

    /** A certificate that expired yesterday. */
    public static Issued expired(String dn) {
        KeyPair kp = keyPair();
        X509Certificate c = build(new X500Name(dn), kp, new X500Name(dn), kp.getPrivate(),
                Instant.now().minus(Duration.ofDays(10)), Instant.now().minus(Duration.ofDays(1)), false,
                KeyPurposeId.id_kp_clientAuth, null);
        return new Issued(kp, List.of(c));
    }

    private static Issued leaf(Issued ca, String dn, Duration validFor, KeyPurposeId purpose, GeneralName... sans) {
        KeyPair kp = keyPair();
        X509Certificate issuer = ca.cert();
        X509Certificate c = build(new X500Name(dn), kp,
                X500Name.getInstance(issuer.getSubjectX500Principal().getEncoded()), ca.keys().getPrivate(),
                Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(validFor), false, purpose,
                sans.length == 0 ? null : new GeneralNames(sans));
        return new Issued(kp, List.of(c, issuer));
    }

    /** A client SSLContext presenting {@code client} (may be null) and trusting {@code serverCa}. */
    public static SSLContext clientContext(Issued client, Issued serverCa) throws GeneralSecurityException, IOException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = null;
        if (client != null) {
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(client.keyStore(new char[0]), new char[0]);
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", serverCa.cert());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), RANDOM);
        return ctx;
    }

    public static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static byte[] encoded(X509Certificate c) {
        try {
            return c.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair keyPair() {
        return keyPair("EC");
    }

    private static KeyPair keyPair(String algorithm) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance(algorithm);
            switch (algorithm) {
                case "EC" -> g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
                case "RSA" -> g.initialize(2048, RANDOM);
                default -> {
                    // Ed25519 has no parameters
                }
            }
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate build(X500Name subject, KeyPair subjectKeys, X500Name issuer, PrivateKey signer,
                                         Instant from, Instant to, boolean ca, KeyPurposeId purpose,
                                         GeneralNames sans) {
        try {
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(issuer, new BigInteger(64, RANDOM),
                    Date.from(from), Date.from(to), subject, subjectKeys.getPublic());
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            if (purpose != null) {
                b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(purpose));
            }
            if (sans != null) {
                b.addExtension(Extension.subjectAlternativeName, false, sans);
            }
            return new JcaX509CertificateConverter().getCertificate(
                    b.build(new JcaContentSignerBuilder(switch (signer.getAlgorithm()) {
                        case "RSA" -> "SHA256withRSA";
                        case "EC" -> "SHA256withECDSA";
                        default -> "Ed25519";
                    }).build(signer)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
