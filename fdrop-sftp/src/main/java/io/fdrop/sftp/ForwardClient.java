package io.fdrop.sftp;

import io.fdrop.core.model.Enums.ServerAuth;
import io.fdrop.core.model.Records.ForwardDestination;
import io.fdrop.core.service.SshTools.TestResult;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The SSH client behind forward folders and destination tests. Every connection is mutually authenticated: the
 * server's host key must match the pin, or be a valid host certificate from the configured CA, issued for the host;
 * fdrop logs in with its own key (and user certificate) only. Password authentication is never offered.
 */
public final class ForwardClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ForwardClient.class);
    static final AttributeKey<Expectation> EXPECT = new AttributeKey<>();

    /** What one connection expects of the server, and what it found. */
    static final class Expectation {
        final ForwardDestination destination;
        final String host;
        final AtomicReference<PublicKey> seen = new AtomicReference<>();
        final AtomicReference<String> rejection = new AtomicReference<>();

        Expectation(ForwardDestination destination, String host) {
            this.destination = destination;
            this.host = host;
        }
    }

    /** Why a connection failed, for retry decisions and the failure metric. */
    public enum Failure { HOST_KEY_MISMATCH, AUTH_REJECTED, CONNECT, IO }

    /** A connection or login failure with its classification. */
    public static final class ForwardException extends IOException {
        private final Failure failure;

        ForwardException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }

        /** Host key and login failures are not retried. */
        public boolean permanent() {
            return failure == Failure.HOST_KEY_MISMATCH || failure == Failure.AUTH_REJECTED;
        }
    }

    private final SshClient client;
    private final Duration timeout;

    public ForwardClient(Duration timeout) {
        this.timeout = timeout;
        this.client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(this::verify);
        client.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
        client.start();
    }

    private static Expectation expectation(ClientSession s) {
        Expectation e = s.getAttribute(EXPECT);
        if (e == null && s.getConnectionContext() != null) {
            e = s.getConnectionContext().getAttribute(EXPECT);
        }
        return e;
    }

    private boolean verify(ClientSession session, SocketAddress remote, PublicKey key) {
        Expectation e = expectation(session);
        if (e == null) {
            return false;
        }
        e.seen.set(key);
        ForwardDestination d = e.destination;
        if (d == null) {
            // a probe: report the key, trust nothing
            return true;
        }
        if (d.serverAuth() == ServerAuth.HOST_KEY) {
            String fp = MinaSshTools.fingerprint(key);
            if (d.hostKeySha256() != null && d.hostKeySha256().equals(fp)) {
                return true;
            }
            e.rejection.set("host key " + fp + " does not match the pinned " + d.hostKeySha256());
            return false;
        }
        if (!(key instanceof OpenSshCertificate cert)) {
            e.rejection.set("server presented a plain host key, not a certificate from the configured CA");
            return false;
        }
        if (cert.getType() != OpenSshCertificate.Type.HOST) {
            e.rejection.set("server certificate is not a host certificate");
            return false;
        }
        if (d.hostCaPublicKey() == null || !KeyUtils.compareKeys(cert.getCaPubKey(),
                MinaSshTools.parse(d.hostCaPublicKey()))) {
            e.rejection.set("host certificate is not signed by the configured CA");
            return false;
        }
        if (!OpenSshCertificate.isValidNow(cert)) {
            e.rejection.set("host certificate is expired or not yet valid");
            return false;
        }
        if (cert.getPrincipals() != null && !cert.getPrincipals().isEmpty()
                && !cert.getPrincipals().contains(e.host)) {
            e.rejection.set("host certificate is not issued for " + e.host);
            return false;
        }
        return true;
    }

    /** Opens an authenticated session, or throws a classified {@link ForwardException}. */
    public ClientSession open(ForwardDestination d, KeyPair key, String userCert) throws ForwardException {
        Expectation e = new Expectation(d, d.host());
        ClientSession s = connect(d.host(), d.port(), d.username(), e);
        login(s, e, key, userCert);
        return s;
    }

    private ClientSession connect(String host, int port, String username, Expectation e) throws ForwardException {
        try {
            return client.connect(username, host, port, AttributeRepository.ofKeyValuePair(EXPECT, e), null)
                    .verify(timeout).getSession();
        } catch (IOException | RuntimeException ex) {
            throw new ForwardException(Failure.CONNECT, "cannot connect to " + host + ":" + port + ": "
                    + ex.getMessage(), ex);
        }
    }

    private void login(ClientSession s, Expectation e, KeyPair key, String userCert) throws ForwardException {
        try {
            KeyPair identity = key;
            if (userCert != null && !userCert.isBlank()) {
                identity = new KeyPair(MinaSshTools.parse(userCert), key.getPrivate());
            }
            s.addPublicKeyIdentity(identity);
            s.auth().verify(timeout);
        } catch (IOException | RuntimeException ex) {
            closeQuietly(s);
            if (e.rejection.get() != null) {
                throw new ForwardException(Failure.HOST_KEY_MISMATCH, e.rejection.get(), ex);
            }
            if (e.seen.get() == null) {
                throw new ForwardException(Failure.CONNECT, "connection closed before key exchange: "
                        + ex.getMessage(), ex);
            }
            throw new ForwardException(Failure.AUTH_REJECTED, "the destination rejected fdrop's key"
                    + (userCert != null ? " and certificate" : "") + ": " + ex.getMessage(), ex);
        }
    }

    /** Connects without trusting the server, reports its key, and optionally tries to log in. */
    TestResult probe(String host, int port, String username, byte[] privateKey, String userCert,
                     ForwardDestination pinned) {
        Expectation e = new Expectation(pinned, host);
        ClientSession s = null;
        try {
            s = connect(host, port, username == null ? "fdrop-probe" : username, e);
            boolean authed = false;
            String error = null;
            if (privateKey != null) {
                try {
                    login(s, e, MinaSshTools.loadPrivateKey(privateKey), userCert);
                    authed = true;
                } catch (ForwardException fe) {
                    error = fe.getMessage();
                    s = null;
                }
            } else {
                // finish key exchange so the host key is known
                try {
                    s.waitFor(java.util.EnumSet.of(ClientSession.ClientSessionEvent.WAIT_AUTH,
                            ClientSession.ClientSessionEvent.CLOSED), timeout);
                } catch (RuntimeException ignored) {
                    // a closed session still reports what it saw
                }
            }
            PublicKey seen = e.seen.get();
            OpenSshCertificate cert = seen instanceof OpenSshCertificate c ? c : null;
            return new TestResult(true, authed, seen == null ? null : KeyUtils.getKeyType(seen),
                    seen == null ? null : MinaSshTools.fingerprint(seen), cert != null,
                    cert == null ? null : KeyUtils.getFingerPrint(cert.getCaPubKey()),
                    error != null ? error : e.rejection.get());
        } catch (ForwardException fe) {
            return new TestResult(false, false, null, null, false, null, fe.getMessage());
        } finally {
            closeQuietly(s);
        }
    }

    private static void closeQuietly(ClientSession s) {
        if (s != null) {
            try {
                s.close(true);
            } catch (RuntimeException ignored) {
                // closing anyway
            }
        }
    }

    @Override
    public void close() {
        try {
            client.stop();
        } catch (RuntimeException e) {
            log.debug("ssh client stop: {}", e.toString());
        }
    }
}
