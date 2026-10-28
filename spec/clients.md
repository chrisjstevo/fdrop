# Clients

[fdrop specification](../SPEC.md)

## Command line client (fdrop-cli)

`fdrop-cli` is a Maven module that builds `fdrop`, a Java command line client that lets a system upload files to fdrop unattended, one at a time or by watching a directory. It signs in as an application account with a client certificate on the mTLS listener and uploads over HTTPS with the resumable upload protocol, so a large upload survives network drops and even a restart of the CLI.

### Usage

```text
fdrop upload <local-file> <remote-path> [options]
fdrop watch <local-dir> <remote-dir>/ [options]
fdrop whoami [options]
```

- `upload` sends one file. `<remote-path>` is a path in the account's virtual tree: a directory ending in `/` (the local file name is kept, e.g. `/forward/partner-a/`) or a full file path (`/shared/finance/2026-10.csv`).
- `watch` runs until stopped and uploads each new file that appears in `<local-dir>`; see Watching a directory.
- `whoami` calls `GET /api/v1/me` and prints the account name and kind, to check certificate and connectivity before a job is scheduled.

| Option | Env var | Notes |
| --- | --- | --- |
| `--url` | `FDROP_URL` | Base URL of the mTLS listener, e.g. `https://fdrop.example.com:8444`. Must be `https` |
| `--cert`, `--key` | `FDROP_CERT`, `FDROP_KEY` | PEM client certificate chain and PKCS#8 private key (RSA, EC or Ed25519) |
| `--key-password-file` | `FDROP_KEY_PASSWORD_FILE` | Password for an encrypted private key, read from a file |
| `--keystore` | `FDROP_KEYSTORE` | PKCS#12 file holding certificate and key, instead of `--cert` and `--key` |
| `--keystore-password-file` | `FDROP_KEYSTORE_PASSWORD_FILE` | Password for the PKCS#12 file |
| `--ca` | `FDROP_CA` | Extra PEM CA bundle to trust for the server certificate, added to the JDK's default trust store |
| `--overwrite` | — | Replace an existing file at the target; without it an existing file fails with exit code 3 |
| `--expires-in` | — | Expiry for the uploaded file, e.g. `6 days`, `2weeks` or `never`; without it the folder's default applies. See File expiry |
| `--parallel` | — | Chunks sent at once (default 3, maximum 8) |
| `--retry-for` | — | How long to keep retrying network errors before giving up (default 1h) |
| `--no-resume` | — | Do not resume a previous run's upload session; start a new one |
| `--quiet`, `--json` | — | No progress output; or print the result as one line of JSON on stdout |
| `--config` | `FDROP_CONFIG_FILE` | Properties file with any of the above (default `~/.config/fdrop/cli.properties`); options override env vars, which override the file |

- Secrets never go on the command line: key and keystore passwords come only from files (or the config file, which must not be readable by others; the CLI refuses it otherwise), so they don't show in process lists or shell history.
- There is no option to skip server certificate or hostname verification.
- Password login (HTTP Basic) is not offered; the CLI uses the certificate only.

### Upload flow

1. Load the client certificate and key into a `KeyManager`, and the JDK trust store plus `--ca` into a `TrustManager`; build one `SSLContext` (TLS 1.2+) for a `java.net.http.HttpClient` using HTTP/1.1.
2. Read the file once to compute its SHA-256, so the server can verify the assembled file.
3. Look for a saved session for this upload in `~/.local/state/fdrop/uploads/`, keyed by a hash of URL, account certificate fingerprint, remote path, size, last-modified time and file hash. If one exists, `GET /api/v1/uploads/{id}` gives the chunks already received; a `404` or a non-`OPEN` state means start again.
4. Otherwise `POST /api/v1/uploads` with path, size, last-modified, SHA-256, `overwrite` and `expiresIn` when given, and save the returned id, chunk size and chunk count (file mode `0600`).
5. Send each missing chunk with `PUT /api/v1/uploads/{id}/chunks/{index}` and `X-Chunk-SHA256`, up to `--parallel` at once. Each chunk body streams from a `FileChannel` region, so memory stays at a few buffers whatever the file size.
6. On a network error, a timeout, a connection dropped during the TLS handshake, `429` or `5xx`, wait with exponential backoff (1 s doubling to 60 s, with jitter) and resume from the server's status; a `409` on a chunk also triggers a status check (three times at most for the same chunk). Stop when nothing has got through for `--retry-for`. A server whose session has expired (`404`) gets a new one.
7. `POST /api/v1/uploads/{id}/complete`; on `202`, poll status until `COMPLETED` or `FAILED`. Then delete the saved session and print the result (path, size, SHA-256, whether it resumed, the expiry if any, and for a forward folder that the file is queued for forwarding).
8. On Ctrl-C, keep the session on the server and the state file, so the next run resumes. A failed upload (wrong hash, `FAILED`) is aborted with `DELETE /api/v1/uploads/{id}`.

Progress goes to stderr as a percentage of bytes acknowledged by the server, only when stderr is a terminal. Requests carry `User-Agent: fdrop-cli/<version>`, which fdrop records in the audit log.

### Watching a directory

`fdrop watch` keeps running and uploads every new file that appears in a local directory, for systems that drop files into a folder rather than call a command. It uses the same certificate, options, chunked resumable upload and retries as `fdrop upload`; each file goes up exactly as `fdrop upload <file> <remote-dir>/<relative path>` would send it.

```text
fdrop watch <local-dir> <remote-dir>/ [options]
fdrop watch --local-dir <local-dir> --remote-dir <remote-dir>/ [options]
```

#### The two folders

Every watch names exactly one local folder to listen to and one folder on the server to upload into; there are no defaults for either, and the watcher refuses to start without both.

| What | Argument | Option | Env var | Config key |
| --- | --- | --- | --- | --- |
| The local folder to listen to | first | `--local-dir` | `FDROP_WATCH_LOCAL_DIR` | `watch.localDir` |
| The server folder to upload into | second | `--remote-dir` | `FDROP_WATCH_REMOTE_DIR` | `watch.remoteDir` |

- **Local folder.** An absolute path, or one relative to the working directory, resolved to its real path at start and logged. It must exist, be a directory and be readable (exit code 1 otherwise). It may not be inside `--after-upload move=` or `--on-failure move=` targets, nor contain them.
- **Server folder.** A directory in the account's virtual tree, written with a trailing `/`: the account's home (`/home/inbound/`), a shared folder it can write to (`/shared/finance/incoming/`), or a forward folder (`/forward/partner-a/`), whose files are then sent on as usual. At start the watcher checks with `GET /api/v1/files?path=<remote-dir>` that it exists, is a directory and is writable by the account; otherwise it exits with code 4 and the server's reason. It does not create the folder itself, so a typo cannot quietly start a new one; with `--recursive`, it creates only the subfolders below it.
- **How a file's name on the server is formed:** the server folder plus the file's path relative to the local folder. With `fdrop watch /srv/outbox /shared/finance/incoming/ --recursive`, `/srv/outbox/2026/10/ledger.csv` is uploaded as `/shared/finance/incoming/2026/10/ledger.csv`.
- **One pair per process.** To watch several folders, run one watcher per pair. The systemd template does this with one properties file per pair, for example `/etc/fdrop/watch/finance.properties`:

  ```properties
  url=https://fdrop.example.com:8444
  cert=/etc/fdrop/watch/finance.crt
  key=/etc/fdrop/watch/finance.key
  watch.localDir=/srv/outbox/finance
  watch.remoteDir=/shared/finance/incoming/
  watch.afterUpload=move=/srv/sent/finance
  ```

  Run it with `systemctl enable --now fdrop-watch@finance`.
- **Shown in the log, status file and ledger.** Both folders appear in the start line, in the status file, and in the ledger's identity (see below), so changing either one starts a new ledger rather than reusing another folder's history.

#### Finding new files

- **Events.** The watcher registers `<local-dir>` with a `java.nio.file.WatchService` for `ENTRY_CREATE`, `ENTRY_MODIFY` and `OVERFLOW`, using the JDK's native watch service (inotify on Linux).
- **Subdirectories.** `WatchService` is not recursive. With `--recursive`, every subdirectory is registered as well, including ones created later. A file in `a/b/` goes to `<remote-dir>/a/b/`, and missing remote directories are created first with `POST /api/v1/files/mkdir`. Without `--recursive`, subdirectories are ignored.
- **Events are only a hint.** Every event is followed up by looking at the file itself.
- **Rescans catch what events miss.** A full scan of the directory runs:
  - at start
  - after an `OVERFLOW` (the kernel's event queue filled)
  - every `--rescan` interval (default 5 min)

  Watch events are not delivered for network file systems (NFS, SMB) or for changes made on another host. `--poll <interval>` drops the `WatchService` and only scans, every interval (for example `--poll 30s`); the CLI prints a hint to use it when `<local-dir>` is on a network file system.
- **Not uploaded:**
  - symbolic links, logged as skipped
  - anything that is not a regular file
  - names matching `--ignore` globs, which by default are hidden files (`.*`) and the usual temporary names (`*.tmp`, `*.part`, `*.partial`, `*.crdownload`, `*~`). `--ignore` replaces the defaults and may be given several times.
  - names not matching `--include` globs, when any are given
- **Order.** Files found by a scan go up oldest first (by last-modified time). Files found by events go up in the order they became ready.

#### When a file is ready

A file appearing does not mean it has been written completely, so the watcher waits until it is ready before it uploads anything:

- **Settling (default).** A file is ready when its size and last-modified time have not changed for `--settle` (default 10 s). Writers that write to a temporary name and then rename into place (the recommended way) are ready at once in practice, since the rename produces a complete file.
- **Marker files.** With `--ready-marker <suffix>` (for example `.done`), `data.csv` is ready only when `data.csv.done` exists, and its size and last-modified time are then fixed. The marker is never uploaded, and is removed or moved along with its file (see `--after-upload`). This is for producers that cannot rename atomically.
- **Changed while uploading.** The upload reads the file's SHA-256 first and the server checks it at `complete`. If the file's size or last-modified time changes during the upload, or `complete` reports a hash mismatch, the session is aborted. The file then waits to settle again and starts over, and a warning is logged.

#### After an upload

| `--after-upload` | Effect on a successful upload |
| --- | --- |
| `keep` (default) | Leave the file; the ledger (below) stops it going up again |
| `delete` | Delete the local file (and its marker) once the server reports `COMPLETED` |
| `move=<dir>` | Move it (and its marker) into `<dir>`, keeping its relative path. `<dir>` must be on the same file system, so the move is a rename, and outside the watched tree |

A file the server refuses for good is set aside rather than retried forever. That covers `400`, `403`, `404`, `409` without `--overwrite`, `413`, and an assembly `FAILED` after the file had settled. With `--on-failure move=<dir>` it is moved there with a `<name>.error.txt` holding the problem's title and detail. By default (`keep`) it stays where it is, recorded as failed in the ledger, and is tried again only when its size or last-modified time changes. A file that only failed for network reasons after `--retry-for` is not a refusal: it stays queued and its session is kept, and the next rescan resumes it.

#### State, restarts and one watcher per directory

- **The ledger.** Each watch keeps a ledger in `~/.local/state/fdrop/watch/<id>/ledger.jsonl` (mode `0600`), where `<id>` is a hash of the URL, certificate fingerprint, local directory and remote directory. It is append-only JSON lines, compacted at start, with one record per file:
  - relative path, size, last-modified time and SHA-256
  - the remote path
  - the outcome (`uploaded`, `refused` with the problem, or `changed`)
  - the time
- **What is uploaded again.** A file whose path, size and last-modified time match an `uploaded` record is not uploaded again, so `keep` does not repeat uploads after a restart. A file that changed after it was uploaded is uploaded again, with `--overwrite` if given; without it, the server's `409` makes it a refusal as above.
- **Restarts.** In-progress uploads use the saved sessions of `fdrop upload`, so stopping and starting the watcher (or a crash) resumes the chunks already sent rather than starting again.
- **One watcher per directory.** At start the watcher takes an exclusive `FileChannel` lock on `~/.local/state/fdrop/watch/<id>/lock`; a second watcher with the same settings exits with code 7 and a message naming the running one's PID. Two hosts watching the same network directory are not supported.

#### Running it as a service

- **Concurrency.** Up to `--max-files` files (default 2) upload at once, each with `--parallel` chunks at once, so a burst of small files does not wait behind one large one.
- **Checks at start.**
  1. `GET /api/v1/me` checks the certificate.
  2. `GET /api/v1/files?path=<remote-dir>` checks the server folder exists, is a directory and is writable (see The two folders). Exit code 2 or 4 when these checks fail, so a misconfigured service fails at once rather than queueing files.
  3. `<local-dir>` must exist, be a directory and be readable (exit code 1).
- **While running, the server is retried.** If the server becomes unreachable, or starts refusing the certificate (`401`, for example after it expired), the watcher logs it and keeps retrying with backoff, without exiting. Files keep queueing locally, and uploads resume when the server is back. If `<local-dir>` disappears or becomes unreadable, the watcher exits with code 7.
- **Logging.** One line per event on stderr: started, file ready, uploaded (path, size, duration, resumed or not), refused (with the problem), retrying, set aside, and stopped. With `--json` each line is a JSON object for log collectors. Progress percentages are not printed.
- **Status file.** `--status-file <path>` writes a small JSON document, replaced atomically every 30 s and at each upload, that monitoring can read:
  - the files queued and in progress
  - the counts uploaded and refused since start
  - the last successful upload and its time
  - the last error
- **Stopping.** `SIGTERM` or Ctrl-C stops taking new files. In-flight chunk requests may finish within `--shutdown-timeout` (default 30 s). Unfinished sessions and the ledger are kept, so the next start resumes. Exit code 0.
- **Requests and the audit log.** Requests carry `User-Agent: fdrop-cli/<version> (watch)`, so the server's audit log tells watched uploads from single ones.
- **Packaging.** The release archive adds a systemd unit template, `fdrop-watch@.service`. It runs `fdrop watch` as an unprivileged user with the settings in `/etc/fdrop/watch/%i.properties` (`Restart=on-failure`, `ProtectSystem=strict`, write access only to the watched, move and state directories), so one host can run several watches.

#### Watch options

These come in addition to the connection, `--overwrite`, `--expires-in`, `--parallel`, `--retry-for`, `--json` and `--config` options of `upload`. In the config file they take the same names without dashes (`watch.recursive=true`).

| Option | Default | Notes |
| --- | --- | --- |
| `--local-dir` | required | The local folder to listen to (or the first argument) |
| `--remote-dir` | required | The server folder to upload into, ending in `/` (or the second argument) |
| `--recursive` | off | Watch subdirectories and keep their structure under `<remote-dir>` |
| `--include`, `--ignore` | all files; hidden and temporary names ignored | Globs on the relative path, repeatable |
| `--settle` | 10s | How long size and last-modified time must stay the same before a file is ready |
| `--ready-marker` | — | Upload a file only when `<name><suffix>` exists, e.g. `.done` |
| `--after-upload` | `keep` | `keep`, `delete` or `move=<dir>` |
| `--on-failure` | `keep` | `keep` or `move=<dir>` for files the server refuses |
| `--max-files` | 2 | Files uploaded at once (maximum 8) |
| `--rescan` | 5m | Full scan interval, to catch missed events |
| `--poll` | — | Scan only, every interval, without `WatchService` (network file systems) |
| `--status-file` | — | Where to write the status JSON |
| `--shutdown-timeout` | 30s | How long in-flight requests may finish after a stop signal |

### Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Uploaded (or `whoami` succeeded) |
| 1 | Usage error: bad option, missing file, unreadable certificate or key, an `--expires-in` that does not parse, or a remote path or expiry the server rejects (`400` on create) |
| 2 | Authentication failed: TLS handshake refused, untrusted server certificate, or `401` |
| 3 | Target exists and `--overwrite` was not given (`409` on create) |
| 4 | Not allowed: `403` (no write permission), quota exceeded (`413`), or `404` (no such folder, or not a member) |
| 5 | Gave up after `--retry-for` without reaching the server; the session is kept for a later run. `whoami` does not retry and exits 5 when the server cannot be reached |
| 6 | Server-side failure: assembly `FAILED`, hash mismatch or an unexpected response |
| 7 | `watch` only: another watcher already runs with the same settings, or the watched directory disappeared or became unreadable |

`watch` exits 0 when stopped by a signal. At start it exits as `upload` would (1, 2 or 4) when the settings, certificate or remote directory are wrong, or 5 when the server cannot be reached at all. Once running, refused files and unreachable servers do not end it.

Error messages print the server's problem JSON `title` and `detail`, never certificate or key contents.

### Module and packaging

- `fdrop-cli` depends only on the JDK, picocli (argument parsing and help) and Jackson (JSON). It does not depend on `fdrop-core` or any server module at compile or run time, so the jar stays small and has no server code on the client; `fdrop-http` and `fdrop-storage-local` are test-scope dependencies only, for the integration tests, which start fdrop's HTTP server in the test JVM. `fdrop-cli` is the last module in the reactor. Its request and response types are its own records; the integration tests keep them in step with the server.
- Private keys load with the JDK only: PKCS#8 PEM through `KeyFactory`, encrypted PKCS#8 through `EncryptedPrivateKeyInfo`, and PKCS#12 through `KeyStore`. Traditional OpenSSL (`BEGIN RSA PRIVATE KEY`) keys are refused with a hint to convert them with `openssl pkcs8 -topk8`.
- `maven-shade-plugin` builds `fdrop-cli/target/fdrop-cli.jar`, run with `java -jar` on Java 21. The module also packages a `fdrop` launcher script and a `.tar.gz` with jar, script, README and the `fdrop-watch@.service` systemd unit template, attached to each release.
- The version printed by `fdrop --version` is the project version and commit, matching the server's `fdrop_build_info`.

### Tests

- **Unit tests** (JUnit 5, Surefire): option precedence of options, environment variables and config file, and `--config` over `$FDROP_CONFIG_FILE`; refusal of a config file readable by others; PEM loading for RSA, EC and Ed25519 keys, encrypted PKCS#8 with its password file, PKCS#12, a clear error for a traditional OpenSSL key and for a key that is not the certificate's; remote path handling (directory versus file); session keys; backoff timing with a fake clock.
- **Fake server tests:** a JDK `HttpsServer` in the test, requiring a client certificate, with certificates made per run by a test-only CA (BouncyCastle in test scope). It scripts responses to check: every chunk sent with its hash and every request with a client certificate and the `fdrop-cli` User-Agent; a connection dropped mid-chunk, a `503` and a `409` retried after a status check; a run that gives up keeps its session, and the next run resumes and sends only the missing chunks; `--no-resume`; a saved session the server forgot starts again; `202` polling on complete, and a failed assembly aborted with exit code 6; exit codes for `409`, `403`, `413`, `404`, `401` and a rejected chunk; an untrusted server certificate failing with exit code 2; giving up after `--retry-for`; usage errors.
- **Integration tests** (Failsafe, `*IT.java`): fdrop's HTTP server with the mTLS listener in the test JVM, using the test identity provider of the server's own tests (no Keycloak), and an application account with a pinned certificate. The CLI uploads into `/home`, a shared folder where it is a read-write member, and a forward folder, whose forward job is then queued; it is refused (exit code 4) in a read-only folder and one it is not a member of; an existing file gives exit code 3 without `--overwrite`; an unregistered certificate and a disabled account give exit code 2. A 40 MiB file goes through a test TCP proxy that cuts the connection three times mid-transfer and arrives with the right SHA-256; and a run whose server becomes unreachable mid-upload gives up, then the next run resumes without re-sending the chunks that arrived.
- **Watch unit tests**, with a fake clock and a fake uploader:
  - both folders required, from arguments, options, env vars or the config file, with the usual precedence; a server folder without a trailing `/` refused; move targets inside the watched folder refused
  - settling (a file still growing is not ready; one unchanged for `--settle` is)
  - marker files
  - default and custom `--include` and `--ignore` globs
  - symbolic links skipped
  - ledger records, compaction, and the decisions to skip, upload again or treat as refused
  - remote paths for nested files
  - the lock refusing a second watcher
  - option parsing for `move=<dir>` and its checks (same file system, outside the watched tree)
  - a file that changes while it is uploading going up again once settled, an `OVERFLOW` asking for a full scan, a server that is unreachable keeping the file queued until `--rescan`, refused files not retried, and `--after-upload delete` removing the marker too
- **Watch integration tests** (`WatchIT`) against the same in-JVM server, running the whole command in the test JVM. They check that:
  - a file there at the start, one written in place, one renamed into the folder, a burst of 20 small files, and a file in a folder created while running all arrive once in the named server folder, keeping their paths with `--recursive`; a temporary name is not uploaded
  - a restart uploads nothing again, because the ledger knows every file
  - `move=` after the upload moves the file only after it arrived; the same name again without `--overwrite` is refused by the server and set aside with its `.error.txt`; the status file counts both
  - `--poll` finds files without events
  - a server folder that does not exist, or that the account may only read, and a local folder that does not exist, stop the watcher at start (codes 4, 4 and 1)
  - a second watcher with the same settings exits with code 7, and so does the first when its folder is removed

  Resuming an interrupted upload and surviving connection drops are the `upload` path's, already covered by the integration tests above. A file that changes during its upload, an `OVERFLOW`, and a server that is away for a while are covered by the unit tests with a fake uploader.
- **End-to-end test:** the shaded jar is run as a separate process with `java -jar` against the same kind of server, checking `--version`, `whoami --json`, `upload --json`, an existing file's exit code 3 with its message on stderr, and a usage error; and `watch --json` as a background process that uploads a file dropped into its directory and exits 0 on `SIGTERM`.

## Java client library (fdrop-http-client-java)

`fdrop-http-client-java` is a Maven module with a Java client for the REST API, for applications that move files through fdrop. It depends only on the JDK's `HttpClient` and Jackson, not on any server module.

- **Interface.** `FdropClient` covers the API for standard users and application accounts: the account (`me`, upload history), files (list, upload, download, mkdir, move, delete, set and clear expiry), search, folders (create shared and forward folders, members, forward jobs and retries) and the caller's forward destinations. Admin endpoints are not included. Responses are typed records that ignore fields they do not know, so a newer server does not break an older client.
- **Uploads look like one call.** `upload(Path, remotePath, options)` and `upload(InputStream, size, remotePath, options)` use the resumable protocol underneath: create the session, read the source one chunk at a time in the server's chunk size, send up to `parallelChunks` (default 3) at once, each with its `X-Chunk-SHA256`, and complete with the whole file's SHA-256, polling while a `202` assembly runs. A `409` on a chunk is checked against the session status before trying again. Any failure discards the session (`DELETE`), so nothing partial is left; a later call starts a new upload rather than resuming.
- **Retries.** Every request is tried up to `maxAttempts` times (default 5; per chunk for uploads) with exponential backoff and jitter, 1 s doubling to 60 s, honouring `Retry-After`. Connection failures, `429` and `503` are always retried; other network errors, timeouts, `500`, `502` and `504` only for requests that are safe to repeat (reads, chunk `PUT`s, `complete`, which the server treats as idempotent, deletes and `PATCH`/`PUT` settings). `mkdir`, `move` and folder creation are not repeated after the request may have reached the server. Creating an upload session is repeated, since an unused session expires on its own. When the tries run out the call throws `FdropRetriesExhaustedException`, carrying the number of attempts and the last failure. A TLS handshake refused for a certificate fails at once.
- **Errors.** Every failure is an unchecked `FdropClientException`. `FdropApiException` carries the HTTP status and fdrop's RFC 9457 problem type, title and detail.
- **Authentication.** A bearer token supplier (asked before each request, so the caller can refresh it), HTTP Basic for application accounts, or a client certificate on the mTLS port (`clientCertificate(cert, key, ca)` from PEM files, PKCS#8 key, or any `SSLContext`). `http://` URLs are refused: fdrop speaks only HTTPS. Tests run fdrop with certificates from the test CA and trust it, as the CLI's tests do.
- **Tests.** Unit tests of the retry rules without sleeping (driven by `src/test/resources/client-retry-cases.json`, the table the .NET client's tests also run), and tests against a real fdrop HTTP server in the test JVM: multi-chunk uploads from a file and a stream, an empty file, download and comparison, refusals as `FdropApiException`, files and folders, an application account's certificate login on the mTLS port, every chunk's first try cut off and sent again, and a chunk that always fails giving up after `maxAttempts` with its session discarded.

## .NET client library (fdrop-http-client-dotnet)

`fdrop-http-client-dotnet` is the C# counterpart of `fdrop-http-client-java`: the same API calls, the same upload behaviour and the same retry rules, written as idiomatic .NET. It is for .NET applications that move files through fdrop without knowing its upload protocol. Where this section says nothing different, the Java client's section applies.

### Target and packaging

- **Runtime.** The library targets `net10.0`, the current long-term support release, and is built and tested with the .NET 10 SDK on Linux. It is a portable, framework-dependent library: it has no runtime identifier, no native code and no platform-specific APIs, so the same `Fdrop.Client.dll` runs on the portable `linux-x64` and `linux-arm64` runtimes (glibc and musl). It also runs anywhere else .NET 10 does, but Linux is the only platform built and tested.
- **Dependencies.** One package, `Microsoft.Extensions.Logging.Abstractions` (for the optional `ILogger`); everything else is the base class library. `HttpClient` with `SocketsHttpHandler` does the HTTP, `System.Text.Json` with a source-generated `JsonSerializerContext` does the JSON, and `System.Security.Cryptography` does the hashing and certificates. Because the JSON is source-generated, the library is trimming- and Native AOT-compatible (`IsAotCompatible`), and the build fails on any trim or AOT warning.
- **Package.** A NuGet package `Fdrop.Client` with namespace `Fdrop.Client`. The Maven version maps onto a NuGet version (`0.1.0-SNAPSHOT` becomes `0.1.0-dev`, a release `0.1.0` stays `0.1.0`). The package carries XML documentation, symbols (`.snupkg`), SourceLink and a README. Publishing to a feed is a release step, not part of the build.
- **Build settings** (`Directory.Build.props`): `Nullable` enabled, `TreatWarningsAsErrors`, the .NET analyzers at `latest-recommended`, `Deterministic` and `ContinuousIntegrationBuild` in CI. NuGet versions are pinned centrally (`Directory.Packages.props`) and locked (`packages.lock.json`, restored with `--locked-mode` in CI).

### Layout

```
fdrop-http-client-dotnet/
  pom.xml                        # runs the dotnet CLI in the Maven build (see Build)
  global.json                    # pins the SDK feature band (10.0.1xx, rollForward latestFeature)
  Fdrop.Client.sln
  Directory.Build.props
  Directory.Packages.props
  src/Fdrop.Client/              # the library
  tests/Fdrop.Client.Tests/      # xUnit: unit tests and tests against a real fdrop server
  src/test/java/…/ClientTestServer.java   # starts that fdrop server for the tests (Java, test scope only)
```

### API

The surface mirrors `FdropClient` in Java, written the .NET way: every call is asynchronous, takes a `CancellationToken`, and returns `Task<T>`. There are no blocking variants.

```csharp
public interface IFdropClient : IDisposable
{
    Task<Account> GetMeAsync(CancellationToken ct = default);
    Task<UploadHistory> GetUploadHistoryAsync(string? cursor = null, int limit = 50, CancellationToken ct = default);

    Task<Listing> ListAsync(string path, CancellationToken ct = default);
    Task<FileEntry> UploadAsync(string localPath, string remotePath, UploadOptions? options = null, CancellationToken ct = default);
    Task<FileEntry> UploadAsync(Stream source, long size, string remotePath, UploadOptions? options = null, CancellationToken ct = default);
    Task DownloadAsync(string remotePath, string localPath, CancellationToken ct = default);
    Task<Stream> OpenDownloadAsync(string remotePath, CancellationToken ct = default);
    Task<FileEntry> CreateDirectoryAsync(string path, CancellationToken ct = default);
    Task<FileEntry> MoveAsync(string source, string destination, CancellationToken ct = default);
    Task DeleteAsync(string path, bool recursive = false, CancellationToken ct = default);
    Task<FileEntry> SetExpiryAsync(string path, string expiresIn, CancellationToken ct = default);
    Task<FileEntry> SetExpiryAtAsync(string path, DateTimeOffset expiresAt, CancellationToken ct = default);
    Task<FileEntry> ClearExpiryAsync(string path, CancellationToken ct = default);
    Task<SearchResult> SearchAsync(string query, string? cursor = null, int limit = 20, CancellationToken ct = default);

    Task<IReadOnlyList<Folder>> GetFoldersAsync(CancellationToken ct = default);
    Task<Folder> GetFolderAsync(string id, CancellationToken ct = default);
    Task<Folder> CreateSharedFolderAsync(string name, CancellationToken ct = default);
    Task<Folder> CreateForwardFolderAsync(string name, IReadOnlyList<string> destinationIds, int? keepDays = null, CancellationToken ct = default);
    Task<Folder> SetForwardDestinationsAsync(string folderId, IReadOnlyList<string> destinationIds, bool sendExisting = false, CancellationToken ct = default);
    Task DeleteFolderAsync(string id, CancellationToken ct = default);
    Task<Folder> PutMemberAsync(string folderId, string username, Permission permission, CancellationToken ct = default);
    Task RemoveMemberAsync(string folderId, string username, CancellationToken ct = default);
    Task<IReadOnlyList<ForwardJob>> GetForwardJobsAsync(string folderId, CancellationToken ct = default);
    Task RetryForwardJobAsync(string folderId, string jobId, CancellationToken ct = default);
    Task CancelForwardJobAsync(string folderId, string jobId, string reason, CancellationToken ct = default);
    Task<IReadOnlyList<ForwardDestination>> GetForwardDestinationsAsync(CancellationToken ct = default);
}
```

- **Types.** The response types (`Account`, `FileEntry`, `Listing`, `Folder`, `ForwardJob`, `ForwardDestination`, `UploadRecord`, `UploadHistory`, `SearchResult`) are immutable `record` types with the Java client's fields, in .NET naming (`ExpiresAt`, mapped from camelCase JSON). Java's `Me` is `Account` here, and `MoveAsync` takes `source` and `destination`, because `Me` and `To` are Visual Basic keywords, which the .NET analyzers flag in a public API. Times are `DateTimeOffset?` rather than strings, sizes are `long`, and `Permission` is an enum. Unknown JSON fields are ignored, so a newer server does not break an older client.
- **Creating a client.** `new FdropClient(FdropClientOptions)`. The options are a plain class with `init` properties, so they bind from configuration (`IConfiguration.Get<FdropClientOptions>()`). The names and defaults follow the Java builder:

  | Option | Default | Notes |
  | --- | --- | --- |
  | `BaseAddress` | required | `https://`; `http://` is refused, as fdrop speaks only HTTPS |
  | `BearerToken` | — | `Func<CancellationToken, ValueTask<string>>`, asked for before each request so it can be refreshed |
  | `BasicUsername`, `BasicPassword` | — | application accounts |
  | `ClientCertificate` | — | an `X509Certificate2` with its private key; `FdropClientOptions.LoadPemCertificate(certPath, keyPath)` loads PEM files (PKCS#8, or traditional RSA/EC keys, which .NET reads too) |
  | `TrustedCaCertificates` | system store | an `X509Certificate2Collection`, or `LoadPemCa(path)`; when set, the server's chain must end at one of these (`X509ChainTrustMode.CustomRootTrust`) and nothing else is trusted |
  | `MaxAttempts` | 5 | per request, and per chunk for uploads; at least 1 |
  | `InitialBackoff`, `MaxBackoff` | 1 s, 60 s | |
  | `ConnectTimeout`, `RequestTimeout` | 30 s, 5 min | `RequestTimeout` covers one request, including one whole chunk |
  | `ParallelChunks` | 3 | |
  | `UserAgent` | `fdrop-http-client-dotnet/<version>` | |

  Only one of bearer token, Basic and client certificate may be set (a client certificate is used on fdrop's mTLS port). The constructor checks the options and throws `ArgumentException` naming the bad one.
- **Dependency injection.** A second constructor takes an `HttpClient` the application owns, for example from `IHttpClientFactory`. The client then uses that `HttpClient`'s handler as it is, so TLS settings must be on that handler, and `Dispose` leaves the `HttpClient` alone. With options alone, the client creates and owns a `SocketsHttpHandler` with `PooledConnectionLifetime` of 5 minutes, so DNS changes are seen. The library itself has no DI package dependency.
- **Thread safety.** One `FdropClient` per server and account, shared and used concurrently, as with `HttpClient`.
- **Progress.** `UploadOptions(bool Overwrite = false, string? ExpiresIn = null, IProgress<UploadProgress>? Progress = null)`, where `UploadProgress(long Acknowledged, long Total)` reports bytes the server has acknowledged.

### Uploads

Behaviour matches the Java client step for step:
1. Create the session.
2. Read the source in the server's chunk size, hashing each chunk and the whole file as it goes.
3. Send up to `ParallelChunks` chunks at once, each with `X-Chunk-SHA256`. On a `409`, check the session status before sending the chunk again.
4. Complete with the whole file's SHA-256, polling every 250 ms (doubling to 5 s) while a `202` assembly runs, for at most an hour.
5. On any failure or cancellation, discard the session with a `DELETE`. This is retried like any request for at most 10 seconds, on a token of its own rather than the caller's (which may be the reason for the abort), so nothing partial is left behind. If the server cannot be reached, it discards the session when it expires.

Some details are specific to .NET:
- **Memory.** Chunk buffers are rented from `ArrayPool<byte>.Shared` and returned when the chunk is acknowledged or the upload fails, so at most `ParallelChunks` chunks are held at a time. Hashing uses `IncrementalHash`.
- **Streams.** A `Stream` source needs its length up front, as in Java. When the stream is seekable and `size` is passed as `-1`, the client uses `Length - Position`; for a stream that cannot seek, `-1` is an `ArgumentException`, thrown before the call returns its task. The client never closes the stream it was given. File uploads open the file with `FileOptions.SequentialScan`.
- **Cancellation.** Cancelling the token stops sending, discards the session as in step 5, and throws `OperationCanceledException` (never `FdropRetriesExhaustedException`).

### Retries and errors

The same rules as Java: up to `MaxAttempts` tries with exponential backoff and jitter, honouring `Retry-After` (seconds or an HTTP date), capped at `MaxBackoff`.

| Failure | Retried |
| --- | --- |
| Connection refused, DNS failure, connect timeout (`HttpRequestException` with `HttpRequestError.ConnectionError` or `NameResolutionError`) | always |
| `429`, `503` | always |
| Connection dropped mid-request, response ended early, request timeout (`TaskCanceledException` whose cause is a `TimeoutException`, not the caller's token), `500`, `502`, `504` | only requests that are safe to repeat; the others throw `FdropClientException` saying the request may have taken effect |
| TLS handshake refused (`AuthenticationException`: untrusted server, client certificate not accepted) | never: `FdropClientException` at once |
| Caller's cancellation | never: `OperationCanceledException` |
| Any other `4xx` | never: `FdropApiException` |

- **Safe to repeat** means the same requests as in Java: reads, chunk `PUT`s, `complete`, deletes, settings changes (`PATCH`/`PUT`), retrying a forward job, and creating an upload session. Not `mkdir`, `move` or creating folders.
- **Exceptions.**
  - `FdropClientException` is the base class.
  - `FdropApiException` carries `StatusCode`, `ProblemType`, `Title` and `Detail` from fdrop's RFC 9457 body.
  - `FdropRetriesExhaustedException` carries `Attempts`, with the last failure as `InnerException`.

  Messages name the operation and path, never tokens, passwords or key material.
- **Shared retry cases.** The retry rules are a table in `fdrop-http-client-java/src/test/resources/client-retry-cases.json`: the failure, whether the request is safe to repeat, and the expected outcome (`retry`, `error` or `api-error`). The Java and .NET unit tests both run every row, so the two clients cannot drift apart. The .NET test project copies the file in at build time.

### Logging and diagnostics

- **Logging.** An optional `ILogger` (from `Microsoft.Extensions.Logging.Abstractions`, the one allowed dependency, and only the abstractions) logs each retry at `Warning`, with the operation, attempt, wait and failure, and each upload's start and finish at `Information`. Nothing is logged without a logger.
- **Tracing.** An `ActivitySource` named `Fdrop.Client` wraps each public call and each chunk, and `HttpClient`'s own activities nest inside them, so OpenTelemetry users see uploads as traces with no extra setup.

### Tests

- **Framework.** xUnit v3, with plain xUnit assertions (no assertion library), in `tests/Fdrop.Client.Tests`. Unit tests are tagged `[Trait("Category", "Unit")]` and the tests against a server `Integration`. The tests run through VSTest (`UseMicrosoftTestingPlatformRunner` off), not xUnit's Microsoft.Testing.Platform runner, so `dotnet test --filter` and coverlet work the same in the Maven build and in IDEs.
- **Unit tests:**
  - the shared retry cases
  - the backoff waits, run against an injected `TimeProvider` (`FakeTimeProvider`), so nothing sleeps
  - options validation
  - problem bodies mapped to `FdropApiException`
  - PEM loading (PKCS#8 and traditional EC keys) and trusting only the given CA
  - the exception constructors and epoch-millisecond times
- **A real fdrop server.** The tests need a running fdrop, which is Java, so the module's Maven build starts one. `ClientTestServer`, a Java test-scope class in this module, uses `fdrop-core`'s test support (fake identity provider, in-memory DB, local storage in a temp folder, 5 MiB chunks). It:
  1. creates users `alice` and `bob`, and an application account `app-client` with a generated, pinned certificate
  2. starts the HTTP listener on a free port, and the mTLS listener with a generated server certificate
  3. writes `target/test-server/server.json` with both URLs and the paths of the CA, client certificate and key
  4. serves a control endpoint on another loopback port, `POST /stop`, which the build calls after the tests

  The server runs with `fdrop-search` (in-process extraction), so search is tested for real. The .NET tests find it through `FDROP_TEST_SERVER=target/test-server/server.json`, and skip themselves when it is not set. Bearer tokens are the fake provider's (`user:alice`). Each test works in a folder of its own under `/home`, so tests can run in parallel and against a server that earlier runs used, with nothing to reset. A test checks that a session was discarded by asking for it through the public API (`GET /api/v1/uploads/{id}` answers `ABORTED`, or `404`), with the session id read by its fault-injecting handler.
- **Integration tests** cover the same ground as the Java client's:
  - multi-chunk uploads from a file and from a seekable and a non-seekable stream, and an empty file, downloaded and compared byte for byte
  - a short stream refused with its session discarded
  - refusals as `FdropApiException` (`409` without overwrite, `403` for a read-only member, `404`)
  - files, expiry and folders
  - the application account's certificate login on the mTLS port, and an untrusted server certificate failing at once
  - every chunk's first try cut off by a fault-injecting `DelegatingHandler` and sent again
  - a chunk that always fails giving up after `MaxAttempts` with no open session left
  - cancellation mid-upload leaving no open session and throwing `OperationCanceledException`
  - `ParallelChunks` respected: the handler counts concurrent chunk `PUT`s
  - a chunk answered `409` checked against the session status, both when the server has it and when it must be sent again
  - a `202` from `complete` polled until `COMPLETED`, and the file read back from its folder
  - search finding a file's contents for its owner and not for another user, forward-job errors, and every way of reading a download stream
- **Coverage.** `coverlet.msbuild` measures the library over both runs (the integration run merges the unit run's results) into `target/coverage/coverage.cobertura.xml`. Generated code (the JSON and logging source generators) and the response records in `Models.cs` are left out. The build fails below 85% line coverage.

### Build

The module is part of the Maven reactor, after `fdrop-http-client-java`, so `./mvnw verify` builds and tests both clients. `pom.xml` (packaging `pom`) runs the `dotnet` CLI with `exec-maven-plugin`, the way `fdrop-web` runs npm:

| Maven phase | Runs |
| --- | --- |
| `validate` | `dotnet --version`, failing with a clear message when the SDK from `global.json` is missing |
| `compile` | `dotnet restore --locked-mode`, `dotnet build -c Release --no-restore` |
| `test` | `dotnet test --filter Category=Unit` |
| `pre-integration-test` | starts `ClientTestServer` in the background and waits for `server.json` |
| `integration-test` | `dotnet test --filter Category=Integration` with coverage and TRX results; a failing test does not stop the build here |
| `post-integration-test` | stops the test server |
| `verify` | fails the build when the TRX results show failures or errors, or line coverage is below 85%. Judged here, as failsafe does, so the server is always stopped first |
| `package` | `dotnet pack -c Release -p:Version=<mapped version>`; the `.nupkg` lands in `target/` |

- `-Ddotnet.skip` skips the module, for machines without the .NET SDK. CI never sets it.
- Developers can also work in the folder directly with `dotnet build` and `dotnet test`. The unit tests need nothing else. For the integration tests, `./mvnw -pl fdrop-http-client-dotnet test-compile exec:exec@test-server` runs the test server in the foreground until Ctrl-C.
- Build output goes under the module's `target/` (`bin`, `obj`, coverage, results and the `.nupkg`), so `mvn clean` removes it and nothing new needs ignoring.
