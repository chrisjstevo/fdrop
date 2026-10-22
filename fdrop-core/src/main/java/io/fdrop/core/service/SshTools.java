package io.fdrop.core.service;

import io.fdrop.core.model.Records.ForwardDestination;

/**
 * SSH key handling and forward connection tests, implemented in the SFTP module with Apache MINA SSHD so the
 * core stays free of SSH dependencies.
 */
public interface SshTools {

    /** A newly generated client key pair. */
    record GeneratedKey(String publicKey, byte[] privateKeyOpenSsh) {
    }

    /** A validated public key line. */
    record ParsedKey(String type, String fingerprint, String normalized) {
    }

    /** What a test or probe connection found. */
    record TestResult(boolean connected, boolean authenticated, String hostKeyType, String hostKeyFingerprint,
                      boolean hostCertificate, String hostCertificateCa, String error) {
    }

    GeneratedKey generateEd25519(String comment);

    /** Parses an OpenSSH public key line ({@code ssh-ed25519 AAAA... comment}); 400 if malformed. */
    ParsedKey parsePublicKey(String line);

    /** Checks that {@code certLine} is an OpenSSH user certificate for {@code publicKeyLine}; 400 otherwise. */
    void checkUserCertificate(String certLine, String publicKeyLine);

    /**
     * Connects to {@code host:port}, reports the host key or certificate presented, and, when a key is given,
     * whether fdrop could log in with it. Never trusts the host: this is how an admin learns the fingerprint.
     */
    TestResult probe(String host, int port, String username, String publicKey, byte[] privateKeyOpenSsh,
                     String userCert, ForwardDestination pinnedOrNull);
}
