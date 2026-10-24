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

| Exit code | Meaning |
| --- | --- |
| 0 | Uploaded (or `whoami` succeeded) |
| 1 | Usage error: bad option, missing file, unreadable certificate or key |
| 2 | Authentication failed: TLS handshake refused, untrusted server certificate, or 401 |
| 3 | Target exists and `--overwrite` was not given |
| 4 | Not allowed: no write permission, method or source refused, quota exceeded, or no such folder |
| 5 | Gave up retrying; the session is kept for a later run |
| 6 | Server-side failure: assembly failed, hash mismatch or an unexpected response |

Keys must be PKCS#8 (`BEGIN PRIVATE KEY` or `BEGIN ENCRYPTED PRIVATE KEY`) or PKCS#12. Convert a traditional
OpenSSL key with `openssl pkcs8 -topk8 -nocrypt -in old.key -out app.key`.
