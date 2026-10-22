<p align="center"><img src="fdrop-web/src/assets/logo/fdrop-logo.svg" width="96" alt=""></p>

# fdrop

fdrop lets people upload and download files through a browser or any SFTP client, with every file stored in a
pluggable backend: the local file system or Amazon S3 (and S3-compatible stores). Browser uploads are chunked and
resume after a dropped connection. Users keep private files in **Home**, share files in **shared folders** with
members they choose, and drop files into **forward folders** that fdrop relays to a partner's SFTP server. Sign-in
is through Keycloak.

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

`deploy/` has an example production config, Prometheus scrape config and alert rules, a Grafana dashboard, a
Quadlet unit and a local demo Compose file.

## Developing

The supported environment is the dev container (`.devcontainer/`), run with Podman 5 and `podman-compose`. In VS
Code set `dev.containers.dockerPath` to `podman` and `dev.containers.dockerComposePath` to `podman-compose`, then
"Reopen in Container". It starts Keycloak (with the dev realm), Prometheus and a throwaway SFTP destination beside
the workspace.

```bash
./mvnw verify                                  # build everything and run all tests
java -jar fdrop-server/target/fdrop-server.jar # start fdrop with .devcontainer/fdrop.dev.yaml
```

Then open http://localhost:8080. Dev users (development only):

| User | Password | Roles |
| --- | --- | --- |
| admin | admin | standard, admin |
| jugash | jugash | standard |
| chris | chris | standard |

The Keycloak admin console is http://localhost:8180/admin (admin / admin), Prometheus is http://localhost:9090, and
SFTP is `sftp -P 2222 jugash@localhost`.

For UI work, `cd fdrop-web && npm run dev` serves the UI on http://localhost:5173 with hot reload and proxies the
API to fdrop on 8080 (start fdrop with `FDROP_AUTH_OIDC_REDIRECTURI=http://localhost:5173/auth/callback`).

### Without the dev container

If your machine has no JDK 21, `scripts/podman-mvn.sh` runs the same Maven build in a Java 21 container and passes
Podman's socket through for the Testcontainers integration tests:

```bash
scripts/podman-mvn.sh verify
```

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

## Configuration

`fdrop.yaml` (or `$FDROP_CONFIG`); every key can be overridden with an environment variable named after its path,
e.g. `FDROP_STORAGE_S3_BUCKET` or `FDROP_AUTH_OIDC_WEBCLIENTSECRET`. See
[deploy/fdrop.example.yaml](deploy/fdrop.example.yaml) for every setting.

## Ports

| Port | Purpose |
| --- | --- |
| 8080 (8443 with TLS) | Browser UI, REST API, sign-in |
| 2222 | SFTP |
| 8081 | Management: `/metrics`, `/healthz`, `/readyz` (never publish to the internet) |

## Differences from the spec

- S3 integration tests use LocalStack: MinIO no longer publishes container images.
- The `upload_record` column the spec calls `current_path` is `path_now` (`CURRENT_PATH` is reserved in H2).
- The upload `complete` call also accepts the whole-file SHA-256, because the browser can only compute it while
  sending chunks.
- SFTP resume copies the existing file into the new upload rather than using S3's server-side part copy, and
  out-of-order SFTP writes are accepted within a 16 MiB window.
- Browser sessions are held in memory: a restart signs users out, and several nodes need sticky sessions.
