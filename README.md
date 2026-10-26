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
- File expiry: the sweeper claims due rows with a conditional `UPDATE` (as forward workers claim jobs) rather than
  `SELECT … FOR UPDATE SKIP LOCKED`, and uses the nodes' clocks rather than the database's, since every timestamp
  in the schema is epoch milliseconds written by fdrop. S3 objects are deleted one `DeleteObject` at a time, after a
  `HeadObject` for the quota, not in `DeleteObjects` batches.
