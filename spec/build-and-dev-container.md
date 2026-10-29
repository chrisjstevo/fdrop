# Tech stack and packaging

[fdrop specification](../SPEC.md)

| Area | Choice |
| --- | --- |
| Language / runtime | Java 21 (virtual threads for the storage I/O executor) |
| Build | Apache Maven 3.9 multi-module reactor: `fdrop-core` (SPI, auth, metadata), `fdrop-storage-local`, `fdrop-storage-s3`, `fdrop-http`, `fdrop-sftp`, `fdrop-web` (UI), `fdrop-search` (Lucene index and Tika extraction), `fdrop-server` (main), `fdrop-cli` (command line client), `fdrop-http-client-java` (Java client library), `fdrop-http-client-dotnet` (.NET client library, built with the .NET 10 SDK), `fdrop-kubernetes` (Kubernetes deployment in OpenTofu, checked and tested with `tofu`) |
| Web UI | React 19 + TypeScript responsive web app styled after lme.com, built with npm (Vite bundler, Vitest tests, CSS Modules, Radix UI, Nunito Sans) |
| Identity | Keycloak 26 over OpenID Connect; Nimbus OAuth 2.0 SDK + `nimbus-jose-jwt` in fdrop |
| HTTP | Netty 4.2 |
| SFTP | Apache MINA SSHD 2.x |
| S3 | AWS SDK for Java v2 (async, CRT client) |
| Encryption at rest | JDK `javax.crypto` (AES-256-GCM, HKDF and HMAC-SHA-256); AWS SDK v2 KMS client; Vault transit over `java.net.http` |
| Audit log | The same Lucene 10 jars plus `lucene-facet` for group and outcome counts, and core's `InetAddressPoint` for IP and CIDR filters; in `fdrop-search` beside file search, with its own index |
| Search | Apache Lucene 10 (core, analysis-common, queryparser) embedded; Apache Tika 3 (`tika-core` and the text, PDF, Microsoft, misc-office, HTML, mail and XML parser modules), run in a separate JVM for extraction |
| Metadata | JDBC + Flyway migrations; H2 embedded or PostgreSQL |
| Config | Jackson YAML + env overlay |
| Command line client | Java 21, picocli, `java.net.http.HttpClient`, Jackson; shaded jar |
| Logging / metrics | SLF4J + Logback (JSON); Micrometer with the Prometheus registry, served on management port 8081 |
| Distribution | Fat jar; OCI container image (distroless Java 21) run with Podman, rootless by default; an example Compose file with MinIO and Keycloak for local demos only (`podman-compose up`); and an example Quadlet unit (`deploy/quadlet/fdrop.container`) that runs fdrop under systemd on Linux hosts; and a reference Kubernetes deployment with PostgreSQL and Keycloak, applied with OpenTofu (`fdrop-kubernetes`, see Kubernetes deployment) |

## Maven build

Maven is the top-level build for the whole project; npm builds the React UI and is run by Maven, so one command still builds everything. `mvn verify` from the repo root must build every module, the UI and the fat jar, and run all tests.

- Root `pom.xml` is the parent (`packaging=pom`) listing the nine modules and pinning versions in `<dependencyManagement>` via the Netty, AWS SDK v2 and Testcontainers BOMs.
- Maven Wrapper (`mvnw`, `.mvn/wrapper/`) is committed so builds don't depend on a locally installed Maven.
- `maven-enforcer-plugin` requires Java 21 and Maven 3.9+, and bans duplicate classes and dependency convergence errors.
- `fdrop-web` uses `frontend-maven-plugin` to install pinned Node and npm versions, then run `npm ci`, `npm test` and `npm run build`; output lands in `target/classes/static` and is packaged as a jar the server depends on.
- `fdrop-server` builds the runnable fat jar with `maven-shade-plugin` (merging `META-INF/services` so `ServiceLoader` finds both storage backends). `fdrop-cli` builds its own shaded jar and release `.tar.gz`; it is not part of the server jar or image.
- Unit tests run under Surefire; Testcontainers and SFTP integration tests run under Failsafe (`*IT.java`) in the `verify` phase.
- The container image is built with Jib (`jib-maven-plugin`) as a standard OCI image, with no Containerfile and no container daemon: `jib:buildTar` writes an archive for `podman load`, and `jib:build` pushes straight to a registry, for the build machine's platform or, with `-Pall-platforms`, amd64 and arm64 (see "CPU architecture" below). Jib's Docker-daemon goal (`jib:dockerBuild`) is not used.

## Dev container

The repo ships `.devcontainer/devcontainer.json` so the project builds and tests identically in VS Code or any Dev Containers client, with nothing installed on the host but Podman 5 and `podman-compose`. Docker is not required or used anywhere.

The dev container is Compose based and runs under Podman, so Keycloak and PostgreSQL run beside the workspace container. Files live in `.devcontainer/`: `devcontainer.json`, `compose.yaml`, `fdrop.dev.yaml`, `keycloak/fdrop-realm.json`, `postgres/` (the server's `pg_hba.conf` and TLS settings) and the generated development certificates in `certs/`. (`dockerComposeFile` below is the Dev Containers spec's own property name; Podman reads the same file.)

```json
{
  "name": "fdrop",
  "dockerComposeFile": "compose.yaml",
  "service": "dev",
  "runServices": ["certs", "localhost", "dev", "keycloak", "postgres", "sftp-target", "sftp-target2", "prometheus"],
  "workspaceFolder": "/workspaces/fdrop",
  "features": {
    "ghcr.io/devcontainers/features/java:1": { "version": "none", "installMaven": "true", "mavenVersion": "3.9" },
    "ghcr.io/devcontainers/features/node:1": { "version": "22" },
    "ghcr.io/devcontainers/features/aws-cli:1": {},
    "ghcr.io/devcontainers/features/dotnet:2": { "version": "10.0" }
  },
  "forwardPorts": [8443, 8081, 8444, 2222, 5173, 8180, 5432, 9090, 9000, 9001],
  "portsAttributes": {
    "8443": { "label": "fdrop HTTPS", "protocol": "https" },
    "8081": { "label": "fdrop management (metrics)" },
    "8444": { "label": "fdrop application mTLS" },
    "2222": { "label": "fdrop SFTP" },
    "5173": { "label": "Vite dev server", "protocol": "https" },
    "8180": { "label": "Keycloak" },
    "5432": { "label": "PostgreSQL (TLS + client certificate)" },
    "9090": { "label": "Prometheus" },
    "9000": { "label": "MinIO API" },
    "9001": { "label": "MinIO console" }
  },
  "remoteEnv": {
    "FDROP_CONFIG": "/workspaces/fdrop/.devcontainer/fdrop.dev.yaml",
    "FDROP_AUTH_OIDC_ISSUER": "https://localhost:8180/realms/fdrop",
    "FDROP_AUTH_OIDC_WEBCLIENTSECRET": "fdrop-web-dev-secret",
    "FDROP_AUTH_OIDC_SFTPCLIENTSECRET": "fdrop-sftp-dev-secret",
    "FDROP_LOG_FORMAT": "PLAIN",
    "DOTNET_CLI_TELEMETRY_OPTOUT": "1",
    "DOTNET_NOLOGO": "1"
  },
  "updateRemoteUserUID": false,
  "postCreateCommand": "sudo chown vscode:vscode /home/vscode/.m2 /home/vscode/.nuget && sudo apt-get update -q && sudo apt-get install -y -q postgresql-client && sh scripts/install-opentofu.sh && ./mvnw -B -ntp dependency:go-offline && (cd fdrop-web && npm ci)",
  "customizations": {
    "vscode": {
      "extensions": [
        "vscjava.vscode-java-pack",
        "redhat.vscode-yaml",
        "ms-azuretools.vscode-containers",
        "dbaeumer.vscode-eslint",
        "esbenp.prettier-vscode"
      ]
    }
  }
}
```

```yaml
# .devcontainer/compose.yaml (run by podman-compose)
x-podman:
  in_pod: false                      # podman-compose's default pod rejects dev's userns_mode ("--userns and --pod cannot be set together")
services:
  certs:                             # one-shot: creates the dev CA and certificates in .devcontainer/certs/
    image: docker.io/library/alpine:3.22
    command: ["sh", "-c", "apk add -q --no-cache openssl openssh-keygen && sh /repo/scripts/dev-certs.sh /repo/.devcontainer/certs"]
    volumes:
      - ..:/repo:z
  localhost:                         # owns the shared network so dev can be recreated without the services joined to it
    image: registry.k8s.io/pause:3.10
  dev:
    image: mcr.microsoft.com/devcontainers/java:21
    command: sleep infinity
    network_mode: service:localhost
    userns_mode: keep-id             # rootless Podman: workspace files keep the host user's ownership
    security_opt:
      - label=disable                # SELinux (the Podman machine, Fedora, RHEL) otherwise blocks the Podman socket mounted below
    environment:
      JAVA_HOME: /usr/lib/jvm/msopenjdk-current                # the image's JDK; the java feature (version none, for Maven) points it at an SDKMAN JDK that isn't there
      DOCKER_HOST: unix:///var/run/docker.sock                  # Testcontainers uses Podman's Docker-compatible API
      TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: ${PODMAN_SOCKET:-/run/podman/podman.sock}
      TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED: "true"
      TESTCONTAINERS_HOST_OVERRIDE: host.containers.internal   # test containers publish their ports on the host, not on dev's localhost
    volumes:
      - ..:/workspaces/fdrop:z
      - fdrop-m2:/home/vscode/.m2
      - fdrop-nuget-home:/home/vscode/.nuget   # all of ~/.nuget: the package cache and NuGet.Config, both written by vscode
      - ${PODMAN_SOCKET:-/run/podman/podman.sock}:/var/run/docker.sock
      - /sys:/sys:ro                 # keep-id can't mount sysfs in localhost's network namespace (another user namespace)
    depends_on:
      certs: { condition: service_completed_successfully }
      postgres: { condition: service_healthy }
  postgres:                          # the metadata DB, reached only over TLS with a client certificate, as in production
    image: docker.io/library/postgres:17-alpine
    network_mode: service:localhost  # listens on localhost:5432, matching the server certificate's SAN
    environment:
      POSTGRES_USER: fdrop           # the DB user; equals the CN of fdrop's client certificate
      POSTGRES_DB: fdrop
      POSTGRES_HOST_AUTH_METHOD: trust   # only for initdb's local socket; pg_hba.conf below has no other trust line
    # Postgres refuses a world-readable key, so the dev keys are copied in with postgres-only permissions first
    entrypoint: ["sh", "-c", "install -d -o postgres -m 700 /var/lib/postgresql/tls && install -o postgres -m 600 /etc/fdrop/certs/postgres.key /var/lib/postgresql/tls/server.key && install -o postgres -m 644 /etc/fdrop/certs/postgres.crt /etc/fdrop/certs/ca.crt /var/lib/postgresql/tls/ && exec docker-entrypoint.sh postgres -c config_file=/etc/postgresql/postgresql.conf"]
    volumes:
      - fdrop-pgdata:/var/lib/postgresql/data
      - ./postgres/postgresql.conf:/etc/postgresql/postgresql.conf:ro,z
      - ./postgres/pg_hba.conf:/etc/postgresql/pg_hba.conf:ro,z
      - ./certs:/etc/fdrop/certs:ro,z
    healthcheck:
      test: ["CMD", "pg_isready", "-h", "/var/run/postgresql", "-U", "fdrop"]
      interval: 2s
      retries: 30
    depends_on:
      certs: { condition: service_completed_successfully }
  keycloak:
    image: quay.io/keycloak/keycloak:26.8.1
    command: ["start-dev", "--import-realm", "--http-enabled=false", "--https-port=8180",
              "--https-certificate-file=/etc/fdrop/certs/server.crt", "--https-certificate-key-file=/etc/fdrop/certs/server.key"]
    network_mode: service:localhost  # shares localhost with the dev container
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_HOSTNAME: https://localhost:8180
      KC_HEALTH_ENABLED: "true"
      KC_TRUSTSTORE_PATHS: /etc/fdrop/certs/ca.crt   # for back-channel logout calls to fdrop's HTTPS port
    volumes:
      - ./keycloak/fdrop-realm.json:/opt/keycloak/data/import/fdrop-realm.json:ro,z
      - ./certs:/etc/fdrop/certs:ro,z
    depends_on:
      certs: { condition: service_completed_successfully }
  prometheus:                        # scrapes fdrop's /metrics and evaluates the alert rules
    image: quay.io/prometheus/prometheus:v3.5.0
    network_mode: service:localhost  # reaches fdrop at localhost:8081
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro,z
      - ../deploy/prometheus/alerts.yml:/etc/prometheus/alerts.yml:ro,z
  sftp-target:                       # destination for trying forward folders
    image: localhost/fdrop-dev-sftp:1
    build: { context: ./sftp-server, dockerfile: Containerfile }   # OpenSSH on Alpine, amd64 and arm64 alike
    command: "target::1001::inbox"   # no password: public-key login only
    environment:
      HOST_KEY: /etc/fdrop/certs/sftp-target_ssh_host_ed25519_key   # fixed, so a pinned host key survives recreating
    volumes:
      - ./sftp-target:/home/target/.ssh/keys:ro,z   # every file is a key; README.pub keeps the folder non-empty
      - ./sftp-target-setup.sh:/etc/sftp.d/setup.sh:ro,z   # installs the host key, and new keys within 2 s
      - ./certs:/etc/fdrop/certs:ro,z
    depends_on:
      certs: { condition: service_completed_successfully }
  sftp-target2:                      # a second destination, for a forward folder sending to two servers
    image: localhost/fdrop-dev-sftp:1
    build: { context: ./sftp-server, dockerfile: Containerfile }
    command: "target::1001::inbox"
    environment:
      HOST_KEY: /etc/fdrop/certs/sftp-target2_ssh_host_ed25519_key
    volumes:
      - ./sftp-target2:/home/target/.ssh/keys:ro,z
      - ./sftp-target-setup.sh:/etc/sftp.d/setup.sh:ro,z
      - ./certs:/etc/fdrop/certs:ro,z
    depends_on:
      certs: { condition: service_completed_successfully }
volumes:
  fdrop-m2:
  fdrop-nuget-home:                  # ~/.nuget for fdrop-http-client-dotnet: packages and NuGet settings
  fdrop-pgdata:                      # the dev metadata DB, kept across container rebuilds
```

### Podman on macOS and Linux

The dev container runs under **rootless** Podman on every platform: Podman runs as your own user, and so does the dev container's `vscode` user (through `keep-id`). Rootful Podman is not used and does not work for the dev container. What differs between platforms is where Podman runs and whether SELinux is enforcing.

| | macOS and Windows | Linux |
| --- | --- | --- |
| Where Podman runs | In a Podman machine: a Fedora CoreOS VM that `podman machine` manages. Containers, images and volumes live inside it, and Podman forwards its API to the host | Directly on the host |
| Setup | `podman machine init` (rootless by default), then `podman machine start` | `systemctl --user enable --now podman.socket` |
| API socket the dev container mounts | `/run/user/<uid>/podman/podman.sock`, a path inside the VM | `$XDG_RUNTIME_DIR/podman/podman.sock` |
| SELinux | Enforcing (Fedora CoreOS) | Enforcing on Fedora and RHEL; not used on Ubuntu or Debian (AppArmor) |
| IDE Docker connection | The machine's default connection, `podman-machine-default` | Your user's Podman socket |

`initializeCommand` (`scripts/podman-socket-env.sh`) finds the socket on both, so nothing is set by hand.

**Why rootless, and why not rootful.** The dev container mounts Podman's API socket so that Testcontainers, running in it, can start the integration tests' containers. Two things decide whether it can use that socket:

- **File permissions.** The rootless socket is owned by your user and group, mode 660. `keep-id` maps your user and group ids to the same numbers in the container, and the image's `vscode` user is uid and gid 1000: that is the Podman machine user's group (`core`, uid 501, gid 1000) and, on most Linux desktops, your own user (uid and gid 1000), so `vscode` can open the socket. A Linux user with other ids needs `userns_mode: keep-id:uid=1000,gid=1000`, which maps their own ids onto `vscode`'s. The rootful socket (`/run/podman/podman.sock`) is owned by `root:root`, mode 660, so `vscode`, which is not root, cannot open it whatever else is configured. Making it work under rootful Podman would mean running the dev container's user as root, which dev containers are designed not to do, and rootful Podman also gives every container root on the host or VM.
- **SELinux.** Where SELinux enforces, a container's processes (type `container_t`) may not connect to the Podman socket: the rootless socket is labelled `user_tmp_t` and the rootful one `var_run_t`, and both are refused. Rootful Podman does not change this. The `dev` service therefore sets `security_opt: label=disable`, which runs that one container without SELinux confinement, the same setting `scripts/podman-mvn.sh` uses for its build container. The services beside it (Keycloak, PostgreSQL, the SFTP targets, Prometheus) stay confined. This loses little: a container that holds the Podman API socket can already start any container it likes, so SELinux confinement of `dev` itself would not contain it.

Measured on a macOS Podman machine (Fedora CoreOS 44, SELinux enforcing), with a container mounting the socket and calling the API's `/_ping`:

| Podman | Container user | SELinux labelling | Result |
| --- | --- | --- | --- |
| Rootless, `keep-id` | root | on | refused |
| Rootless, `keep-id` | uid 1000 (`vscode`) | off (`label=disable`) | works |
| Rootful | non-root | on | refused |
| Rootful | non-root | off | refused (the socket is `root:root` 660) |

On Ubuntu and Debian there is no SELinux, so `label=disable` makes no difference there and rootless works through `keep-id` alone (expected; not yet measured).

A machine created with `--rootful` can be switched back with `podman machine stop`, `podman machine set --rootful=false` and `podman machine start`. Rootful and rootless Podman keep separate images, containers and volumes, so the dev stack then starts with fresh volumes (dev database, Maven and NuGet caches).

### CPU architecture: Linux and Apple silicon

The dev container, `mvn verify` and the `kind` deployment must work, and give the same results, on:

| Host | Containers run as |
| --- | --- |
| Linux, x86-64 | `linux/amd64`, natively |
| Linux, ARM64 | `linux/arm64`, natively |
| macOS on Apple silicon (M-series) | `linux/arm64`, natively, in the Podman machine's ARM64 VM |
| macOS on Intel | `linux/amd64`, natively, in the Podman machine's x86-64 VM |

Each container must run as the host's own architecture. Emulation is not relied on. The Podman machine on Apple silicon can run `linux/amd64` images through qemu's user-mode emulation, but that emulation lacks system calls some programs need. Garage's amd64 image, for one, exits on start with `Cannot open LMDB database: Function not implemented (os error 38)`.

**Rules:**

- Every image the build, its tests, the dev stack and `fdrop-kubernetes` use publishes both `linux/amd64` and `linux/arm64`. A multi-platform tag lets Podman pull the host's own architecture.
- An image pinned by digest is pinned to the digest of its **multi-platform index** (an OCI image index, or a Docker manifest list), never to the digest of one platform's image. A platform digest makes every host pull that one architecture.
- To find the index digest: `skopeo inspect --raw docker://<image>:<tag> | sha256sum`. `podman manifest inspect <image>@sha256:<digest>` must list both architectures.
- The same pin is used everywhere the image appears. `GarageContainer.IMAGE` (the Garage tests) and `garage_image` in `fdrop-kubernetes/modules/objectstore` are kept equal.
- The server image is built for the build machine's own platform by default (Jib's own default is `linux/amd64` alone). `fdrop-server` sets `jib.from.platforms` to `linux/amd64`, and its `arm64-host` profile, activated by `os.arch` `aarch64`, sets `linux/arm64`. A tar holds only one platform, so `jib:buildTar`, `podman load` and the `kind` deployment then run fdrop natively, with no flags.
- A release pushes both platforms as one multi-platform image: `./mvnw -pl fdrop-server -am -Pall-platforms package jib:build`. The distroless Java 21 base publishes both.

**Images** (checked October 2026):

| Image | Used by | Pin | `linux/arm64` |
| --- | --- | --- | --- |
| `dxflrs/garage:v2.5.0` | Garage tests; `modules/objectstore` | index `sha256:677e5b758e4425a325b811c425c026bf35a4bf42f7ddbaa773de3f4fd779a5a5` | yes |
| `ghostunnel/ghostunnel:v1.11.5-distroless` | `modules/objectstore` | index digest | yes |
| `curlimages/curl:8.23.0` | `modules/objectstore` | index digest | yes |
| `localstack/localstack:4.9` | S3 tests | tag | yes |
| `library/postgres:17-alpine` | `PostgresTlsIT`; dev stack | tag | yes |
| `testcontainers/ryuk` | Testcontainers | Testcontainers' own | yes |
| `devcontainers/java:21`, `alpine:3.22`, `pause:3.10`, `keycloak:26.8.1`, `prometheus:v3.5.0` | dev stack | tag | yes |
| `fdrop-dev-sftp:1`, built from `alpine:3.22` | dev stack (`sftp-target`, `sftp-target2`) | built locally | yes, built for the host |
| `library/maven:3.9-eclipse-temurin-21` | `scripts/podman-mvn.sh` | tag | yes |
| `distroless/java21-debian12:nonroot` | the server image's base | tag | yes |

Three images were changed to meet these rules:

- **Garage was pinned to its amd64 image** (`sha256:23b16d34…`), not its index, so on Apple silicon `GarageStorageContractIT` and `GarageEncryptedContractIT` failed and `mvn verify` with them. It is now pinned to the index digest above, in both places.
- **`atmoz/sftp`, used for the SFTP targets before, is amd64 only,** in every tag (`alpine`, `debian`, `latest`). OpenSSH ran under emulation, which the rules above don't allow. The SFTP targets are now built by Compose from `.devcontainer/sftp-server/Containerfile` as `localhost/fdrop-dev-sftp:1`. It is not in `sftp-target/` or `sftp-target2/`, because every file in those folders is read as a key.
  - `FROM docker.io/library/alpine:3.22` (both platforms), plus `openssh-server` and `bash`.
  - It takes atmoz/sftp's interface, so the services are otherwise unchanged: the user as `target::1001::inbox` (no password), keys in `~/.ssh/keys`, scripts in `/etc/sftp.d` run before `sshd` starts (`sftp-target-setup.sh`, which installs the `HOST_KEY` host key and the keys).
  - `sshd` allows public keys only and SFTP only (`ForceCommand internal-sftp`), chrooted to the user's home.
- **The server image was `linux/amd64` only,** Jib's default. It is now built for the host, or both platforms (above).

**Check:** `scripts/check-image-platforms.sh` finds every fully qualified image reference in the repo's tracked files (compose files, `*.tf`, poms, scripts, and the test classes' image constants, with Java's `"…" + "…"` joined). Dev container features and example placeholders are left out. It fails if an image is a single platform's image, if a digest pin is not a multi-platform index, or if the index lacks `linux/amd64` or `linux/arm64`. Given image names as arguments, it checks just those. It uses `podman` (or `docker`) and needs registry access, so it is not part of `mvn verify`: run it whenever an image is added or changed, and in CI once there is one.

## Dev realm

`.devcontainer/keycloak/fdrop-realm.json` is imported on start and defines the realm, the three roles, the `fdrop-applications` group, both clients, the three dev users, the `app-demo` application account and the two-factor browser flow. The key parts (the `fdrop browser` flow, its level 1 and level 2 sub-flows and their level-of-authentication conditions are in the file but left out here; SFTP codes use Keycloak's built-in `direct grant` flow):

```json
{
  "realm": "fdrop",
  "enabled": true,
  "bruteForceProtected": true,
  "otpPolicyType": "totp",
  "otpPolicyAlgorithm": "HmacSHA1",
  "otpPolicyDigits": 6,
  "otpPolicyPeriod": 30,
  "browserFlow": "fdrop browser",
  "roles": { "realm": [ { "name": "fdrop-user" }, { "name": "fdrop-admin" }, { "name": "fdrop-app" } ] },
  "groups": [ { "name": "fdrop-applications", "realmRoles": ["fdrop-app"] } ],
  "clients": [
    {
      "clientId": "fdrop-web",
      "secret": "fdrop-web-dev-secret",
      "publicClient": false,
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": false,
      "redirectUris": ["https://localhost:8443/auth/callback", "https://localhost:5173/auth/callback"],
      "attributes": {
        "pkce.code.challenge.method": "S256",
        "acr.loa.map": "{\"2fa\":\"2\"}",
        "backchannel.logout.url": "https://localhost:8443/auth/backchannel-logout"
      }
    },
    {
      "clientId": "fdrop-sftp",
      "secret": "fdrop-sftp-dev-secret",
      "publicClient": false,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": true,
      "serviceAccountsEnabled": true
    }
  ],
  "users": [
    {
      "username": "admin",
      "enabled": true,
      "email": "admin@fdrop.local",
      "emailVerified": true,
      "firstName": "fdrop",
      "lastName": "Admin",
      "credentials": [ { "type": "password", "value": "admin", "temporary": false } ],
      "realmRoles": ["fdrop-user", "fdrop-admin"]
    },
    {
      "username": "jugash",
      "enabled": true,
      "email": "jugash@fdrop.local",
      "emailVerified": true,
      "firstName": "Jugash",
      "lastName": "Test",
      "credentials": [ { "type": "password", "value": "jugash", "temporary": false } ],
      "realmRoles": ["fdrop-user"]
    },
    {
      "username": "chris",
      "enabled": true,
      "email": "chris@fdrop.local",
      "emailVerified": true,
      "firstName": "Chris",
      "lastName": "Test",
      "credentials": [ { "type": "password", "value": "chris", "temporary": false } ],
      "realmRoles": ["fdrop-user"]
    },
    {
      "username": "app-demo",
      "enabled": true,
      "email": "app-demo@applications.invalid",
      "emailVerified": true,
      "firstName": "app-demo",
      "lastName": "Application account",
      "credentials": [ { "type": "password", "value": "app-demo", "temporary": false } ],
      "groups": ["/fdrop-applications"]
    },
    {
      "username": "service-account-fdrop-sftp",
      "enabled": true,
      "serviceAccountClientId": "fdrop-sftp",
      "clientRoles": { "realm-management": ["view-users", "query-users", "manage-users", "query-groups"] }
    }
  ]
}
```

- **Host setup.** Install Podman 5 and `podman-compose`, and run Podman rootless (see Podman on macOS and Linux above). On macOS and Windows, create a Podman machine with `podman machine init` (rootless is the default; don't pass `--rootful`), then `podman machine start`. On Linux, enable your user's API socket with `systemctl --user enable --now podman.socket`. For IntelliJ, also install Docker Compose v2 (Ubuntu: `docker-compose-v2`, without `docker.io`), which `podman compose` then prefers: IntelliJ adds its own long-syntax bind mount at the workspace folder, and only Docker Compose merges it with the compose file's mount (podman-compose passes both, and Podman rejects the duplicate mount destination). Nothing needs `PODMAN_SOCKET` set by hand: `initializeCommand` finds the socket (see Testcontainers).
- **VS Code.** Set `dev.containers.dockerPath` to `podman` and `dev.containers.dockerComposePath` to `podman-compose` in user settings, so Dev Containers drives Podman. These are user settings, not committed to the repo.
- **Testcontainers.** The integration tests start MinIO, LocalStack, OpenSSH, Keycloak and PostgreSQL through the host's Podman API socket, mounted into the dev container as `/var/run/docker.sock`; Podman serves the Docker-compatible API there, so Testcontainers needs no Docker. `devcontainer.json`'s `initializeCommand` runs `scripts/podman-socket-env.sh` on the host before Compose starts: it asks Podman for its socket (`podman info`, `Host.RemoteSocket.Path`) and writes `PODMAN_SOCKET` to the git-ignored `.devcontainer/.env`, which Docker Compose and podman-compose read, so a rootless Podman machine (`/run/user/<uid>/podman/podman.sock` inside the machine) and rootless Linux (`$XDG_RUNTIME_DIR/podman/podman.sock`) work without setting anything by hand. Without it, `PODMAN_SOCKET` defaults to `/run/podman/podman.sock`, which only a rootful Podman has; on a rootless one Podman then fails to create that path (`mkdir /run/podman/podman.sock: permission denied`). A `PODMAN_SOCKET` already set in the environment wins. Ryuk, Testcontainers' clean-up container, runs privileged so it can reach the socket. The containers a test starts run beside the dev container, not inside it, so their published ports are on the host: `TESTCONTAINERS_HOST_OVERRIDE=host.containers.internal` (the name Podman gives the host inside its containers) makes Testcontainers connect there instead of to the dev container's own `localhost`, where nothing listens and every such test used to be skipped.
- **Rootless and SELinux.** `userns_mode: keep-id` keeps workspace files owned by the host user, so `updateRemoteUserUID` is off; bind mounts carry `:z` so they work on SELinux hosts such as Fedora and RHEL, and the label is ignored elsewhere. The `dev` service alone also has `security_opt: label=disable`, so it can use the Podman socket; see Podman on macOS and Linux.
- **Image names** are fully qualified (`docker.io/atmoz/sftp`, `quay.io/keycloak/keycloak`), because Podman does not assume Docker Hub for short names.
- Prometheus runs beside the dev container at `http://localhost:9090`, scraping `https://localhost:8081/metrics` every 15 s (trusting the dev CA in `.devcontainer/certs/ca.crt`) with the same alert rules as production (`deploy/prometheus/alerts.yml`), so new metrics and alerts can be tried locally. The dev config sets no metrics token.
- The named `.m2` volume keeps the Maven cache across container rebuilds.
- **PostgreSQL over mTLS.** The dev config's metadata DB is the `postgres` service, connected exactly as production is (see Metadata DB connection): `db.url: jdbc:postgresql://localhost:5432/fdrop`, `db.username: fdrop`, no password, and `db.tls` pointing at `.devcontainer/certs/ca.crt`, `fdrop-db.crt` and `fdrop-db.key`. The server's settings are in `.devcontainer/postgres/`:
  - `postgresql.conf`: `listen_addresses = 'localhost'`, `ssl = on`, `ssl_cert_file`, `ssl_key_file` and `ssl_ca_file` under `/var/lib/postgresql/tls/`, `ssl_min_protocol_version = 'TLSv1.2'`, `hba_file = '/etc/postgresql/pg_hba.conf'`.
  - `pg_hba.conf`: `local all all trust` (for `initdb` and `podman exec … psql` inside the container) and `hostssl all all 127.0.0.1/32 cert` and `hostssl all all ::1/128 cert`; there is no `host` line, so a plain TCP or password-only connection is refused, as it should be in production.
  - Developers query it from the dev container with `psql "host=localhost dbname=fdrop user=fdrop sslmode=verify-full sslrootcert=.devcontainer/certs/ca.crt sslcert=.devcontainer/certs/fdrop-db.crt sslkey=.devcontainer/certs/fdrop-db.key"` (`postCreateCommand` installs `postgresql-client`), or with `podman exec -it devcontainer-postgres-1 psql -U fdrop` over the local socket. Host tools such as DBeaver use the forwarded port 5432 with the same three files.
  - The data lives in the `fdrop-pgdata` volume; `podman volume rm devcontainer_fdrop-pgdata` starts it empty. Existing H2 data in `data/meta` is not migrated: after switching, delete `data/` too so stored files and metadata start empty together.
  - Unit tests keep using in-memory H2 for speed; nothing in `./mvnw verify` depends on this service. PostgreSQL integration tests start their own server through Testcontainers (see Testing).
- The .NET 10 SDK is installed (the `dotnet` feature) for `fdrop-http-client-dotnet`, so `./mvnw verify` builds and tests the .NET client in the dev container and in CI like everything else. `DOTNET_CLI_TELEMETRY_OPTOUT=1` and `DOTNET_NOLOGO=1` are set in `remoteEnv`, and `~/.nuget` is a named volume (`fdrop-nuget-home`) like the Maven cache. The volume covers all of `~/.nuget`, not just `packages/`: NuGet also writes `~/.nuget/NuGet/NuGet.Config` there, and a volume mounted below `~/.nuget` leaves the folder above it owned by root, so restore fails with "Access to the path '/home/vscode/.nuget/NuGet' is denied". `postCreateCommand` gives the new volume to `vscode`, as it does `.m2`.
- Node 22 and npm are installed so developers can run `npm run dev` and `npm test` in `fdrop-web` directly; the Maven build still pins its own Node and npm through `frontend-maven-plugin`, and both must use the same major version.
- The dev container, Keycloak and Prometheus share one network namespace, owned by the `localhost` service (a pause container), so `https://localhost:8180/realms/fdrop` is the same issuer for the browser (through the forwarded port) and for the fdrop server. Without this, tokens issued to the browser would fail the server's `iss` check. The namespace belongs to `localhost` rather than `dev` because Podman refuses to remove a container others share: with it, the dev container can be recreated (IntelliJ IDEA does so each time it opens the project) without touching the services beside it.
- **IntelliJ IDEA** opens the same `devcontainer.json`. Set its Docker connection to Podman, using the Podman machine's default rootless connection (`podman-machine-default`, not `podman-machine-default-root`) on macOS and Windows, so the images it builds and the containers `docker-compose` starts share the same rootless store, and Docker Compose to a `docker-compose` v2 binary.
- Default user: **admin / admin** in the `fdrop` realm, with `fdrop-user` and `fdrop-admin`. The Keycloak admin console (master realm, `https://localhost:8180/admin`) also uses admin / admin. Two standard (non-admin) users, **jugash / jugash** and **chris / chris**, hold only `fdrop-user`, so sharing, destination assignments and permission checks can be tried with more than one account. Further users are created from fdrop's admin screen.
- **app-demo / app-demo** is an application account in `fdrop-applications`, for trying password login (`curl --cacert .devcontainer/certs/ca.crt -u app-demo:app-demo https://localhost:8443/api/v1/me`, `sftp -P 2222 app-demo@localhost`) and for adding an application to shared and forward folders. The dev config enables the mTLS listener on 8444 with the dev CA, so `curl --cert` or the CLI can try certificate login with the generated `app-demo` certificate once an admin registers its subject or SAN on `app-demo`:
  ```bash
  java -jar fdrop-cli/target/fdrop-cli.jar upload README.md /home/ --url https://localhost:8444 --cert .devcontainer/certs/app-demo.crt --key .devcontainer/certs/app-demo.key --ca .devcontainer/certs/ca.crt
  ``` fdrop creates the `app-demo` row at its first password login.
- Two-factor in dev: the dev config sets `auth.twoFactor.require: none`, so the dev users keep signing in with just a password and the acceptance checks below need no authenticator app. Any dev user can still turn it on from the Account page. To try the production behaviour, start fdrop with `FDROP_AUTH_TWOFACTOR_REQUIRE=admins` and admin is asked to set up an authenticator app at the next sign-in. Deleting the credential in the Keycloak admin console, or recreating the Keycloak container, undoes it.
- `sftp-target` and `sftp-target2` are two throwaway SFTP servers, so a forward folder can be shown sending each file to two separate servers. Each accepts public-key login only (user `target`, directory `inbox`) and is reachable from the dev container as `sftp-target:22` and `sftp-target2:22`. After creating a destination for each in the admin UI, the developer pastes that destination's public key into the git-ignored `.devcontainer/sftp-target/authorized_keys` or `.devcontainer/sftp-target2/authorized_keys`; README "Trying forward folders" walks through it in the admin screens.
  - The whole `.devcontainer/sftp-target*/` folder is mounted as the image's keys folder, where every file is a key; a committed comment-only `README.pub` keeps it non-empty, since a missing single-file mount becomes an empty directory under Docker-compatible engines and the image cannot start with one.
  - `.devcontainer/sftp-target-setup.sh`, run by the image from `/etc/sftp.d` before sshd starts, installs the keys folder as `authorized_keys` and then again every 2 seconds, so a pasted key works without recreating the container (the image itself installs keys only when its container is created).
  - Each server has a fixed Ed25519 host key, made by `scripts/dev-certs.sh` with `ssh-keygen` as `.devcontainer/certs/sftp-target*_ssh_host_ed25519_key` and copied into place by the same setup script, so a host key pinned in fdrop keeps matching after the container is recreated. The certs folder is mounted whole, for the same reason as the keys folder.
  - The dev config sets `forward.allowedHosts: [sftp-target, sftp-target2]` and a fixed dev `forward.encryptionKey`.
- These credentials and client secrets are for development only. The realm file stays under `.devcontainer/` and is never packaged into the jar or image; fdrop logs a startup warning if a client secret ends in `-dev-secret`.
- Keycloak runs in `start-dev` mode with its own throwaway database, so changes made in its console are lost when the container is recreated; durable changes go into `fdrop-realm.json`.
- **HTTPS only.** The dev config serves the web app and API on https://localhost:8443 and nothing on plain HTTP. A one-shot `certs` service, which Keycloak and the dev container wait for, runs `scripts/dev-certs.sh` (OpenSSL in an Alpine container) to create, in the git-ignored `.devcontainer/certs/`, a dev CA, a `localhost` server certificate for fdrop's HTTPS and mTLS ports and the Vite dev server, an `app-demo` client certificate, fixed SSH host keys for the two dev SFTP destinations, a `postgres` server certificate (SAN `DNS:localhost`, `IP:127.0.0.1`, `IP:::1`, `serverAuth`) and an `fdrop-db` client certificate with `CN=fdrop` (`clientAuth`), whose key is written in PKCS#8. One CA signs them all, which is fine for development; production would normally use a separate CA for DB clients, so a leaked application certificate cannot open the DB. Existing files are kept until a week before they expire. Keycloak serves HTTPS only at `https://localhost:8180` with the same `localhost` certificate, and trusts the dev CA (`KC_TRUSTSTORE_PATHS`) for its back-channel logout calls to fdrop; the dev config sets `auth.oidc.trustedCas: .devcontainer/certs/ca.crt` so fdrop trusts Keycloak in turn. The browser trusts the dev CA once (it is the same CA as fdrop's). Prometheus scrapes fdrop's management port over HTTPS with the same CA. The demo stack in `deploy/compose/` does the same into `deploy/compose/certs/`. These keys are for development only.
- Acceptance: in a fresh container, `./mvnw verify` passes with no extra setup; after starting the server, logging in at `https://localhost:8443` as admin / admin works, `sftp -P 2222 admin@localhost` accepts password admin, and after the admin creates and assigns destinations for `sftp-target` and `sftp-target2`, a file dropped into a forward folder sending to both arrives in each server's `inbox`.
- CI runs the same container with the Dev Containers CLI on an Ubuntu GitHub runner, which ships Podman: `devcontainer up --docker-path podman --docker-compose-path podman-compose`, then `./mvnw verify` inside it, so local and CI builds match.
