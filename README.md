<p align="center"><img src="fdrop-web/src/assets/logo/fdrop-logo.svg" width="96" alt=""></p>

# fdrop

fdrop lets people upload and download files through a browser or any SFTP client, with every file stored in a
pluggable backend: the local file system or Amazon S3 (and S3-compatible stores). Browser uploads are chunked and
resume after a dropped connection. Users keep private files in **Home**, share files in **shared folders** with
members they choose, and drop files into **forward folders** that fdrop relays to a partner's SFTP server. Other
systems use **application accounts**, which sign in with a client certificate, a password or an SSH key and join
folders as members; the `fdrop` command line client uploads with one. Sign-in is through Keycloak.

The full specification is in [SPEC.md](SPEC.md).

## Layout

| Module | What it holds |
| --- | --- |
| `fdrop-core` | Storage SPI, config, metadata DB (Flyway, H2/PostgreSQL), Keycloak client, all domain services, metrics |
| `fdrop-storage-local` | Local file system backend |
| `fdrop-storage-s3` | S3 backend (AWS SDK v2, CRT client, native multipart) |
| `fdrop-http` | Netty HTTP server: REST API, browser login, static UI, management port |
| `fdrop-sftp` | Apache MINA SSHD SFTP server, per-user virtual file system, forward workers |
| `fdrop-web` | React + TypeScript UI, built with npm (Vite) and packaged as static resources |
| `fdrop-server` | `Main`, the shaded fat jar and the container image (Jib) |
| `fdrop-cli` | `fdrop`, the command line client: `fdrop-cli.jar` and a release `.tar.gz` |
| `fdrop-http-client-java` | A Java client library for the REST API; uploads are resumable and chunked underneath |
| `fdrop-http-client-dotnet` | The same client for .NET 10 (NuGet package `Fdrop.Client`), built by Maven with the dotnet CLI |

`deploy/` has an example production config, Prometheus scrape config and alert rules, a Grafana dashboard, a
Quadlet unit and a local demo Compose file.

## Developing

The supported environment is the dev container (`.devcontainer/`), run with Podman 5 and `podman-compose`. In VS
Code set `dev.containers.dockerPath` to `podman` and `dev.containers.dockerComposePath` to `podman-compose`, then
"Reopen in Container". IntelliJ IDEA works too: set its Docker connection to Podman (on macOS and Windows, the
machine's rootful connection, `podman-machine-default-root`) and Docker Compose to a `docker-compose` v2 binary. It
starts Keycloak (with the dev realm), Prometheus and a throwaway SFTP destination beside the workspace.

```bash
./mvnw verify                                  # build everything and run all tests
java -jar fdrop-server/target/fdrop-server.jar # start fdrop with .devcontainer/fdrop.dev.yaml
```

The integration tests that need containers (S3 and KMS on LocalStack, among others) start them through the host's
Podman socket. They run beside the dev container, so the dev container is set up to reach them at
`host.containers.internal` (`TESTCONTAINERS_HOST_OVERRIDE` in `compose.yaml`). Recreate the dev container after
pulling this change; until then those tests are skipped.

Then open https://localhost:8443. fdrop serves HTTPS only, with a development certificate the dev container's
`certs` step creates when the stack starts (see [Development certificates](#development-certificates)); your
browser warns about it until you trust the dev CA. Dev users (development only):

| User | Password | Roles |
| --- | --- | --- |
| admin | admin | standard, admin |
| jugash | jugash | standard |
| chris | chris | standard |
| app-demo | app-demo | application account |

The Keycloak admin console is http://localhost:8180/admin (admin / admin), Prometheus is http://localhost:9090, and
SFTP is `sftp -P 2222 jugash@localhost`.

### Development certificates

Both Compose stacks start with a one-shot `certs` service that runs [scripts/dev-certs.sh](scripts/dev-certs.sh)
(OpenSSL in an Alpine container) and writes, into a git-ignored `certs/` folder next to the Compose file
(`.devcontainer/certs/` or `deploy/compose/certs/`):

| File | What it is |
| --- | --- |
| `ca.crt`, `ca.key` | The dev CA. fdrop trusts it for application client certificates, and Keycloak for its calls to fdrop |
| `server.crt`, `server.key` | The `localhost` certificate for fdrop's HTTPS (8443) and mTLS (8444) ports, and the Vite dev server |
| `app-demo.crt`, `app-demo.key` | A client certificate for the `app-demo` application account (SAN `DNS:app-demo.fdrop.local`) |

Existing certificates are kept, and remade a week before they expire; delete the folder to start over. To stop the
browser warning, trust `ca.crt` (on macOS: `security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db
.devcontainer/certs/ca.crt`), or accept the warning once per browser. For `curl`, pass `--cacert` with it. These keys
are for development only and are readable by everyone.

### Trying application accounts

As admin, open **Admin: applications** to create accounts and register their certificates and SSH keys. The dev
realm has one already, `app-demo`, in the `fdrop-applications` group, and the dev config enables the mTLS listener on
8444 with the development certificates:

```bash
curl --cacert .devcontainer/certs/ca.crt -u app-demo:app-demo https://localhost:8443/api/v1/me
```

That first sign-in creates the account in fdrop.

Then register the SAN `DNS:app-demo.fdrop.local` on `app-demo` in the admin screen, and upload with the CLI:

```bash
java -jar fdrop-cli/target/fdrop-cli.jar upload README.md /home/ --url https://localhost:8444 \
  --cert .devcontainer/certs/app-demo.crt --key .devcontainer/certs/app-demo.key --ca .devcontainer/certs/ca.crt
```

For UI work, `cd fdrop-web && npm run dev` serves the UI on https://localhost:5173 with hot reload, using the dev
certificate, and proxies the API to fdrop on 8443 (start fdrop with
`FDROP_AUTH_OIDC_REDIRECTURI=https://localhost:5173/auth/callback`).

### Without the dev container

If your machine has no JDK 21, `scripts/podman-mvn.sh` runs the same Maven build in a Java 21 container and passes
Podman's socket through for the Testcontainers integration tests:

```bash
scripts/podman-mvn.sh verify
```

### Running with Podman from the command line

You need Podman 5 and a Compose provider: `podman compose` runs whichever of `podman-compose` or `docker-compose`
(v2) is installed. On macOS and Windows, use a rootful Podman machine:

```bash
podman machine init --rootful
```

```bash
podman machine start
```

The stacks use Compose `depends_on` conditions (so the certificates exist before anything uses them), which
`docker-compose` v2 supports; they were tested with it. There are two ways to run fdrop. Both use ports 8443, 8180, 8444 and 2222 on the host, so stop one before starting
the other, and stop any dev container an IDE has open.

#### The packaged image, with Keycloak

The quickest way to try fdrop: the container image, Keycloak with the dev realm, and local storage in a volume,
from [deploy/compose/compose.yaml](deploy/compose/compose.yaml). No JDK is needed on the host.

1. Build the image as a tar file (in a Java 21 container) and load it into Podman as `localhost/fdrop:0.1.0-SNAPSHOT`:

   ```bash
   scripts/podman-mvn.sh -pl fdrop-server -am package jib:buildTar -DskipTests
   ```

   ```bash
   podman load -i fdrop-server/target/jib-image.tar
   ```

2. Start the stack. The `certs` step creates the dev certificates in `deploy/compose/certs/` first (see
   [Development certificates](#development-certificates)), then Keycloak and fdrop start:

   ```bash
   podman compose -f deploy/compose/compose.yaml up -d
   ```

3. Wait for fdrop to log `fdrop started` (Keycloak takes 20–30 seconds the first time):

   ```bash
   podman compose -f deploy/compose/compose.yaml logs -f fdrop
   ```

4. Open https://localhost:8443 and sign in as one of the dev users listed above. SFTP is on port 2222, the application mTLS
   listener on 8444, and `curl http://localhost:8081/readyz` reports readiness.

After a code change, repeat step 1, then recreate just fdrop:

```bash
podman compose -f deploy/compose/compose.yaml up -d --force-recreate fdrop
```

To stop everything, keeping uploaded files and fdrop's database in the `fdrop-data` volume:

```bash
podman compose -f deploy/compose/compose.yaml down
```

Add `-v` to `down` to delete that volume as well. Keycloak keeps nothing between runs: it imports the dev realm
each time it starts.

#### The dev stack, without an IDE

The same containers an IDE starts from `.devcontainer/` (the workspace container with JDK 21 and Maven, Keycloak,
Prometheus and the `sftp-target` SFTP server), with fdrop built and run inside the workspace container.
[.devcontainer/compose.host-ports.yaml](.devcontainer/compose.host-ports.yaml) publishes their ports on the host,
which an IDE would otherwise do. `-p fdrop-dev` keeps this stack apart from an IDE's.

1. Start the stack. The `certs` step creates the dev certificates in `.devcontainer/certs/` first:

   ```bash
   podman compose -p fdrop-dev -f .devcontainer/compose.yaml -f .devcontainer/compose.host-ports.yaml up -d
   ```

2. Open a shell in the workspace container:

   ```bash
   podman compose -p fdrop-dev -f .devcontainer/compose.yaml exec dev bash
   ```

3. Inside it, build fdrop (the first build downloads dependencies into the `fdrop-m2` volume):

   ```bash
   cd /workspaces/fdrop && ./mvnw -B -ntp package -DskipTests
   ```

4. Still inside it, set the environment an IDE would set from `devcontainer.json`, then start fdrop:

   ```bash
   export FDROP_CONFIG=.devcontainer/fdrop.dev.yaml FDROP_AUTH_OIDC_WEBCLIENTSECRET=fdrop-web-dev-secret FDROP_AUTH_OIDC_SFTPCLIENTSECRET=fdrop-sftp-dev-secret FDROP_LOG_FORMAT=PLAIN
   ```

   ```bash
   java -jar fdrop-server/target/fdrop-server.jar
   ```

5. Open https://localhost:8443. Ctrl-C stops fdrop; build and start it again after a change. Uploaded files and
   fdrop's database are in `data/` in the workspace.

To stop the stack (add `-v` to also delete the Maven cache volume):

```bash
podman compose -p fdrop-dev -f .devcontainer/compose.yaml -f .devcontainer/compose.host-ports.yaml down
```

For a production-like single container under systemd on Linux, see the Quadlet unit in
[deploy/quadlet/fdrop.container](deploy/quadlet/fdrop.container).

### Trying two-factor sign-in

The dev config sets `auth.twoFactor.require: none`, so the dev users sign in with just a password. Any of them
can turn two-factor on from the Account page. To try the production default, where admins must use it, start fdrop
with `FDROP_AUTH_TWOFACTOR_REQUIRE=admins`. At admin's next sign-in Keycloak asks them to set up an authenticator
app; "Unable to scan?" on that page shows the secret as text for an app or a TOTP script. Recreating the Keycloak
container, or deleting the credential in its admin console, undoes it.

Over SFTP, a user with two-factor logs in with an SSH key, or with `keyboard-interactive`, which asks for the
password and then a code (`sftp -o PreferredAuthentications=keyboard-interactive -P 2222 admin@localhost`).

### Trying forward folders

1. Sign in as admin, open **Admin: destinations**, create a destination for host `sftp-target`, port 22, user
   `target`, directory `/inbox`, run **Test connection** and pin the host key.
2. Copy the destination's public key into `.devcontainer/sftp-target/authorized_keys` and recreate `sftp-target`
   (`podman-compose up -d --force-recreate sftp-target`): the image installs keys only when its container is
   first created, so a plain restart is not enough.
3. Assign the destination to a user; as that user, create a forward folder and drop a file into it.

### Trying file expiry

- In the browser, pick an **Expires** value above the drop zone before dropping files, or use **Set expiry…** on a
  file. A shared folder's owner sets a default and a maximum in its settings; your Home default is on the Account
  page.
- Over HTTP: `curl -H 'X-Fdrop-Expires-In: 6 days' -T report.pdf 'https://localhost:8443/api/v1/files/content?path=/home/report.pdf'`
  (with a bearer token or an application account), or `"expiresIn": "6 days"` when starting a resumable upload.
- With the CLI: `fdrop upload nightly.csv /home/ --expires-in 2weeks`.
- Over SFTP, upload a control file holding the expiry, then the file:

  ```text
  sftp> put report.pdf.fdrop-expires     # a local file containing "6 days"
  sftp> put report.pdf
  sftp> get report.pdf.fdrop-expires /dev/stdout
  ```

Expired files disappear at once and are deleted by the sweeper within about `expiry.sweepInterval` (30 s).

### Trying fdrop watch

Register `.devcontainer/certs/app-demo.crt` on the `app-demo` account (Admin: Applications), then watch a folder
and drop files into it:

```bash
mkdir -p /tmp/outbox
java -jar fdrop-cli/target/fdrop-cli.jar watch /tmp/outbox /home/ --settle 2s --url https://localhost:8444 \
    --cert .devcontainer/certs/app-demo.crt --key .devcontainer/certs/app-demo.key --ca .devcontainer/certs/ca.crt
```

Each file appears in `app-demo`'s home a couple of seconds after it stops changing; Ctrl-C stops the watcher.

### Trying search

Type in the search box at the top: matching names from everywhere you can see appear as you type, and Enter
searches names and contents. The index lives in `data/search` and catches up with new uploads within a few seconds;
**Admin: Search index** shows how far behind it is and can rebuild it. Text is read from documents by Apache Tika in a
separate short-lived JVM per file.

### The dev database (PostgreSQL over mTLS)

The dev container's metadata DB is the `postgres` service, reached the way production reaches it: TLS with
`verify-full` and a client certificate (`.devcontainer/certs/fdrop-db.crt`, `CN=fdrop`) instead of a password. Plain
TCP and password logins are refused by `.devcontainer/postgres/pg_hba.conf`. To look inside:

```bash
psql "host=localhost dbname=fdrop user=fdrop sslmode=verify-full sslrootcert=.devcontainer/certs/ca.crt sslcert=.devcontainer/certs/fdrop-db.crt sslkey=.devcontainer/certs/fdrop-db.key"
```

`psql` refuses a world-readable key; copy it with `install -m 600 .devcontainer/certs/fdrop-db.key /tmp/fdrop-db.key`
and point `sslkey` there. Data lives in the `devcontainer_fdrop-pgdata` volume. Earlier dev data in H2
(`data/meta`) is not migrated, so after switching delete `data/` to start clean; the unit tests still use in-memory
H2, and `PostgresTlsIT` starts its own PostgreSQL with the same settings.

### Trying the audit log

Sign in as an admin with two factors and open **Admin: Audit log**. Every event since the database was created is
there, newest first; sign in from another browser and it appears on the next refresh, or tick **Follow live**. Try
`outcome:refused`, `group:sign-in ip:10.0.0.0/8` or `path:/shared -event:download` in the search box. The audit
index lives in `data/audit-index`, separate from the file search index, and every look at the page is itself
recorded as an `audit.viewed`, `audit.searched` or `audit.exported` event.

### Encryption at rest

Every stored file is encrypted (see SPEC.md, Encryption at rest). The dev config has a fixed development key and
`allowPlaintextReads: true`, so files stored in `data/files` before encryption was added are still served. To
encrypt them, open **Admin: Encryption**, press **Count objects now**, then **Encrypt** on the "Not encrypted"
row. For anything real, make a key with `echo "k1:$(openssl rand -base64 32)"` and pass it as
`FDROP_STORAGE_ENCRYPTION_LOCAL_KEYS`; fdrop refuses to start with encryption on and no working key.

## Java client library

`fdrop-http-client-java` lets a Java application use fdrop's REST API without knowing its upload protocol. It
depends only on the JDK and Jackson.

```java
try (FdropClient fdrop = FdropClient.builder()
        .baseUrl("https://fdrop.example.com:8444")              // the application mTLS port
        .clientCertificate(Path.of("app.crt"), Path.of("app.key"), Path.of("ca.crt"))
        .maxAttempts(5)                                          // per request, and per chunk
        .build()) {
    FileEntry f = fdrop.upload(Path.of("export.csv"), "/shared/Finance/",
            UploadOptions.DEFAULTS.withExpiresIn("30 days"));
}
```

`upload` creates an upload session, sends the file in the server's chunk size (three chunks at a time), retries
each failed chunk, and completes the session with the whole file's SHA-256. A request that keeps failing with a
network error, timeout, `429` or `5xx` throws `FdropRetriesExhaustedException` after `maxAttempts` tries, and the
unfinished session is discarded. Refusals (`403`, `404`, `409`, a full quota) throw `FdropApiException` at once with
fdrop's problem type and detail. People and scripts can use `.bearerToken(() -> token)` instead, and application
accounts with a password `.basicAuth(user, password)`. `InputStream` uploads need the size up front; each chunk is
held in memory while it is sent.

## .NET client library

`fdrop-http-client-dotnet` is the C# port of the Java client, packaged as `Fdrop.Client`; see its
[README](fdrop-http-client-dotnet/README.md). `./mvnw verify` builds it with the .NET 10 SDK (in the dev container) and
runs its tests against a real fdrop server; `-Ddotnet.skip` skips it on machines without the SDK.

## Configuration

`fdrop.yaml` (or `$FDROP_CONFIG`); every key can be overridden with an environment variable named after its path,
e.g. `FDROP_STORAGE_S3_BUCKET` or `FDROP_AUTH_OIDC_WEBCLIENTSECRET`. See
[deploy/fdrop.example.yaml](deploy/fdrop.example.yaml) for every setting.

## Ports

| Port | Purpose |
| --- | --- |
| 8443 (8080 if `http.tls` is off) | Browser UI, REST API, sign-in; HTTPS only in the dev and demo stacks |
| 2222 | SFTP |
| 8444 | Application accounts' client certificates (mTLS): REST API only, when `http.mtls.enabled` |
| 8081 | Management: `/metrics`, `/healthz`, `/readyz` (never publish to the internet) |

## Differences from the spec

- S3 integration tests use LocalStack: MinIO no longer publishes container images.
- The `upload_record` column the spec calls `current_path` is `path_now` (`CURRENT_PATH` is reserved in H2).
- The upload `complete` call also accepts the whole-file SHA-256, because the browser can only compute it while
  sending chunks.
- SFTP resume copies the existing file into the new upload rather than using S3's server-side part copy, and
  out-of-order SFTP writes are accepted within a 16 MiB window.
- Browser sessions are held in memory: a restart signs users out, and several nodes need sticky sessions.
- Search: text is extracted in a separate JVM that fdrop starts itself (`ExtractMain`, with `-Xmx` and a time
  limit) rather than through Tika's `ForkParser`; the forked JVM is not cut off from the network. Tika is limited to
  the parser modules for the formats the spec lists, not its whole standard package. A `429` from the search API
  carries no `Retry-After` header. Each node rebuilds and searches its own index; there is no rolling rebuild.
- Encryption at rest: SFTP spool files are not written at all yet (out-of-order writes are held in memory), so
  there is nothing to encrypt there. Several encrypted reads of the same object each fetch its header once per
  node until it is cached; nothing is shared between nodes.
- File expiry: the sweeper claims due rows with a conditional `UPDATE` (as forward workers claim jobs) rather than
  `SELECT … FOR UPDATE SKIP LOCKED`, and uses the nodes' clocks rather than the database's, since every timestamp
  in the schema is epoch milliseconds written by fdrop. S3 objects are deleted one `DeleteObject` at a time, after a
  `HeadObject` for the quota, not in `DeleteObjects` batches.
- Audit log: each row's hash covers the previous hash, node, `node_seq`, id, time and the exact log line (which holds
  every field); the columns shown in the table are derived from that line and checked against it by **Verify
  chain**. The `REVOKE`/`GRANT` for an append-only DB user are in a comment in `V6__audit_log.sql` for operators to
  run, not run by the migration. `audit.viewed` is recorded once per admin per 5 minutes, so **Follow live** does not
  fill the log with it. Several groups or outcomes are passed comma separated (`group=Sign-in,Files`). On phones the
  filters wrap above the list instead of sitting in a sheet. Every node runs the nightly prune and the chain check;
  a second run finds nothing to do. An export over `audit.exportMax` is refused with `413`
  (`audit-export-too-large`).
- Metadata DB TLS: the client key must be PKCS#8 (`openssl pkcs8 -topk8 -nocrypt` converts a traditional one), since
  fdrop reads it without BouncyCastle. The PostgreSQL integration tests cover the TLS connection and the audit chain;
  the two-node expiry sweeper test on PostgreSQL is still to be written.
- Java client: an upload that gives up is not resumed by a later call (unlike `fdrop-cli`, which saves its session);
  the client discards the session and the next upload starts again. Admin endpoints and SSH key and two-factor
  management are not in the client.
- .NET client: `Me` is `Account` and `MoveAsync` takes `source` and `destination` (Visual Basic keywords are flagged in
  a public .NET API). The tests run on VSTest rather than xUnit's newer test platform, for `dotnet test --filter` and
  coverlet.
- `fdrop watch`: `SIGTERM` and `SIGINT` are caught through `sun.misc.Signal` (looked up reflectively, as the JDK has no
  public API for it), so a stop exits 0 after in-flight uploads finish; on a JDK without it a stop simply ends the
  process, and unfinished uploads still resume at the next start. `--include` and `--ignore` also accept
  comma-separated lists, so the config file can set several. The watch integration tests leave resuming and
  connection drops to the `upload` tests and the fake-uploader unit tests (see SPEC.md, Command line client, Tests).
- Client libraries: `FileEntry.isDirectory` (Java) and `IsDirectory` (.NET) now treat every type but `FILE` as a
  folder; they had looked for a `DIRECTORY` type that fdrop never sends (it sends `DIR`, `AREA`, `SHARED_FOLDER`
  and `FORWARD_FOLDER`).
