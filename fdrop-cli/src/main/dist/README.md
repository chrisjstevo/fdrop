# fdrop command line client

`fdrop` uploads files to fdrop unattended, signed in as an application account with a client certificate. It
needs Java 21 or later.

```bash
./fdrop whoami --url https://fdrop.example.com:8444 --cert app.crt --key app.key
./fdrop upload report.csv /shared/finance/ --url https://fdrop.example.com:8444 --cert app.crt --key app.key
```

Large uploads are sent in chunks and survive network drops: the client retries for up to an hour (`--retry-for`)
and remembers the upload in `~/.local/state/fdrop/uploads/`, so running the same command again after an interrupt
resumes where it stopped.

Options can also come from environment variables (`FDROP_URL`, `FDROP_CERT`, `FDROP_KEY`, `FDROP_CA`, …) or from
`~/.config/fdrop/cli.properties` (keys are the option names without dashes; the file must be `chmod 600`).
Passwords are only ever read from files, never from the command line. Run `./fdrop upload --help` for every option.

## Watching a folder

`fdrop watch` keeps running and uploads every new file that appears in one local folder into one folder on the
server, each the way `fdrop upload` would send it:

```bash
./fdrop watch /srv/outbox /shared/finance/incoming/ --recursive --after-upload move=/srv/sent \
    --url https://fdrop.example.com:8444 --cert app.crt --key app.key
```

- **Both folders are required:** the local one as the first argument or `--local-dir` (`$FDROP_WATCH_LOCAL_DIR`,
  `watch.localDir` in the config file), and the server one, ending in `/`, as the second argument or `--remote-dir`
  (`$FDROP_WATCH_REMOTE_DIR`, `watch.remoteDir`). The server folder must exist and be writable by the account; the
  watcher checks at the start and exits with code 4 if not.
- **A file is uploaded once it has stopped changing** for `--settle` (10 s), or with `--ready-marker .done` once
  `<name>.done` exists. Hidden and temporary names (`*.tmp`, `*.part`, …) are skipped; `--include` and `--ignore`
  take globs.
- **After an upload** the file is kept (`--after-upload keep`, the default), deleted, or moved (`move=<dir>`).
  A ledger in `~/.local/state/fdrop/watch/` stops a kept file going up again after a restart. Files the server
  refuses are recorded and not retried until they change; `--on-failure move=<dir>` moves them aside with a
  `.error.txt`.
- **Running as a service:** `--json` logs one JSON object per line, `--status-file` writes counts for monitoring,
  and `SIGTERM` stops cleanly (exit 0), keeping unfinished uploads for the next start. `fdrop-watch@.service` in this
  archive runs one watcher per `/etc/fdrop/watch/<name>.properties`; see the comments at its top.
- **Network file systems** (NFS, SMB) do not report new files: use `--poll 30s`.

Run `./fdrop watch --help` for every option.

| Exit code | Meaning |
| --- | --- |
| 0 | Uploaded (or `whoami` succeeded) |
| 1 | Usage error: bad option, missing file, unreadable certificate or key |
| 2 | Authentication failed: TLS handshake refused, untrusted server certificate, or 401 |
| 3 | Target exists and `--overwrite` was not given |
| 4 | Not allowed: no write permission, method or source refused, quota exceeded, or no such folder |
| 5 | Gave up retrying; the session is kept for a later run |
| 6 | Server-side failure: assembly failed, hash mismatch or an unexpected response |
| 7 | `watch`: another watcher runs with the same settings, or the watched folder went away |

Keys must be PKCS#8 (`BEGIN PRIVATE KEY` or `BEGIN ENCRYPTED PRIVATE KEY`) or PKCS#12. Convert a traditional
OpenSSL key with `openssl pkcs8 -topk8 -nocrypt -in old.key -out app.key`.
