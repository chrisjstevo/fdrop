package io.fdrop.core;

import io.fdrop.core.auth.ClientCertificates;
import io.fdrop.core.auth.KeycloakAdmin;
import io.fdrop.core.auth.OidcClient;
import io.fdrop.core.auth.TwoFactorService;
import io.fdrop.core.auth.UserStatusCache;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.db.AppAccountRepo;
import io.fdrop.core.db.Db;
import io.fdrop.core.db.DestinationRepo;
import io.fdrop.core.db.ExpiryRepo;
import io.fdrop.core.db.FolderRepo;
import io.fdrop.core.db.RecordRepo;
import io.fdrop.core.db.UploadRepo;
import io.fdrop.core.db.UserRepo;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.service.AccountService;
import io.fdrop.core.service.AppAccountService;
import io.fdrop.core.service.DestinationService;
import io.fdrop.core.service.ExpiryService;
import io.fdrop.core.service.ExpirySweeper;
import io.fdrop.core.service.FileService;
import io.fdrop.core.service.FolderService;
import io.fdrop.core.service.HostPolicy;
import io.fdrop.core.service.Janitor;
import io.fdrop.core.service.PathResolver;
import io.fdrop.core.service.SshTools;
import io.fdrop.core.service.UploadRecorder;
import io.fdrop.core.service.UploadService;
import io.fdrop.core.service.UserAdminService;
import io.fdrop.core.storage.InstrumentedStorage;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.util.SecretBox;

/**
 * The application context: every shared service, built once and handed to the HTTP and SFTP front ends. Neither
 * front end touches storage or the DB except through these.
 */
public final class Fdrop implements AutoCloseable {

    public final FdropConfig config;
    public final FdropMetrics metrics;
    public final Db db;
    public final StorageBackend storage;
    public final UserRepo users;
    public final FolderRepo folders;
    public final DestinationRepo destinations;
    public final UploadRepo uploads;
    public final RecordRepo records;
    public final ExpiryRepo expiryRepo;
    public final AppAccountRepo appAccountRepo;
    public final OidcClient oidc;
    public final KeycloakAdmin keycloak;
    public final UserStatusCache userStatus;
    public final TwoFactorService twoFactor;
    public final PathResolver resolver;
    public final UploadRecorder recorder;
    public final ExpiryService expiry;
    public final ExpirySweeper expirySweeper;
    public final FileService files;
    public final UploadService uploadService;
    public final FolderService folderService;
    public final DestinationService destinationService;
    public final UserAdminService userAdmin;
    public final AccountService accounts;
    public final AppAccountService appAccounts;
    public final HostPolicy hostPolicy;
    public final SshTools ssh;
    public final Janitor janitor;

    public Fdrop(FdropConfig config, StorageBackend backend, SshTools ssh) {
        this(config, backend, ssh, OidcClient::new);
    }

    /** With a custom OIDC client, for tests that run without Keycloak. */
    public Fdrop(FdropConfig config, StorageBackend backend, SshTools ssh,
                 java.util.function.BiFunction<FdropConfig.Oidc, FdropMetrics, OidcClient> oidcFactory) {
        this(config, backend, ssh, oidcFactory, java.time.Clock.systemUTC());
    }

    /** With a custom clock, for tests of time-dependent behaviour such as file expiry. */
    public Fdrop(FdropConfig config, StorageBackend backend, SshTools ssh,
                 java.util.function.BiFunction<FdropConfig.Oidc, FdropMetrics, OidcClient> oidcFactory,
                 java.time.Clock clock) {
        this.config = config;
        this.ssh = ssh;
        this.metrics = new FdropMetrics(config.management.histogramBuckets);
        this.db = new Db(config.db, metrics.registry());
        this.storage = new InstrumentedStorage(backend, metrics);
        this.users = new UserRepo(db);
        this.folders = new FolderRepo(db);
        this.destinations = new DestinationRepo(db);
        this.uploads = new UploadRepo(db);
        this.records = new RecordRepo(db);
        this.expiryRepo = new ExpiryRepo(db);
        this.appAccountRepo = new AppAccountRepo(db);
        this.oidc = oidcFactory.apply(config.auth.oidc, metrics);
        this.keycloak = new KeycloakAdmin(oidc, metrics);
        this.userStatus = new UserStatusCache(keycloak, config.auth.oidc);
        this.twoFactor = new TwoFactorService(config.auth.twoFactor,
                TwoFactorService.keycloak(keycloak, config.auth.oidc));
        this.resolver = new PathResolver(folders);
        this.recorder = new UploadRecorder(db, records, folders, metrics);
        this.expiry = new ExpiryService(config, expiryRepo, records, resolver, storage, metrics, clock);
        this.expirySweeper = new ExpirySweeper(config, db, expiryRepo, records, users, folders, storage, expiry,
                metrics, clock);
        this.files = new FileService(config, storage, resolver, users, folders, destinations, uploads, records,
                recorder, expiry);
        this.uploadService = new UploadService(config, storage, files, uploads, users, recorder, records, metrics);
        this.folderService = new FolderService(db, folders, destinations, users, records, storage, resolver, expiry);
        this.hostPolicy = new HostPolicy(config.forward.allowedHosts);
        this.destinationService = new DestinationService(destinations, folders, users, records, ssh,
                SecretBox.fromBase64(config.forward.encryptionKey), hostPolicy);
        this.userAdmin = new UserAdminService(config, keycloak, userStatus, twoFactor, users, folders, destinations,
                uploads, records, storage, folderService, appAccountRepo);
        this.appAccounts = new AppAccountService(config, db, appAccountRepo, users, userAdmin, twoFactor, oidc,
                new ClientCertificates(config.auth.applications.mtls), metrics,
                AppAccountService.keycloak(keycloak, userStatus, config));
        this.accounts = new AccountService(config, users, uploads, records, folders, destinations, resolver, ssh);
        this.janitor = new Janitor(config, db, uploads, uploadService, records, folders, destinations, users, storage,
                metrics, appAccounts);
        records.resetStuckSending(Long.MAX_VALUE);
    }

    @Override
    public void close() throws Exception {
        expirySweeper.close();
        janitor.close();
        storage.close();
        db.close();
    }
}
