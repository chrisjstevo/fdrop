# File expiry

[fdrop specification](../SPEC.md)

Any file can be given an **expiry**: a duration such as `1hour`, `6 days` or `2weeks`. When it has passed, fdrop deletes the file automatically. An expiry can be given with a browser, API or CLI upload, comes from the folder's default otherwise, and can be set over SFTP with a small control file (below). Users with write access can change or clear it later, within the limits the folder owner and the admins set.

- **The clock starts when the file is complete:** a resumable upload reaching `COMPLETED`, a simple `PUT` finishing, or an SFTP write handle closing. A 4 GB upload that takes an hour with expiry `1day` lives for a day after it arrived, not 23 hours. An expiry changed later counts from the moment of the change.
- **fdrop stores an instant**, `expires_at` in UTC, worked out once. Later changes to folder defaults or limits do not move it (except when an owner asks for that; see Defaults and limits).
- **The deadline is hard.** From `expires_at`, fdrop treats the file as gone over every protocol, even if the bytes have not been deleted yet: listings leave it out, downloads and SFTP opens return not found, and a transfer still running is cut off at its next read. Physical deletion follows within seconds (see Deletion).
- **Files only.** Folders have no expiry of their own; a folder emptied by expiry stays until someone deletes it. Dropping a folder in the browser gives each file in it the chosen expiry.
- **Forward folders don't take a per-file expiry.** Their files already leave fdrop when sent. The "After sending: keep N days" setting (see Per-folder settings) is carried out by the same mechanism: when the file's last job reaches `SENT` (every destination has it), fdrop sets the file's expiry to N days from then. An unsent file never expires, so expiry cannot lose a file that was never delivered.

## Expiry syntax

One syntax is used everywhere a person or script types an expiry: the web UI, the API, the CLI, the SFTP control file and the `expiry.*` limit keys in config.

```abnf
; ABNF (RFC 5234); string literals are case-insensitive
expiry   = *WSP ( duration / "never" ) *WSP
duration = count *WSP unit
count    = %x31-39 *5DIGIT           ; 1 to 999999: no zero, sign, fraction or leading zero
unit     = second / minute / hour / day / week / month / year
second   = "s" / "sec" / "secs" / "second" / "seconds"
minute   = "min" / "mins" / "minute" / "minutes"
hour     = "h" / "hr" / "hrs" / "hour" / "hours"
day      = "d" / "day" / "days"
week     = "w" / "wk" / "wks" / "week" / "weeks"
month    = "month" / "months"
year     = "y" / "yr" / "yrs" / "year" / "years"
```

- Valid: `1sec`, `1 sec`, `90s`, `1min`, `15 minutes`, `1hour`, `36h`, `1day`, `6 days`, `6days`, `2weeks`, `1 month`, `1year`, `10y`, `never`, ` 6 Days `. Singular and plural units are interchangeable whatever the number (`6 day` and `1 days` are both accepted). Spaces and tabs are allowed around the number and unit, so `6  days` is accepted too.
- **One number and one unit.** No compound values (`1day 6hours`: write `30 hours`) and no fractions (`1.5 days`: write `36 hours`). ISO 8601 durations (`P6D`) are not accepted, so there is only one syntax to learn and document.
- **No `m`.** It could mean minutes or months, so it is refused with a hint to write `min` or `month`.
- **What each unit means.** Seconds, minutes, hours, days and weeks are fixed lengths: a day is always 86,400 s and a week 7 days, so daylight saving changes nothing. Months and years are calendar units, added to the start instant in UTC with `java.time` rules: the day of month is kept and clamped to the month's last day, so 31 January plus `1month` is 28 or 29 February, and 29 February plus `1year` is 28 February.
- **`never`** means no expiry. It is how a folder default is overridden at upload and how an expiry is cleared later. It is refused where a maximum applies.
- **Limits.** The parsed value must be at least 1 second (guaranteed by the grammar) and no longer than the maximum that applies to the target folder (see Defaults and limits). The server-wide ceiling `expiry.max` defaults to `10y` and may not be set above `100y`, so a parsed value never comes near the range of `java.time.Instant`.
- **Canonical form.** fdrop echoes and stores the value as `<count> <unit>`, singular for 1 and plural otherwise, with the unit spelled in full (`1 day`, `6 days`, `90 seconds`). This form is what the UI, API responses, CLI output and audit log show.
- **One implementation per runtime, one set of test cases.** The server parser lives in `fdrop-core` (`ExpirySpec.parse`). `fdrop-web` has a TypeScript copy for instant feedback in forms, and `fdrop-cli` its own copy for checking options before connecting, since it does not depend on `fdrop-core`. All three run the same table of valid and invalid inputs, expected results and error messages, `fdrop-core/src/test/resources/expiry-syntax-cases.json`. The server always parses again; the client copies only help.

**Errors.** A value that does not parse is refused before any bytes are accepted: HTTP `400` with problem type `invalid-expiry`, the CLI exits with code 1 before connecting, and an SFTP control file fails its close (see Over SFTP). `detail` says what was wrong:

| Input | `detail` |
| --- | --- |
| (empty, or only spaces) | Expiry is empty. Use a number and a unit, such as `6 days`, or `never` |
| `6` | `6` has no unit. Add one of sec, min, hour, day, week, month, year |
| `6 dys` | Unknown unit `dys`. Use one of sec, min, hour, day, week, month, year |
| `5m` | `m` is ambiguous. Write `5 min` or `5 months` |
| `0 days`, `-1 day`, `007 days` | The number must be a whole number from 1 to 999999 without leading zeros. To keep a file forever use `never` |
| `1.5 days` | Fractions are not supported. Use a smaller unit, such as `36 hours` |
| `1day 6hours` | Only one number and one unit are allowed, such as `30 hours` |
| `1000000 sec` | The number must be a whole number from 1 to 999999 |

A value that parses but is too long gets problem type `expiry-too-long` (`400`), naming the limit and who set it: "`2 years` is longer than this folder's maximum of `1 year`, set by the folder owner", or "…the server maximum of `10 years`". `never` where a maximum applies gets the same type: "Files in this folder must expire within `30 days`". An expiry for a forward folder file gets `expiry-not-allowed` (`400`), with a pointer to the folder's "After sending" setting.

## Defaults and limits

Each upload's expiry is resolved in this order, first match wins:

1. The expiry given with the upload (form choice, API field or header, CLI option, SFTP control file), including `never`.
2. The folder's **default expiry**: the managed folder's for files under `/shared/<name>/`, or for `/home` the user's own default, or else the server's `expiry.home.default`. Ordinary sub-folders use the default of the managed folder or home they are in.
3. The folder's **maximum expiry**, if one is set: a folder with a maximum and no default gives every file the maximum.
4. Otherwise the file never expires.

The result must not be longer than the **effective maximum**, the shortest of: the folder's maximum (shared folders), `expiry.home.max` (homes) and `expiry.max` (everywhere). An explicit value over the limit is refused, never shortened without telling the user. A default over the limit, possible only when a limit was lowered after the default was set, is shortened to the limit; the UI warns the owner about the default that no longer fits.

| Setting | Where | Set by | Notes |
| --- | --- | --- | --- |
| `expiry.max` | Config | Operator | Server-wide ceiling for every expiry and every folder setting. Default `10y`, at most `100y` |
| `expiry.home.default` | Config | Operator | Default for `/home` uploads of users who set none. Default `null` (never expires) |
| `expiry.home.max` | Config | Operator | Longest expiry in any `/home`. When set, every file in a home expires, and `never` is refused there. Default `null` |
| Home default | Account page, `PATCH /api/v1/me` | The user (admins for an application account, on its admin screen) | Must fit `expiry.home.max` and `expiry.max` |
| Shared folder default | Folder settings, `PATCH /api/v1/folders/{id}` | Folder owner | Must fit the folder's maximum and `expiry.max` |
| Shared folder maximum | Folder settings, `PATCH /api/v1/folders/{id}` | Folder owner | When set, every file in the folder expires within it. Shown to members beside the upload control |

- **Lowering a limit is not retroactive by default.** Files already in the folder keep their expiry. With `"applyToExisting": true` on the folder `PATCH` (a checkbox in the UI, "Also shorten the expiry of N files already in this folder that would outlive the new maximum"), every file in the folder whose expiry is later than now plus the new maximum, or that has none, gets now plus the new maximum. This is audited with the number of files changed. Lowering `expiry.max` or `expiry.home.max` in config never changes existing files.
- **Moving and renaming.** The expiry follows the file. A file moved into a folder whose effective maximum it would outlive gets now plus that maximum, and the move response says so (`"expiryShortened": true`), as does the audit entry. Overwriting a file replaces its expiry with the new upload's, resolved as above; nothing is inherited from the replaced file.

## Setting the expiry over HTTP

The same value can be passed in three ways; an upload that gives two different values gets `400` `conflicting-expiry`.

| Upload | Field |
| --- | --- |
| Resumable: `POST /api/v1/uploads` | JSON field `"expiresIn": "6 days"` |
| Simple: `PUT /api/v1/files/content?path=…` | Query parameter `expiresIn=6days` (URL-encode spaces as `%20`) |
| Either | Header `X-Fdrop-Expires-In: 6 days` |

- The value is checked against the target folder's limits when the resumable session is created or the simple `PUT` starts, so a refused expiry costs no transferred bytes. It is stored on the session (`upload_session.expires_in`) and applied at `complete`. If the folder's limits were lowered while the upload ran, the expiry is shortened to fit at `complete` rather than failing a finished transfer, and the response says so.
- Not to be confused with `expiresAt` on the upload session itself, which is when an unfinished session is abandoned (`upload.ttl`; see Resumable chunked uploads).
- **Changing or clearing later:** `PATCH /api/v1/files?path=…` with one of `{ "expiresIn": "2 days" }` (from now), `{ "expiresAt": "2026-11-30T17:00:00Z" }` (RFC 3339, must be in the future and within the limit), or `{ "expiresIn": "never" }` (clear). Anyone with write permission on the folder may do it: the owner of a home, or an owner or read-write member of a shared folder. Read-only members see the expiry but cannot change it. Extending is allowed, within the limits. Forward folder files return `expiry-not-allowed`.
- **Showing it:** every file entry in `GET /api/v1/files` carries `"expiresAt"` (RFC 3339 UTC, or `null`) and `"expirySource"` (`upload`, `folderDefault`, `homeDefault`, `changed`, `forwardKeep`). The listing also carries `"folderExpiry": { "default": "7 days", "max": "30 days" }` for the folder being listed, so the UI can show what an upload will get without another call. Downloads send `X-Fdrop-Expires-At` when the file has an expiry. The upload complete response, the simple `PUT` response and the `PATCH` response return the same file entry.

**Example: simple upload with an expiry, then a change.**

```http
PUT /api/v1/files/content?path=/shared/Invoices/2026-10.pdf HTTP/1.1
Host: fdrop.example.com:8443
Authorization: Bearer eyJhbGciOi…
X-Fdrop-Expires-In: 6 days
Content-Type: application/pdf
Content-Length: 482113

<482113 bytes>
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{ "path": "/shared/Invoices/2026-10.pdf", "size": 482113, "mtime": "2026-10-26T09:14:03Z",
  "type": "file", "expiresAt": "2026-11-01T09:14:03Z", "expirySource": "upload", "expiresIn": "6 days" }
```

The expiry counts from 09:14:03, when the last byte was committed. Two days later a read-write member extends it, and the folder's 30-day maximum is checked against the new value:

```http
PATCH /api/v1/files?path=/shared/Invoices/2026-10.pdf HTTP/1.1
Host: fdrop.example.com:8443
Authorization: Bearer eyJhbGciOi…
Content-Type: application/json

{ "expiresIn": "2weeks" }
```

returns the entry with `"expiresAt": "2026-11-11T…"`, `"expirySource": "changed"`. The same upload done resumably sends `{ "path": "/shared/Invoices/2026-10.pdf", "size": 482113, "expiresIn": "6 days", … }` to `POST /api/v1/uploads`, gets `"fileExpiresIn": "6 days"` echoed in the `201`, and the expiry starts when `complete` returns `COMPLETED`. `{ "expiresIn": "1 decade" }` would have returned `400` with `"type": "…/invalid-expiry"` and `"detail": "Unknown unit 'decade'. Use one of sec, min, hour, day, week, month, year"`.

## In the web UI

- **Upload control.** Beside the drop zone, an "Expires" drop-down shows what new uploads will get, starting at the folder's default ("Expires: folder default, 7 days"). Choices: the folder default, `1 hour`, `1 day`, `7 days`, `30 days`, `Never` (absent when a maximum applies), and "Custom…", a text field using the syntax above, validated as the user types with the same messages as the server. Choices longer than the folder's maximum are disabled, with the maximum shown. The choice applies to files added to the queue after it was made; each queue row shows its file's expiry. Forward folders show no control.
- **File list.** An "Expires" column shows the time left ("in 5 days", "in 40 min"), with the exact date and time in the user's time zone on hover, an hourglass icon, and nothing for files that do not expire. Under an hour, the icon turns coral (never the text; see Visual design). On phones the time left appears under the file's size and date.
- **Set expiry.** A row action, "Set expiry…", opens a dialog with the same choices plus "On a date…" (a date and time picker, sent as `expiresAt`) and "Remove expiry". Read-only members see the expiry but not the action.
- **Folder settings.** The shared folder settings dialog has "Default expiry" and "Maximum expiry" fields (owner only), and the Account page has "Default expiry for files in Home".
- **My uploads.** A new **Expires** column shows the expiry recorded for the upload, and Status gains **Expired**, with the time it was deleted on hover. Expired rows keep no link or download action.
- **Expiry shown on time.** A file that expires while its folder is open disappears from the list at its expiry: the UI schedules a refetch of the folder for the earliest expiry shown. An action on a file that has just expired gets "This file has expired".

## Setting the expiry with the CLI

`fdrop upload` takes `--expires-in <expiry>`, using the syntax above (`--expires-in never` overrides a folder default). It is checked locally before connecting, so a typo exits with code 1 and the parser's message, and is sent as `expiresIn` when creating the upload session. A value over the folder's limit is refused by the server with `400` and also exits with code 1. A saved session resumes with the expiry it was created with; giving a different `--expires-in` for the same file starts a new session. The result line and `--json` output include `expiresAt`.

```bash
fdrop upload nightly-2026-10-26.csv /shared/finance/ --expires-in 2weeks --json
```

```json
{"path":"/shared/finance/nightly-2026-10-26.csv","size":91822345,"sha256":"9f2c…","resumed":false,"expiresAt":"2026-11-09T02:00:41Z","expiresIn":"2 weeks"}
```

`--expires-in` may also go in the CLI config file (`expires-in=7 days`) as a default for every upload of a job. It has no environment variable, since an expiry belongs to an upload, not to a machine.

## Setting the expiry over SFTP

SFTP has no way to send metadata with an upload. Most clients cannot send custom attributes, and file names must stay the names users chose. fdrop therefore offers two mechanisms that work with any client, including scripted `sftp -b` batches:

1. **Folder defaults (the main mechanism).** An SFTP upload with nothing else gets the folder's default and maximum exactly as an HTTP upload does. For most automated drops (a scratch area where everything lives a day, a partner exchange folder kept 30 days) the owner sets a default once and the SFTP side needs nothing.
2. **Control files, for a per-file expiry.** Writing a small file named `<name>.fdrop-expires` (`expiry.sftp.suffix`) into the same directory sets the expiry of `<name>`. Its content is one expiry in the syntax above (at most 256 bytes of UTF-8; surrounding whitespace and a final newline are ignored). fdrop acts on it when its write handle closes and never stores it as a file:
   - **Before the upload**, if `<name>` does not exist yet, the value is held as a pending expiry for that user and path for `expiry.sftp.pendingFor` (default 10 minutes), and applied, as if it had been given with the upload, when `<name>` is next completed by that user over SFTP, from any connection or node. This is the recommended order, since the clock then starts when the file is complete, as for HTTP.
   - **After the upload**, if `<name>` exists, its expiry is changed, counting from now, like `PATCH`. `never` clears it.
   - **Reading** `<name>.fdrop-expires` (for example `get report.pdf.fdrop-expires /dev/stdout`) returns the current expiry as one line, `2026-11-01T09:14:03Z 6 days` or `never`, or `pending 6 days` while a value waits for its file. **Deleting** it (`rm`) clears the expiry, like writing `never`. With neither a file nor a pending value, it does not exist.
   - **Clients that upload to a temporary name** (WinSCP's `.filepart`, `lftp`'s `.in.` prefix) and then rename are covered: renaming onto a control file name counts as writing it, and a pending value is applied when a file is renamed into `<name>` as well as when it is written under that name.

   Control files never appear in listings and are not counted in quotas, but answer `stat` (with the size of their text) so that clients which stat before `get` can read them. They need write permission on the folder, as a `PATCH` does. A value that does not parse, is too long, or targets a forward folder makes the close fail with `SSH_FX_FAILURE` and the same message as the HTTP `detail`; MINA sends it as the status message, and OpenSSH `sftp` and WinSCP print it. The failure is also audited.

Why this choice: per-file settings are rare over SFTP, and most SFTP uploads are unattended jobs where a folder default is simplest and cannot be forgotten. Control files work with every client and every scripting tool because they are plain uploads, can be checked by reading them back, and keep the uploaded file's name unchanged. Alternatives that were rejected: encoding the expiry in the file name (`report.pdf;6days`), which breaks clients that check the name after upload and leaks into the receiver's file names; magic directories (`/home/.expire-6days/`), which change the file's path; and SFTP extended attributes in `SSH_FXP_SETSTAT`, which few clients can send. The suffix `.fdrop-expires` is reserved: no file with that suffix can be created by any protocol (HTTP returns `400` `reserved-name`).

**Example: SFTP upload with an expiry.** An application account, logging in with its SSH key, sends today's report to a shared folder, to be deleted 6 days after it arrives:

```bash
printf '6 days\n' > report.pdf.fdrop-expires
sftp -P 2222 -b - erp-export@fdrop.example.com <<'EOF'
cd /shared/finance
put report.pdf.fdrop-expires
put report.pdf
get report.pdf.fdrop-expires /dev/stdout
EOF
```

```text
sftp> cd /shared/finance
sftp> put report.pdf.fdrop-expires
Uploading report.pdf.fdrop-expires to /shared/finance/report.pdf.fdrop-expires
sftp> put report.pdf
Uploading report.pdf to /shared/finance/report.pdf
sftp> get report.pdf.fdrop-expires /dev/stdout
Fetching /shared/finance/report.pdf.fdrop-expires to /dev/stdout
2026-11-01T09:14:03Z 6 days
```

`ls` shows only `report.pdf`. Had the control file said `6 dys`, the first `put` would have failed with "Unknown unit 'dys'. Use one of sec, min, hour, day, week, month, year", and `sftp -b` would have stopped there before sending the report. With no control file, `report.pdf` would get the `/shared/finance` folder default, say `30 days`.

## Deletion

Expiry deletion is driven by the metadata DB, so it does not depend on which node took the upload and survives restarts.

- **Expiry rows.** Every file with an expiry has one `file_expiry` row, written in the same transaction as the upload's completion (or the change), and moved with the file on rename or move. Files without an expiry have none.
- **Sweeper.** An `ExpirySweeper` runs on every node every `expiry.sweepInterval` (default 30 s). It claims due rows with `SELECT … WHERE expires_at <= now() AND (claimed_until IS NULL OR claimed_until < now()) ORDER BY expires_at LIMIT expiry.batchSize FOR UPDATE SKIP LOCKED` (PostgreSQL; on H2, which is single-node only, plain `FOR UPDATE`), sets `claimed_by` and `claimed_until = now() + expiry.claimTimeout` and commits. Then it deletes each file through the Storage SPI, and for each one, in one transaction, deletes the row, sets the `upload_record` to `EXPIRED` and releases the quota, then writes the audit entry once that has committed. `now()` is the database's clock, so the nodes agree on what is due; the nodes' own clocks are used only for the access check, and NTP is required as for TLS and tokens.
- **Precision.** Access stops exactly at `expires_at` on every node. Physical deletion normally follows within about a second: when a node writes or reads an expiry due within the next `sweepInterval`, it also puts it on an in-memory timer, which fires a claim of that one row. If that node stops, the next sweep on any node does it, within `sweepInterval`. So a `1sec` expiry is gone from listings 1 s after the upload and from storage a second or so later. A large backlog is worked through at up to `expiry.maxDeletesPerSecond` (default 200) per node so it cannot starve S3 or the DB.
- **Restarts and downtime.** Nothing is kept only in memory. At startup the sweeper runs at once; files that expired while fdrop was down are already hidden by the access check and are deleted in `expires_at` order. A node that crashes mid-batch leaves claims that lapse after `claimTimeout` (default 5 min) and are picked up by another node or by itself after restart.
- **Several nodes.** `SKIP LOCKED` lets every node sweep without two deleting the same file. Deleting is idempotent: a file already gone (deleted by a user, by another node before a crash, or by hand) counts as deleted (`outcome="already_gone"`). The `upload_record` update commits with the row delete and the audit line is written only after that commit, so each expired file is recorded once; a crash between the two can lose that one audit line, but never the `EXPIRED` status.
- **Failures.** A storage error leaves the row in place with `attempts` and `last_error`, retried with backoff (1 min doubling to 1 h) for as long as it takes, and counted in metrics. The file stays hidden meanwhile. A file that is still not deleted an hour after its expiry raises the `FdropExpiryOverdue` alert.
- **Local backend.** `Files.deleteIfExists` on the resolved path; the parent folders stay.
- **S3 backend.** Due rows are deleted in batches with `DeleteObjects` (up to 1,000 keys per request), with each key's result handled one by one. On a versioned bucket, `DeleteObject` only adds a delete marker; operators who need expiry to remove the bytes must add a lifecycle rule for noncurrent versions (`NoncurrentVersionExpiration`) to the bucket, and fdrop logs a warning at startup when bucket versioning is on. S3's own lifecycle expiration is not used for expiry: it works in whole days, runs at an unspecified time once a day, and would delete files fdrop still lists.
- **Unfinished uploads** are not affected; abandoned upload sessions are cleaned by the upload janitor as before (`upload.ttl`).
- **Pending SFTP control values** past `expiry.sftp.pendingFor` are deleted by the upload janitor.

## Audit and metrics

Audit entries (JSON lines, as for every other event), each with acting user (or `system`), protocol, client IP, path, folder id and `expiresAt`:

| Event | When | Extra fields |
| --- | --- | --- |
| `file.expiry.set` | An upload completed with an expiry | `expiresIn`, `source` (upload, folderDefault, homeDefault, forwardKeep), `shortened` |
| `file.expiry.changed` | `PATCH`, SFTP control file, move into a shorter maximum, or `applyToExisting` | `from`, `to`, `reason` (user, controlFile, move, folderMaximum) |
| `file.expiry.cleared` | Cleared with `never`, "Remove expiry" or `rm` of the control file | `from` |
| `file.expiry.refused` | A value refused for syntax, length or folder type | `input` (first 64 characters), `problem` |
| `file.expired` | The sweeper deleted the file | `size`, `backend`, `uploadedBy`, `lagSeconds` (deletion time minus `expires_at`), `outcome` (deleted, already_gone) |
| `file.expiry.deleteFailed` | A delete attempt failed | `attempts`, `error` |
| `folder.expiry.changed` | A folder or home default or maximum changed | `default`, `max`, `applyToExisting`, `filesChanged` |

Metrics are listed under Observability: `fdrop_expiry_set_total`, `fdrop_expiry_refused_total`, `fdrop_expiry_deleted_total`, `fdrop_expiry_delete_lag_seconds`, `fdrop_expiry_files` and `fdrop_expiry_overdue_files`, with the alerts `FdropExpiryOverdue` and `FdropExpiryDeleteErrors`.

## Metadata

- `file_expiry(id, storage_key UNIQUE, user_sub, folder_id, expires_at, expires_in, source UPLOAD|FOLDER_DEFAULT|HOME_DEFAULT|CHANGED|FORWARD_KEEP, set_by_sub, set_via HTTP|SFTP, set_at, upload_record_id, claimed_by, claimed_until, attempts, last_error, next_attempt_at)`, indexed on `(expires_at)`. `user_sub` is the home's owner for files under `/home`, `folder_id` the managed folder otherwise; `expires_in` is the canonical text, kept for display and audit.
- `folder` gains `default_expiry` and `max_expiry` (canonical text, nullable); `user` gains `home_default_expiry`.
- `upload_session` gains `expires_in`.
- `sftp_pending_expiry(user_sub, storage_key, expires_in, created_at)`, primary key `(user_sub, storage_key)`.
- `upload_record` gains `expires_at` and the status `EXPIRED` (above).
