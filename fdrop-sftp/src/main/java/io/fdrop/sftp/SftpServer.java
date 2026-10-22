package io.fdrop.sftp;

import io.fdrop.core.Fdrop;
import io.fdrop.core.audit.Audit;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.model.Records.SshKey;
import io.fdrop.core.model.Records.User;
import io.fdrop.core.util.TrustedProxies;
import io.fdrop.sftp.fs.FdropFileSystem;
import io.fdrop.sftp.fs.FdropFileSystemProvider;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge;
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator;
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The SFTP server on Apache MINA SSHD. Only the SFTP subsystem is enabled: no shell, exec, port forwarding or SCP.
 * Passwords are checked against Keycloak; public keys against the keys users add in the web UI, with Keycloak
 * consulted (cached) to make sure the user is still enabled. Each session sees its user's virtual tree.
 */
public final class SftpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SftpServer.class);
    static final AttributeKey<Principal> PRINCIPAL = new AttributeKey<>();
    private static final AttributeKey<Boolean> WANTS_CODE = new AttributeKey<>();
    static final AttributeKey<FdropFileSystem> FILE_SYSTEM = new AttributeKey<>();

    /** Weak algorithms removed from MINA's defaults: SHA-1 key exchange and signatures, CBC, 3DES, RC4. */
    private static boolean weak(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith("-sha1") || n.contains("group1-") || n.contains("group-exchange-sha1") || n.equals("ssh-rsa")
                || n.equals("ssh-dss") || n.contains("cbc") || n.contains("3des") || n.contains("arcfour")
                || n.contains("blowfish") || n.equals("ssh-rsa-cert-v01@openssh.com")
                || n.equals("ssh-dss-cert-v01@openssh.com") || n.startsWith("hmac-sha1") || n.startsWith("hmac-md5");
    }

    private final Fdrop app;
    private final SshServer sshd;
    private final FdropFileSystemProvider provider;
    private final Map<String, AtomicInteger> sessionsPerUser = new ConcurrentHashMap<>();

    public SftpServer(Fdrop app) {
        this.app = app;
        FdropConfig.Sftp cfg = app.config.sftp;
        this.provider = new FdropFileSystemProvider(app.files, app.metrics);
        this.sshd = SshServer.setUpDefaultServer();
        sshd.setHost(cfg.bind);
        sshd.setPort(cfg.port);

        KeyPairProvider hostKeys = KeyPairProvider.wrap(loadOrCreateHostKey(Path.of(cfg.hostKeyPath)));
        sshd.setKeyPairProvider(hostKeys);

        sshd.setKeyExchangeFactories(sshd.getKeyExchangeFactories().stream().filter(f -> !weak(f.getName()))
                .toList());
        sshd.setCipherFactories(strong(sshd.getCipherFactories()));
        sshd.setSignatureFactories(strong(sshd.getSignatureFactories()));
        sshd.setMacFactories(strong(sshd.getMacFactories()));

        // keyboard-interactive is how users who need two factors log in with a password and a one-time code
        sshd.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE,
                UserAuthKeyboardInteractiveFactory.INSTANCE, UserAuthPasswordFactory.INSTANCE));
        sshd.setPasswordAuthenticator(this::password);
        sshd.setPublickeyAuthenticator(this::publicKey);
        sshd.setKeyboardInteractiveAuthenticator(new CodeAuthenticator());
        sshd.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
        sshd.setShellFactory(null);
        sshd.setCommandFactory(null);

        CoreModuleProperties.AUTH_TIMEOUT.set(sshd, java.time.Duration.ofSeconds(60));
        CoreModuleProperties.IDLE_TIMEOUT.set(sshd, Durations.parse(cfg.idleTimeout));
        CoreModuleProperties.MAX_AUTH_REQUESTS.set(sshd, 3);
        CoreModuleProperties.SERVER_IDENTIFICATION.set(sshd, "fdrop");

        if (cfg.proxyProtocol) {
            sshd.setServerProxyAcceptor(new ProxyProtocolAcceptor(new TrustedProxies(app.config.http.trustedProxies)));
        }

        SftpSubsystemFactory sftp = new SftpSubsystemFactory.Builder().withFileSystemAccessor(new Accessor()).build();
        sftp.addSftpEventListener(new Events());
        sshd.setSubsystemFactories(List.of(sftp));
        sshd.setFileSystemFactory(new FileSystemFactory() {
            @Override
            public Path getUserHomeDir(SessionContext session) {
                return fileSystem((ServerSession) session).getPath("/home");
            }

            @Override
            public FileSystem createFileSystem(SessionContext session) {
                return fileSystem((ServerSession) session);
            }
        });
        sshd.addSessionListener(new Sessions());
        app.userAdmin.addTerminator(this::terminate);
    }

    private static <T extends NamedFactory<?>> List<T> strong(List<T> factories) {
        return factories.stream().filter(f -> !weak(f.getName())).toList();
    }

    /**
     * The persistent Ed25519 host key: generated on first start and written in OpenSSH format with owner-only
     * permissions, so clients never see the key change across restarts.
     */
    static java.security.KeyPair loadOrCreateHostKey(Path file) {
        try {
            if (Files.exists(file)) {
                return MinaSshTools.loadPrivateKey(Files.readAllBytes(file));
            }
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            java.security.KeyPair kp = org.apache.sshd.common.config.keys.KeyUtils.generateKeyPair(
                    KeyPairProvider.SSH_ED25519, 256);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE
                    .writePrivateKey(kp, "fdrop host key", null, out);
            Files.write(file, out.toByteArray());
            try {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            log.info("generated SFTP host key {} ({})", file, MinaSshTools.fingerprint(kp.getPublic()));
            return kp;
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot load or create the SFTP host key " + file, e);
        }
    }

    /** The session's file system, created once at first use. */
    private FdropFileSystem fileSystem(ServerSession s) {
        FdropFileSystem fs = s.getAttribute(FILE_SYSTEM);
        if (fs == null) {
            Principal p = s.getAttribute(PRINCIPAL);
            if (p == null) {
                throw new IllegalStateException("no authenticated user");
            }
            fs = provider.newFileSystem(p, clientIp(s));
            s.setAttribute(FILE_SYSTEM, fs);
        }
        return fs;
    }

    static String clientIp(ServerSession s) {
        String proxied = s.getAttribute(ProxyProtocolAcceptor.CLIENT_IP);
        if (proxied != null) {
            return io.fdrop.core.util.TrustedProxies.normalise(proxied);
        }
        return io.fdrop.core.util.TrustedProxies.normalise(
                ((InetSocketAddress) s.getClientAddress()).getAddress().getHostAddress());
    }

    public SftpServer start() throws IOException {
        sshd.start();
        log.info("SFTP listening on {}:{}", app.config.sftp.bind, port());
        return this;
    }

    public int port() {
        return sshd.getBoundAddresses().stream().filter(a -> a instanceof InetSocketAddress)
                .map(a -> ((InetSocketAddress) a).getPort()).findFirst().orElse(sshd.getPort());
    }

    public KeyPairProvider hostKeys() {
        return sshd.getKeyPairProvider();
    }

    // ---- authentication ----

    private boolean admit(ServerSession session, Principal p, String method) {
        if (!p.canUseFiles()) {
            app.metrics.sftpAuth(method, false);
            return false;
        }
        AtomicInteger n = sessionsPerUser.computeIfAbsent(p.sub(), k -> new AtomicInteger());
        if (n.get() >= app.config.sftp.maxSessionsPerUser) {
            log.info("SFTP login for {} refused: {} sessions already open", p.username(), n.get());
            app.metrics.sftpAuth(method, false);
            return false;
        }
        app.accounts.ensure(p);
        session.setAttribute(PRINCIPAL, p);
        app.metrics.sftpAuth(method, true);
        Audit.log("login", "user", p.username(), "ip", clientIp(session), "protocol", "SFTP", "method", method);
        return true;
    }

    /** True when this user signs in with two factors, so a password alone is not enough. */
    private boolean needsTwoFactor(String username) {
        return app.twoFactor.findByUsername(username).map(app.twoFactor::needed).orElse(false);
    }

    private boolean password(String username, String password, ServerSession session) {
        try {
            if (needsTwoFactor(username)) {
                app.metrics.sftpAuth("password", false);
                Audit.log("login.failed", "user", username, "ip", clientIp(session), "protocol", "SFTP",
                        "reason", "two-factor required: use keyboard-interactive or an SSH key");
                return false;
            }
            Principal p = app.oidc.passwordLogin(username, password, null);
            if (p == null) {
                app.metrics.sftpAuth("password", false);
                Audit.log("login.failed", "user", username, "ip", clientIp(session), "protocol", "SFTP");
                return false;
            }
            return admit(session, p, "password");
        } catch (FdropException e) {
            log.warn("SFTP password check for {} failed: {}", username, e.getMessage());
            app.metrics.sftpAuth("password", false);
            return false;
        }
    }

    private boolean publicKey(String username, PublicKey key, ServerSession session) {
        try {
            String fp = MinaSshTools.fingerprint(key);
            Optional<SshKey> k = app.users.keyByFingerprint(username, fp);
            if (k.isEmpty()) {
                app.metrics.sftpAuth("publickey", false);
                return false;
            }
            Optional<User> u = app.users.find(k.get().userSub());
            if (u.isEmpty()) {
                return false;
            }
            Optional<Principal> active = app.userStatus.active(u.get().sub());
            if (active.isEmpty()) {
                app.metrics.sftpAuth("publickey", false);
                Audit.log("login.failed", "user", username, "ip", clientIp(session), "protocol", "SFTP",
                        "reason", "disabled in Keycloak");
                return false;
            }
            app.users.touchKey(k.get().id());
            return admit(session, active.get(), "publickey");
        } catch (FdropException e) {
            log.warn("SFTP key login for {} could not be checked: {}", username, e.getMessage());
            app.metrics.sftpAuth("publickey", false);
            return false;
        }
    }

    /**
     * Password, then a one-time code for users who need two factors. Both go to Keycloak's direct grant, whose
     * conditional OTP step checks the code. Users who need two factors but have no authenticator app (only a
     * security key), or when {@code auth.twoFactor.sftpPasswordWithCode} is off, are refused and use SSH keys.
     */
    private final class CodeAuthenticator implements KeyboardInteractiveAuthenticator {
        @Override
        public InteractiveChallenge generateChallenge(ServerSession session, String username, String lang,
                                                      String subMethods) {
            boolean wantsCode;
            try {
                Optional<Principal> who = app.twoFactor.findByUsername(username);
                wantsCode = who.map(app.twoFactor::needed).orElse(false);
                if (wantsCode && (!app.twoFactor.config().sftpPasswordWithCode
                        || !app.twoFactor.hasOtp(who.get().sub()))) {
                    app.metrics.sftpAuth("keyboard_interactive", false);
                    Audit.log("login.failed", "user", username, "ip", clientIp(session), "protocol", "SFTP",
                            "reason", "two-factor required and no one-time code available: use an SSH key");
                    return null;
                }
            } catch (FdropException e) {
                log.warn("SFTP two-factor check for {} failed: {}", username, e.getMessage());
                return null;
            }
            session.setAttribute(WANTS_CODE, wantsCode);
            InteractiveChallenge c = new InteractiveChallenge();
            c.setInteractionName("");
            c.setInteractionInstruction("");
            c.setLanguageTag("");
            c.addPrompt("Password: ", false);
            if (wantsCode) {
                c.addPrompt("Verification code: ", false);
            }
            return c;
        }

        @Override
        public boolean authenticate(ServerSession session, String username, List<String> responses) {
            boolean wantsCode = Boolean.TRUE.equals(session.getAttribute(WANTS_CODE));
            if (responses == null || responses.size() != (wantsCode ? 2 : 1)) {
                app.metrics.sftpAuth("keyboard_interactive", false);
                return false;
            }
            String code = wantsCode ? responses.get(1).trim() : null;
            if (wantsCode && code.isEmpty()) {
                app.metrics.sftpAuth("keyboard_interactive", false);
                return false;
            }
            try {
                Principal p = app.oidc.passwordLogin(username, responses.get(0), code);
                if (p == null) {
                    app.metrics.sftpAuth("keyboard_interactive", false);
                    Audit.log("login.failed", "user", username, "ip", clientIp(session), "protocol", "SFTP",
                            "method", "keyboard_interactive");
                    return false;
                }
                return admit(session, p, "keyboard_interactive");
            } catch (FdropException e) {
                log.warn("SFTP keyboard-interactive check for {} failed: {}", username, e.getMessage());
                app.metrics.sftpAuth("keyboard_interactive", false);
                return false;
            }
        }
    }

    /** Ends a user's SFTP sessions (user deleted or disabled). */
    public void terminate(String sub) {
        for (var s : sshd.getActiveSessions()) {
            if (s instanceof ServerSession ss) {
                Principal p = ss.getAttribute(PRINCIPAL);
                if (p != null && p.sub().equals(sub)) {
                    ss.close(true);
                }
            }
        }
    }

    private final class Sessions implements SessionListener {
        @Override
        public void sessionCreated(Session session) {
            app.metrics.sftpSessions().incrementAndGet();
        }

        @Override
        public void sessionClosed(Session session) {
            app.metrics.sftpSessions().decrementAndGet();
            Principal p = session.getAttribute(PRINCIPAL);
            if (p != null) {
                AtomicInteger n = sessionsPerUser.get(p.sub());
                if (n != null) {
                    n.decrementAndGet();
                }
            }
        }

        @Override
        public void sessionEvent(Session session, Event event) {
            if (event == Event.Authenticated) {
                Principal p = session.getAttribute(PRINCIPAL);
                if (p != null) {
                    sessionsPerUser.computeIfAbsent(p.sub(), k -> new AtomicInteger()).incrementAndGet();
                }
            }
        }
    }

    /** Opens files through the fdrop provider rather than {@code FileChannel}, and disables locking. */
    private static final class Accessor implements SftpFileSystemAccessor {
        @Override
        public SeekableByteChannel openFile(SftpSubsystemProxy subsystem, FileHandle fileHandle, Path file,
                                            String handle, Set<? extends OpenOption> options,
                                            FileAttribute<?>... attrs) throws IOException {
            return file.getFileSystem().provider().newByteChannel(file, options, attrs);
        }

        @Override
        public FileLock tryLock(SftpSubsystemProxy subsystem, FileHandle fileHandle, Path file, String handle,
                                Channel channel, long position, long size, boolean shared) {
            return null;
        }

        @Override
        public void syncFileData(SftpSubsystemProxy subsystem, FileHandle fileHandle, Path file, String handle,
                                 Channel channel) {
            // data is durable when the file is committed on close
        }
    }

    private final class Events implements SftpEventListener {
        @Override
        public void open(ServerSession session, String remoteHandle, org.apache.sshd.sftp.server.Handle localHandle) {
            if (localHandle instanceof FileHandle fh && !fh.getOpenOptions().contains(java.nio.file.StandardOpenOption.WRITE)
                    && !fh.getOpenOptions().contains(java.nio.file.StandardOpenOption.APPEND)) {
                Principal p = session.getAttribute(PRINCIPAL);
                Audit.log("download", "user", p == null ? null : p.username(), "ip", clientIp(session), "protocol",
                        "SFTP", "path", fh.getFile().toString());
            }
        }
    }

    @Override
    public void close() throws IOException {
        sshd.stop(true);
    }

    /** The file system a session sees, for tests. */
    FdropFileSystem fileSystemFor(Principal p) {
        return provider.newFileSystem(p, "127.0.0.1");
    }
}
