package io.fdrop.sftp;

import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.service.SshTools;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Iterator;

/** SSH key handling with Apache MINA SSHD: generation, parsing, fingerprints, certificates, connection probes. */
public final class MinaSshTools implements SshTools {

    private final ForwardClient client;

    public MinaSshTools(ForwardClient client) {
        this.client = client;
    }

    @Override
    public GeneratedKey generateEd25519(String comment) {
        try {
            KeyPair kp = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, comment, null, out);
            return new GeneratedKey(PublicKeyEntry.toString(kp.getPublic()) + " " + comment, out.toByteArray());
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate an Ed25519 key", e);
        }
    }

    /** Loads a private key written by {@link #generateEd25519}. */
    public static KeyPair loadPrivateKey(byte[] openSsh) {
        try {
            Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("fdrop-key"),
                    new ByteArrayInputStream(openSsh), null);
            Iterator<KeyPair> it = pairs.iterator();
            if (!it.hasNext()) {
                throw new IllegalStateException("no key in stored private key");
            }
            return it.next();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot load stored private key", e);
        }
    }

    public static PublicKey parse(String line) {
        try {
            PublicKeyEntry e = PublicKeyEntry.parsePublicKeyEntry(line.strip());
            PublicKey k = e.resolvePublicKey(null, null, PublicKeyEntryResolver.FAILING);
            if (k == null) {
                throw FdropException.badRequest("unsupported key type");
            }
            return k;
        } catch (IllegalArgumentException | IOException | GeneralSecurityException e) {
            throw FdropException.badRequest("not an OpenSSH public key: " + e.getMessage());
        }
    }

    public static String fingerprint(PublicKey k) {
        PublicKey base = k instanceof OpenSshCertificate c ? c.getCertPubKey() : k;
        return KeyUtils.getFingerPrint(base);
    }

    @Override
    public ParsedKey parsePublicKey(String line) {
        if (line == null || line.isBlank()) {
            throw FdropException.badRequest("public key is required");
        }
        PublicKey k = parse(line);
        String normalized = PublicKeyEntry.toString(k);
        String type = normalized.substring(0, normalized.indexOf(' '));
        return new ParsedKey(type, fingerprint(k), normalized);
    }

    @Override
    public void checkUserCertificate(String certLine, String publicKeyLine) {
        PublicKey k = parse(certLine);
        if (!(k instanceof OpenSshCertificate cert)) {
            throw FdropException.badRequest("not an OpenSSH certificate");
        }
        if (cert.getType() != OpenSshCertificate.Type.USER) {
            throw FdropException.badRequest("this is a host certificate; a user certificate is needed");
        }
        if (!KeyUtils.compareKeys(cert.getCertPubKey(), parse(publicKeyLine))) {
            throw FdropException.badRequest("the certificate was not issued for this key");
        }
        if (!OpenSshCertificate.isValidNow(cert)) {
            throw FdropException.badRequest("the certificate is expired or not yet valid");
        }
    }

    @Override
    public TestResult probe(String host, int port, String username, String publicKey, byte[] privateKeyOpenSsh,
                            String userCert, ForwardDestination pinnedOrNull) {
        return client.probe(host, port, username, privateKeyOpenSsh, userCert, pinnedOrNull);
    }
}
