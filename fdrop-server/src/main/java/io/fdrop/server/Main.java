package io.fdrop.server;

import io.fdrop.core.Fdrop;
import io.fdrop.core.config.ConfigLoader;
import io.fdrop.core.config.Durations;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.http.HttpServer;
import io.fdrop.http.ManagementServer;
import io.fdrop.sftp.ForwardClient;
import io.fdrop.sftp.ForwardWorker;
import io.fdrop.sftp.MinaRemoteFiles;
import io.fdrop.sftp.MinaSshTools;
import io.fdrop.sftp.SftpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Starts fdrop: loads {@code fdrop.yaml} (or {@code $FDROP_CONFIG}), runs DB migrations, initialises the storage
 * backend, and starts HTTP, management, SFTP and the forward workers. On SIGTERM it stops accepting connections,
 * waits up to 30 s for transfers in flight, then exits.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        FdropConfig cfg = ConfigLoader.loadDefault();
        warnAboutDevSecrets(cfg);

        StorageBackend backend = StorageBackend.load(cfg);
        ForwardClient forwardClient = new ForwardClient(Durations.parse(cfg.forward.connectTimeout));
        Fdrop app = new Fdrop(cfg, backend, new MinaSshTools(forwardClient));
        app.browse.setRemote(new MinaRemoteFiles(forwardClient));

        app.janitor.start();
        app.expirySweeper.start();
        app.browse.start();
        app.encryptionService.start();
        app.search.start();
        app.audit.start();
        ManagementServer management = new ManagementServer(app).start();
        HttpServer http = new HttpServer(app).start();
        SftpServer sftp = cfg.sftp.enabled ? new SftpServer(app).start() : null;
        ForwardWorker forwarder = new ForwardWorker(app, forwardClient).start();
        log.info("fdrop started: storage={} http={} sftp={} management={}", backend.type(), cfg.http.port,
                cfg.sftp.enabled ? cfg.sftp.port : "off", cfg.management.port);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            log.info("shutting down: draining transfers for up to 30 s");
            forwarder.close();
            http.stop(Duration.ofSeconds(30));
            if (sftp != null) {
                try {
                    sftp.close();
                } catch (Exception e) {
                    log.warn("SFTP stop: {}", e.toString());
                }
            }
            forwardClient.close();
            management.close();
            try {
                app.close();
            } catch (Exception e) {
                log.warn("shutdown: {}", e.toString());
            }
            stopped.countDown();
        }));
        stopped.await();
    }

    static void warnAboutDevSecrets(FdropConfig cfg) {
        for (String secret : new String[]{cfg.auth.oidc.webClientSecret, cfg.auth.oidc.sftpClientSecret}) {
            if (secret != null && secret.endsWith("-dev-secret")) {
                log.warn("a Keycloak client secret ends in '-dev-secret': these are development credentials and"
                        + " must not be used in production");
                return;
            }
        }
    }
}
