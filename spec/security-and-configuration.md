# Security and configuration

[fdrop specification](../SPEC.md)

## Security

- **HTTPS only, no plain HTTP anywhere.** Every port fdrop listens on speaks TLS 1.2+ (TLS 1.3 preferred): the web and API port (`http.port`, default 8443), the application mTLS port (8444) and the management port (8081). There is no setting that turns TLS off, no plain-HTTP listener, and no redirect from `http://`: a plain-HTTP request to any port fails in the TLS handshake. fdrop refuses to start without `http.tls.cert` and `http.tls.key` (PEM, hot reloaded on file change), naming the missing setting. HSTS (`max-age` one year, `includeSubDomains`) is on every response of the web port.
- A reverse proxy or load balancer in front of fdrop connects to it over HTTPS too (re-encrypting, as Traefik does in the Kubernetes deployment) or passes TCP straight through; it still sets `X-Forwarded-For` or PROXY protocol for the client address, believed only from `http.trustedProxies`. Headers claiming the original scheme (`X-Forwarded-Proto`) are ignored, since every request to fdrop is HTTPS.
- The application mTLS listener uses the same server certificate, accepts TLS 1.2+ only, requires a client certificate, and serves nothing but `/api/v1`.
- **No plain HTTP between services either.** fdrop calls Keycloak and its S3 store only over HTTPS: an `http://` `auth.oidc.issuer` or `storage.s3.endpointOverride` is refused at start. Where those services' certificates come from a private CA (the dev container, the Kubernetes deployment), `auth.oidc.trustedCas` and `storage.s3.trustedCas` name a PEM bundle trusted for those calls only, in addition to the JDK's own trust store, rather than replacing the JVM's trust store. The PostgreSQL connection was already TLS-only (Metadata DB connection).
- Metadata DB: PostgreSQL only over TLS with mutual certificate authentication (`verify-full` on fdrop's side, `hostssl … cert` on the server's); no DB password in production and fdrop refuses to start without the certificates (see Metadata DB connection).
- Path traversal: every path is normalised and checked in one place (`StoragePath.of`) before reaching any backend; names with NUL, `/` inside a segment, or longer than 255 bytes are rejected.
- Downloads always send `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff` so uploaded HTML cannot execute in the app's origin.
- Content-Security-Policy `default-src 'self'` on the UI.
- Max file size `limits.maxFileSize` (default 100 GiB) enforced at upload create and on stream length.
- Search never crosses access: every query is filtered to the caller's Home and folders, read from the DB per query, and every hit is checked again as the caller before it is returned; snippets are plain text; documents are parsed in a separate JVM with memory and time limits (see Search).
- Encryption at rest: every stored file, upload part and spool file is encrypted with its own AES-256 key, wrapped by a key-encryption key held in an environment secret, AWS KMS or Vault, never in storage; segments are authenticated, so tampering or truncation fails the read instead of returning altered data (see Encryption at rest).
- Audit log: every event is also kept in an append-only DB table, hash-chained per node so later edits show, readable and searchable only by admins with two factors, and every look at it is itself audited (see Audit log). It records logins, uploads, downloads, deletes, moves and expiry (set, changed, cleared, refused, expired) with user, IP, protocol, path and bytes.
- File expiry is a hard deadline: an expired file is refused on every protocol before its bytes are deleted. The `.fdrop-expires` suffix is reserved for SFTP control files and cannot be used as a file name.

## Configuration

One YAML file (`fdrop.yaml`), every key overridable by env var (`FDROP_STORAGE_TYPE`, `FDROP_STORAGE_S3_BUCKET`, …). `expiry.max`, `expiry.home.default` and `expiry.home.max` are expiries in the File expiry syntax (`10y`, `30 days`, `never` is written `null`); the other `expiry.*` timings use the config's usual duration format.

```yaml
http:
  port: 8443
  tls: { cert: /etc/fdrop/tls.crt, key: /etc/fdrop/tls.key }   # required: fdrop serves HTTPS only
  simpleUploadMax: 64MiB
  trustedProxies: []    # CIDRs of reverse proxies / load balancers whose Forwarded, X-Forwarded-For and PROXY headers are believed
  mtls:
    enabled: false      # listener for application accounts with client certificates
    port: 8444
management:
  port: 8081
  bind: 0.0.0.0
  metricsToken: ${FDROP_MANAGEMENT_METRICSTOKEN:-}   # empty = /metrics needs no token
  histogramBuckets: [0.005, 0.025, 0.1, 0.5, 1, 5, 30, 120, 600]
sftp:
  enabled: true
  port: 2222
  hostKeyPath: /var/lib/fdrop/ssh_host_ed25519_key
  spoolDir: /var/lib/fdrop/spool
  proxyProtocol: false  # accept HAProxy PROXY v1/v2 from http.trustedProxies, to record real client IPs behind a TCP load balancer
auth:
  oidc:
    issuer: https://sso.example.com/realms/fdrop   # https only
    trustedCas: null       # PEM bundle for a Keycloak certificate from a private CA; null = the JDK's trust store
    webClientId: fdrop-web
    webClientSecret: ${FDROP_AUTH_OIDC_WEBCLIENTSECRET}
    redirectUri: https://fdrop.example.com/auth/callback
    sftpClientId: fdrop-sftp
    sftpClientSecret: ${FDROP_AUTH_OIDC_SFTPCLIENTSECRET}
    apiAudience: fdrop-api
    userRole: fdrop-user
    adminRole: fdrop-admin
    appRole: fdrop-app
  userManagement:
    enabled: true        # false = admins see users read-only
  twoFactor:
    require: admins      # none | admins | all
    acr: 2fa             # ACR value Keycloak returns for a two-factor sign-in
    sftpPasswordWithCode: true   # false = users who need two factors use SSH keys only over SFTP
  session: { idle: 24h, absolute: 7d }
  applications:
    group: fdrop-applications   # Keycloak group whose members are application accounts (role mapping: appRole)
    passwordMaxAge: 365d        # 0 = passwords never expire
    expiryWarnings: [30d, 7d]   # log, audit and fdrop_app_credentials_expiring before a password or certificate expires
    mtls:
      trustedCas: /etc/fdrop/app-client-ca.pem   # PEM bundle; pinned certificates need no CA
      crls: []                  # optional CRL files or URLs
      proxyHeader: null         # e.g. X-Forwarded-Client-Cert, believed only from http.trustedProxies
forward:
  allowedHosts: []       # e.g. ["sftp.partner.example", "10.20.0.0/16"]; empty disables forward folders
  encryptionKey: ${FDROP_FORWARD_ENCRYPTIONKEY}   # 32-byte base64
  maxDestinationsPerFolder: 5   # a forward folder sends each file to 1..N destinations
  workers: 4
  perDestinationConcurrency: 2
  retryFor: 24h
  connectTimeout: 30s
  browse:                # destination tabs in forward folders, on destinations with Browsing on
    cacheFor: 30s        # how long a remote listing is reused
    maxEntries: 10000    # entries listed per directory
    maxConcurrent: 2     # retrievals at once per destination
    keepFor: 24h         # a retrieved copy is deleted this long after its last download
storage:
  type: s3            # local | s3
  local: { root: /var/lib/fdrop/data }
  s3:
    bucket: my-fdrop
    prefix: prod/
    region: eu-west-1
    endpointOverride: null   # e.g. https://s3.internal.example:9000; https only
    trustedCas: null         # PEM bundle for an S3 endpoint certificate from a private CA
    pathStyle: false
    sse: AES256
  encryption:                # see Encryption at rest
    enabled: true            # encrypt new files; existing encrypted files are always decrypted
    keyProvider: local       # local | aws-kms | vault
    local:
      keys: ${FDROP_STORAGE_ENCRYPTION_LOCAL_KEYS}   # "id:base64key,…", 32-byte keys, first is primary
    awsKms:
      keyId: null            # e.g. arn:aws:kms:eu-west-1:111122223333:key/…
      region: eu-west-1
    vault:
      address: null          # e.g. https://vault.internal:8200
      transitKey: fdrop
      tokenFile: /run/secrets/vault-token
    dekCache: { maxEntries: 10000, ttl: 10m }
    allowPlaintextReads: false   # true only while migrating a store that holds unencrypted files
    reencrypt:
      bytesPerSecond: 50MiB  # per node
audit:                       # see Audit log
  retention: 400d            # events older than this are pruned from the DB (the log files follow logging config)
  bufferSize: 10000          # events held while the DB is unreachable
  indexDir: /var/lib/fdrop/audit-index   # separate from search.indexDir; encrypted with the storage KEK when encryption is on
  exportMax: 100000
  verifyInterval: 24h        # background chain check
search:                      # see Search
  enabled: true
  indexDir: /var/lib/fdrop/search   # local disk on each node; encrypted with the storage KEK when encryption is on
  includeForwardFolders: false
  commitInterval: 5s
  feedRetention: 7d          # older change rows are pruned; a node down longer rebuilds
  maxResults: 1000
  rateLimitPerMinute: 60     # per user
  auditQueries: false        # true writes query text to the audit log
  extract:
    isolation: fork          # fork (a separate JVM per batch) | inprocess (tests only)
    workers: 2               # per node
    forkHeap: 512m
    timeout: 60s
    maxFileSize: 100MiB      # larger files are found by name only
    maxTextPerFile: 20MiB
upload:
  chunkSize: 8MiB
  ttl: 24h
  maxParallelChunks: 3
  historyRetention: 365d   # My uploads rows
expiry:
  enabled: true            # false = no new expiries can be set and the UI hides its controls; existing expiries still run
  max: 10y                 # longest expiry anyone can set, and the cap on folder and home settings (at most 100y)
  home:
    default: null          # expiry for /home uploads when the user has set no default; null = never
    max: null              # longest expiry in any /home; when set, every /home file expires
  sweepInterval: 30s       # how often each node looks for due files
  batchSize: 500           # files claimed per sweep
  claimTimeout: 5m         # a claim not finished by then is taken over by another node
  maxDeletesPerSecond: 200 # per node, when working through a backlog
  sftp:
    suffix: .fdrop-expires # control file suffix; reserved on every protocol
    pendingFor: 10m        # how long a control file written before its file is kept
db:
  url: jdbc:postgresql://db.example.com:5432/fdrop   # or jdbc:h2:file:/var/lib/fdrop/meta for a single node
  username: fdrop           # must match the client certificate's CN (or its pg_ident.conf mapping)
  password: null            # not used with client certificates
  tls:
    mode: verify-full       # verify-full (default for PostgreSQL) | disable (explicit opt-out, logged as a warning)
    ca: /etc/fdrop/db/ca.crt          # PEM: the CA that signed the DB server's certificate
    cert: /etc/fdrop/db/client.crt    # PEM: fdrop's client certificate
    key: /etc/fdrop/db/client.key     # PEM, PKCS#8 (BEGIN PRIVATE KEY): its private key
limits:
  maxFileSize: 100GiB
  defaultQuota: 50GiB
  defaultAppQuota: 10GiB
```

### Metadata DB connection

In production the metadata DB is PostgreSQL, and fdrop reaches it only over TLS with mutual authentication: the server proves itself with a certificate fdrop checks, and fdrop proves itself with a client certificate instead of a password. The dev container connects the same way (see Dev container), so a misconfigured certificate, CA or `pg_hba.conf` shows up while developing, not at the first deploy.

- **fdrop's side.** With a `jdbc:postgresql:` URL, `db.tls.mode` defaults to `verify-full`: the server's certificate must chain to `db.tls.ca` and name the URL's host in its SAN. fdrop presents `db.tls.cert` and `db.tls.key`. The three files are PEM, like `http.tls`, so one set of tooling issues every certificate; the key must be unencrypted PKCS#8 (`BEGIN PRIVATE KEY`), and a traditional `EC`/`RSA PRIVATE KEY` is refused with the `openssl pkcs8 -topk8` command that converts it. pgJDBC cannot read PEM keys, so fdrop passes its own `sslfactory` (`io.fdrop.core.db.PemSslFactory`) that builds the TLS context from the files, with TLS 1.2 as the minimum and TLS 1.3 preferred; any `ssl*` parameters in `db.url` are refused at startup so there is one place to configure it.
- **Refusing to run without it.** fdrop will not start with a PostgreSQL URL and no `db.tls.ca`, `cert` and `key`, or with files it cannot read or a key that does not match the certificate; the message names the setting. `db.tls.mode: disable` is the only way to connect without TLS, and logs a warning at every start. `db.password` may be left set for a server that asks for both (`cert` plus `scram-sha-256`); with `verify-full` it only ever travels inside TLS. With H2 the `db.tls` settings are ignored.
- **Rotation.** The files are read for every new connection, and Hikari's `maxLifetime` (30 min) retires old ones, so a renewed certificate is used within 30 minutes without a restart. Renew before the old one expires: `fdrop_db_client_cert_expiry_seconds` reports how long the current one has left and `FdropDbClientCertExpiring` warns 14 days ahead.
- **The server's side** (operated by the DBA, not fdrop; the dev container's files are the reference): `ssl = on`, `ssl_min_protocol_version = 'TLSv1.2'`, `ssl_ca_file` set to the CA that issues client certificates, and a `pg_hba.conf` with only `hostssl … cert` lines for fdrop's user, so plain TCP and password-only logins are refused. With `cert`, the certificate's CN must equal the DB user name (`fdrop`), or be mapped to it in `pg_ident.conf`. The migration's suggested maintenance role (Audit log) authenticates the same way with its own certificate.
- **Readiness.** A failed handshake (expired or revoked certificate, wrong CA, wrong host name) makes `/readyz` return 503 like any DB outage; the log line says which side refused and why, without printing key material.
