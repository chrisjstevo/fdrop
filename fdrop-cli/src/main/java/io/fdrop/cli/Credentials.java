package io.fdrop.cli;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The client certificate and private key, loaded with the JDK alone: a PEM certificate chain with a PKCS#8 key
 * (plain or encrypted), or a PKCS#12 file. Error messages never contain key or certificate contents.
 */
public record Credentials(PrivateKey key, List<X509Certificate> chain) {

    private static final Pattern BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    public X509Certificate leaf() {
        return chain.get(0);
    }

    /** Lower-case hex SHA-256 of the leaf certificate, as fdrop pins it. */
    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(leaf().getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A PEM certificate chain and a PKCS#8 private key; {@code password} only for an encrypted key. */
    public static Credentials fromPem(Path certFile, Path keyFile, char[] password) {
        List<X509Certificate> chain = certificates(certFile);
        PrivateKey key = privateKey(keyFile, password);
        Credentials c = new Credentials(key, chain);
        c.checkPair(certFile, keyFile);
        return c;
    }

    /** The first key entry of a PKCS#12 file. */
    public static Credentials fromPkcs12(Path file, char[] password) {
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            for (String alias : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(alias)) {
                    PrivateKey key = (PrivateKey) ks.getKey(alias, password);
                    List<X509Certificate> chain = new ArrayList<>();
                    for (Certificate c : ks.getCertificateChain(alias)) {
                        chain.add((X509Certificate) c);
                    }
                    return new Credentials(key, List.copyOf(chain));
                }
            }
            throw new CliException(CliException.USAGE, "no private key in " + file);
        } catch (IOException e) {
            if (e.getCause() instanceof java.security.UnrecoverableKeyException || password == null) {
                throw new CliException(CliException.USAGE, "cannot open " + file + ": wrong or missing keystore"
                        + " password (--keystore-password-file)");
            }
            throw new CliException(CliException.USAGE, "cannot read " + file + ": " + e.getMessage());
        } catch (GeneralSecurityException e) {
            throw new CliException(CliException.USAGE, "cannot read " + file + ": " + e.getMessage());
        }
    }

    /** Every certificate in a PEM file, leaf first. */
    public static List<X509Certificate> certificates(Path file) {
        byte[] bytes = read(file);
        try {
            List<X509Certificate> out = new ArrayList<>();
            for (Certificate c : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(bytes))) {
                out.add((X509Certificate) c);
            }
            if (out.isEmpty()) {
                throw new CliException(CliException.USAGE, "no certificate found in " + file);
            }
            return List.copyOf(out);
        } catch (CertificateException e) {
            throw new CliException(CliException.USAGE, file + " is not a PEM certificate");
        }
    }

    static PrivateKey privateKey(Path file, char[] password) {
        String text = new String(read(file), StandardCharsets.US_ASCII);
        Matcher m = BLOCK.matcher(text);
        if (!m.find()) {
            throw new CliException(CliException.USAGE, file + " is not a PEM private key");
        }
        String type = m.group(1);
        byte[] der = Base64.getMimeDecoder().decode(m.group(2));
        return switch (type) {
            case "PRIVATE KEY" -> pkcs8(der, file);
            case "ENCRYPTED PRIVATE KEY" -> {
                if (password == null) {
                    throw new CliException(CliException.USAGE, file + " is encrypted: give its password with"
                            + " --key-password-file");
                }
                yield pkcs8(decrypt(der, password, file), file);
            }
            case "RSA PRIVATE KEY", "EC PRIVATE KEY", "OPENSSH PRIVATE KEY" -> throw new CliException(
                    CliException.USAGE, file + " is a traditional " + type.replace(" PRIVATE KEY", "") + " key;"
                    + " convert it to PKCS#8 with: openssl pkcs8 -topk8 -nocrypt -in " + file + " -out key.pk8");
            default -> throw new CliException(CliException.USAGE, file + " holds a " + type + ", not a private key");
        };
    }

    private static byte[] decrypt(byte[] der, char[] password, Path file) {
        try {
            EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(der);
            String alg = info.getAlgName();
            Cipher cipher = Cipher.getInstance(alg);
            cipher.init(Cipher.DECRYPT_MODE, SecretKeyFactory.getInstance(alg).generateSecret(new PBEKeySpec(password)),
                    info.getAlgParameters());
            return info.getKeySpec(cipher).getEncoded();
        } catch (IOException | GeneralSecurityException e) {
            throw new CliException(CliException.USAGE, "cannot decrypt " + file + ": wrong password or unsupported"
                    + " encryption");
        }
    }

    private static PrivateKey pkcs8(byte[] der, Path file) {
        for (String alg : List.of("RSA", "EC", "Ed25519", "Ed448")) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                // try the next algorithm
            }
        }
        throw new CliException(CliException.USAGE, file + " holds a key type this client does not support (use"
                + " RSA, EC or Ed25519)");
    }

    /** Proves the key belongs to the certificate by signing and verifying a random message. */
    private void checkPair(Path certFile, Path keyFile) {
        PublicKey pub = leaf().getPublicKey();
        String alg = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> key.getAlgorithm();
        };
        try {
            byte[] msg = new byte[32];
            new SecureRandom().nextBytes(msg);
            Signature s = Signature.getInstance(alg);
            s.initSign(key);
            s.update(msg);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance(alg);
            v.initVerify(pub);
            v.update(msg);
            if (v.verify(sig)) {
                return;
            }
        } catch (GeneralSecurityException e) {
            // a key of another type than the certificate's: reported below
        }
        throw new CliException(CliException.USAGE, "the key in " + keyFile + " does not belong to the certificate in "
                + certFile);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, "cannot read " + file + (Files.exists(file) ? ": "
                    + e.getMessage() : ": no such file"));
        }
    }
}
