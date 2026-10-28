# HTTP API and browser UI

[fdrop specification](../SPEC.md)

The HTTP side is a Netty 4.2 server on `http.port` (default 8443), HTTPS only, serving a JSON REST API under `/api/v1` and a static single-page UI at `/`. Request bodies are never aggregated: upload handlers consume `HttpContent` frames and write them straight to the storage `WriteHandle` with backpressure (auto-read off while a write is in flight).

## Pipeline

1. `SslHandler` (optional; ALPN for HTTP/2 later). The application mTLS listener on `http.mtls.port` always has one, with client authentication required
2. `HttpServerCodec` with max header size 16 KiB
3. `HttpServerKeepAliveHandler`
4. `IdleStateHandler` (read idle 60 s)
5. `AuthHandler` (session cookie, bearer token, or for application accounts a client certificate or HTTP Basic; rejects before the body is read)
6. `Router` → handler per route; upload and download handlers are streaming, all other routes use a `HttpObjectAggregator` limited to 1 MiB
7. `ChunkedWriteHandler` for downloads

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/auth/login` | Redirect to Keycloak (authorization code + PKCE); `?action=configure-totp`, `register-security-key` or `recovery-codes` starts that Keycloak action and returns to the Account page |
| GET | `/auth/callback` | OIDC redirect target; creates the session cookie |
| POST | `/auth/logout` | End session and redirect to Keycloak end-session endpoint |
| POST | `/auth/backchannel-logout` | Keycloak back-channel logout (logout token) |
| GET/PATCH | `/api/v1/me` | Current user: username, roles, quota, usage, two-factor status, home default expiry; `PATCH` changes the home default expiry |
| DELETE | `/api/v1/me/two-factor/{credentialId}` | Remove one of the user's own second factors (needs a two-factor session; the last required factor cannot be removed) |
| GET/POST/DELETE | `/api/v1/me/ssh-keys` | Manage the user's SFTP public keys |
| GET | `/api/v1/search?q=&scope=&path=&type=&modifiedFrom=&sort=&cursor=&limit=` | Search names and contents of every file the caller can see (see Search) |
| GET | `/api/v1/search/suggest?q=` | Up to 8 name matches for the search box drop-down |
| GET | `/api/v1/admin/audit?q=&group=&event=&user=&ip=&outcome=&from=&to=&cursor=` | Read and search the audit log; also `/{id}`, `/export`, `/events`, `/verify` and `/index` (admin; see Audit log) |
| GET/POST | `/api/v1/admin/search`, `/api/v1/admin/search/rebuild` | This node's search index status; rebuild it (admin) |
| GET | `/api/v1/me/uploads?wentTo=&q=&from=&to=&sort=&cursor=` | The My uploads list: the user's own upload records, filtered, sorted and paged, with count and total size for the filter |
| GET | `/api/v1/files?path=/home/docs` | List a directory in the virtual tree (name, size, mtime, type, `expiresAt`, `expirySource`), with the folder's default and maximum expiry |
| GET | `/api/v1/files/content?path=…` | Download; supports `Range`, `If-None-Match`, `Content-Disposition: attachment` |
| PUT | `/api/v1/files/content?path=…&expiresIn=…` | Simple single-request upload for files ≤ `http.simpleUploadMax` (default 64 MiB), optionally with an expiry (or header `X-Fdrop-Expires-In`; see File expiry) |
| PATCH | `/api/v1/files?path=…` | Set, change or clear a file's expiry (`expiresIn` or `expiresAt`) |
| POST | `/api/v1/files/mkdir` | Create an ordinary sub-folder in `/home` or a shared folder |
| POST | `/api/v1/files/move` | Rename or move; the expiry moves with the file |
| DELETE | `/api/v1/files?path=…` | Delete file or empty directory |
| POST | `/api/v1/uploads` | Start a resumable upload, optionally with an expiry (`expiresIn`) |
| GET | `/api/v1/uploads/{id}` | Upload status and received chunks |
| PUT | `/api/v1/uploads/{id}/chunks/{index}` | Upload one chunk |
| POST | `/api/v1/uploads/{id}/complete` | Assemble chunks into the final file |
| DELETE | `/api/v1/uploads/{id}` | Abort and discard |
| GET | `/api/v1/folders` | Shared and forward folders the user owns or belongs to |
| POST | `/api/v1/folders` | Create a shared folder, or a forward folder with `destinationIds` (one or more, from the user's assigned list; a single `destinationId` is still accepted) |
| GET/PATCH/DELETE | `/api/v1/folders/{id}` | Read, rename, change destinations (`destinationIds`, with `sendExisting` for added ones), change keep days, change default and maximum expiry, delete (owner) |
| PUT/DELETE | `/api/v1/folders/{id}/members/{username}` | Add or change a member's permission, remove a member (owner; a member may remove themselves) |
| POST | `/api/v1/folders/{id}/owner` | Transfer ownership to a member |
| GET | `/api/v1/folders/{id}/forward-jobs` | Forward job status for the folder: one job per file and destination, with the destination's name |
| POST | `/api/v1/folders/{id}/forward-jobs/{jobId}/retry` | Retry a failed job (owner) |
| POST | `/api/v1/folders/{id}/forward-jobs/{jobId}/cancel` | Give up on a failed job's destination for that file, with a reason (owner) |
| GET | `/api/v1/forward-destinations` | The caller's assigned, enabled destinations (id, name, description) for the drop-down |
| GET | `/api/v1/users?search=` | Find standard users and application accounts to add as members (username, display name and `kind` only) |
| GET/POST | `/api/v1/admin/users` | List or create users (admin) |
| DELETE | `/api/v1/admin/users/{id}` | Delete a user (admin) |
| POST | `/api/v1/admin/users/{id}/two-factor/reset` | Delete a user's second factors and end their sessions (admin) |
| GET/POST | `/api/v1/admin/app-accounts` | List or create application accounts (admin) |
| GET/PATCH/DELETE | `/api/v1/admin/app-accounts/{id}` | Read, change description, responsible person, allowed methods and source addresses, home default expiry, enable/disable, delete (admin) |
| POST | `/api/v1/admin/app-accounts/{id}/password` | Generate a new password, returned once (admin) |
| POST/DELETE | `/api/v1/admin/app-accounts/{id}/certificates[/{certId}]` | Add a pinned or by-subject client certificate, or retire one (admin) |
| POST/DELETE | `/api/v1/admin/app-accounts/{id}/ssh-keys[/{keyId}]` | Add or remove an SSH public key or user certificate (admin) |
| GET/POST | `/api/v1/admin/forward-destinations` | List or create destinations (admin) |
| GET/PATCH/DELETE | `/api/v1/admin/forward-destinations/{id}` | Read, change, enable/disable, delete when unused (admin) |
| POST | `/api/v1/admin/forward-destinations/{id}/test` | Connect with mutual authentication and report the host key or certificate seen and whether login succeeded (admin) |
| POST | `/api/v1/admin/forward-destinations/{id}/keys` | Generate a new client key, or upload a user certificate for one; activate and retire keys (admin) |
| PUT/DELETE | `/api/v1/admin/forward-destinations/{id}/assignments/{username}` | Assign a destination to a user or remove the assignment (admin) |
| GET | `/api/v1/admin/storage/encryption` | Encryption status: provider, primary key id, objects per key id, plaintext objects left, re-encryption job progress (admin) |
| POST | `/api/v1/admin/storage/encryption/inventory` | Count stored objects per key id now, instead of waiting for the daily count (admin) |
| POST/DELETE | `/api/v1/admin/storage/encryption/reencrypt` | Start a re-encryption job (`fromKeyId`, or `plaintext: true`), or cancel the running one (admin) |
| GET | `/healthz`, `/readyz`, `/metrics` | Not on this port: served on the management port (8081); see Observability |

Errors use RFC 9457 problem JSON (`type`, `title`, `status`, `detail`); expiry problems (`invalid-expiry`, `expiry-too-long`, `expiry-not-allowed`, `conflicting-expiry`, `reserved-name`) are listed under File expiry. A search that is too long or too broad returns `invalid-search` (see Search). Downloads use `DefaultFileRegion` (zero-copy) only for unencrypted files on the local backend without TLS, and `ChunkedNioStream` over the backend channel otherwise, which with encryption at rest is every file; `Range` requests return 206 with one range (multi-range returns 200 with the full body).

## Browser UI

- Built with React 19 and TypeScript; no hand-written DOM JavaScript or other UI framework. Function components and hooks only.
- Built with npm: `package.json` and a committed `package-lock.json` in `fdrop-web`; `npm ci` installs, `npm run build` produces the bundle with Vite, `npm test` runs Vitest. The output is bundled into the jar as static resources; no server-side rendering.
- Libraries: React Router for navigation, TanStack Query for API calls and caching; no global state library unless a need appears.
- File browser: breadcrumb, list view, a drop zone for drag-and-drop upload (below), a file picker button, download, rename, delete, new folder, and each file's expiry with a Set expiry action (see File expiry).
- Search: the top bar's search box suggests matching names as you type and opens a results page of name and content matches on Enter (see Search).
- Upload queue panel showing each file's percentage, bytes sent, speed and time remaining, with pause/resume/cancel and a retry state when offline (below).
- Uploads run in a Web Worker using `Blob.slice()` and `XMLHttpRequest` (which, unlike `fetch`, reports upload progress events), up to 3 chunks in parallel per file; React components subscribe to worker progress through a small hook (`useUploadQueue`).
- In-progress uploads are recorded in IndexedDB (upload id, file name, size, `lastModified`, chunk size) so a reload offers to resume once the user re-selects the same file.
- CSRF: all state-changing requests send an `X-Fdrop-CSRF` header matching a token from the session.
- Local UI development: `npm run dev` starts the Vite dev server on https://localhost:5173 (with the dev certificate) and proxies `/api` and `/auth` to the fdrop server on https://localhost:8443.

## Drag-and-drop drop zone

- Every writable folder view (`/home` and its sub-folders, shared folders with write access, forward folders) shows a drop zone: a dashed strip below the file list, with the fdrop logo mark as a faint background, reading "Drop files anywhere, or choose files, to upload to <folder>", which also works as a button that opens the file picker. The sidebar's Upload button opens the same picker for the folder being shown.
- While files are dragged over the window, the whole file list becomes the drop target, highlighted, and names the folder the files will land in ("Drop to upload to /shared/Invoices"). Dropping on a sub-folder row uploads into that sub-folder.
- Dropping a folder uploads its contents with their sub-folder structure (via `DataTransferItem.webkitGetAsEntry()`), creating the sub-folders first. Forward folders accept files only; a dropped folder there is refused with a message.
- Many files can be dropped at once; each becomes its own entry in the upload queue. Files over `limits.maxFileSize` or beyond the user's remaining quota are rejected at drop time with the reason shown, before any bytes are sent.
- Where dropping is not allowed (`/`, `/shared`, `/forward`, read-only shared folders) the cursor shows not-allowed and nothing is uploaded.
- Drops outside the drop target never make the browser open the file in place.
- Keyboard and screen-reader users reach the same function through the drop zone's button; touch devices without drag show it as "Tap to choose files".

## Upload progress as a percentage

- Each queued file shows a progress bar with a whole-number percentage ("42%"), its bytes sent of its total ("1.7 GB of 4.0 GB"), current speed and estimated time remaining. The queue header shows the overall percentage across all active uploads, by bytes.
- Percentage = (bytes in chunks the server has acknowledged + bytes sent so far in chunks still in flight) ÷ file size, from the worker's `XMLHttpRequest` `upload.onprogress` events, updated at most 4 times a second.
- The percentage never goes down: if a chunk fails and is resent, the shown value holds until the resend catches up.
- It stops at 99% while the server assembles the file, shown as "Finishing…", and turns to 100% and "Done" only when `complete` succeeds. In a forward folder the row then shows the forward status (queued, sending, sent, failed), with "2 of 3 sent" while a folder with several destinations is part-way.
- After a reload or a dropped connection, the bar resumes from the server's count of received chunks, not from 0%.
- Paused, offline and failed uploads keep their percentage and show the state beside it.
- Accessibility: the bar is a `progressbar` with `aria-valuenow` set to the percentage, and a polite live region announces each file every 25% and on completion.
- Files uploaded over SFTP do not appear in the browser queue; their progress is shown by the SFTP client.

## My uploads page

Every user has a **My uploads** page, reached from the main navigation, listing every file they have uploaded: its name, its size, and whether it went to a normal folder or a forward folder. It covers uploads over both the browser and SFTP, and keeps a row after the file has been forwarded, moved, deleted or expired.

| Column | Shows |
| --- | --- |
| File | File name; a link to its folder while the file still exists and the user can still reach it |
| Size | Size at upload, in KB, MB or GB (exact bytes on hover) |
| Went to | **Folder** badge with the folder (`Home`, `Home / reports`, `Shared: Invoices`), or **Forward** badge with the forward folder and its destination names (`Forward: Partner A`, or `Forward: Partner A, Partner B` for several) |
| Uploaded | Date and time the upload completed, in the user's time zone |
| Via | Browser or SFTP |
| From IP | IP address of the machine that uploaded the file (IPv4, or IPv6 in compressed form). If a resumable upload was started from one address and finished from another, the finishing address is shown with "started from 203.0.113.7" beneath it |
| Expires | The expiry the file was given, as a date and time in the user's time zone, or nothing |
| Status | Folder uploads: Stored, Moved, Deleted or Expired. Forward uploads: Queued, Sending, Sent, Failed or Paused, worked out from the file's jobs (see Several destinations), with "2 of 3 sent" while part-way; the details list each destination's state |

- Newest first by default; sortable by date, size and name.
- Filters: Went to (All, Folders, Forward), date range, and a search box that matches file names or an IP address (exact, or a prefix such as `10.20.`).
- Above the table: number of files and total size for the current filter ("1,284 files, 37.2 GB").
- 50 rows per page with "Load more", using a cursor so new uploads don't shift the pages.
- Row actions: download, while the file still exists and the user still has access; open the folder; and for a failed forward the folder owner sees Retry, and Stop sending, per failed destination.
- On phones the table becomes cards showing name, size, the Folder or Forward badge, date and status; tapping a card shows the IP address and protocol. Filters move into a sheet.
- Badges follow the visual design: Folder in navy outline, Forward in deep navy fill with white text, Sent and Stored on mint.
- Only the user's own uploads appear. Admins see their own uploads here too, not other users'.
- Leaving a shared folder or losing a destination assignment keeps the rows but removes the links and download action.
- **Which IP is recorded.** For browser uploads, the address of the HTTP client. When fdrop sits behind a reverse proxy or load balancer, the client address is taken from the `Forwarded` or `X-Forwarded-For` header, but only when the connection comes from an address in `http.trustedProxies`; the rightmost address not in that list is used, so a client cannot fake its address by sending the header itself. For SFTP, the address of the SSH connection; behind a TCP load balancer, fdrop accepts the HAProxy PROXY protocol (v1 and v2) from `trustedProxies` when `sftp.proxyProtocol` is on. A resumable upload records the address that created the session and the address that completed it.
- **Privacy.** IP addresses are personal data. Users see only the addresses on their own uploads; the address is kept no longer than the row (`upload.historyRetention`), is deleted with a deleted user's rows, and never appears in metrics labels.
- Rows older than `upload.historyRetention` (default 365 days) are deleted by the nightly janitor. A deleted user's rows are deleted with them.

**Data.** When an upload completes (resumable `complete`, simple `PUT`, or an SFTP write handle closed), fdrop writes one `upload_record` row, including the client IP addresses. Moves, renames, deletes and expiry update its current path, expiry and status, and forward jobs link to it so its status follows the job. The page reads only from this table, so it stays fast regardless of storage backend.

## Responsive web app

The UI is one responsive web app that works on phones, tablets and desktops from the same code and URL; there is no separate mobile site or native app.

| Width | Layout |
| --- | --- |
| Under 640 px (phone) | Single column; navigation in a menu button; files as list rows (name, size, date) with actions in an overflow menu; upload queue as a bottom sheet |
| 640–1024 px (tablet) | Navigation as a collapsible drawer; files as a compact table; dialogs as side panels |
| Over 1024 px (desktop) | Workspace layout: a light fixed sidebar (logo, Upload and New folder, Home, Shared, Forward, My uploads, Manage folders, an Admin group, storage meter) beside a white top bar with the search box and the account menu; full file table with sortable columns; upload queue as a docked panel |

- Mobile-first CSS with breakpoints at 640 px and 1024 px, and container queries for components such as the file table and upload queue; usable down to 320 px wide with no horizontal page scrolling.
- Styling with CSS Modules and CSS custom properties for theme tokens (light and dark follow the system setting until the user picks one with the switch in the top bar; see Visual design); Radix UI primitives for accessible menus, dialogs and drop-downs, including the forward destination multi-select.
- Touch: tap targets at least 44 × 44 px; the drop zone accepts drag-and-drop wherever the device supports it (desktop, and tablets with drag such as iPad), and is always also a button that opens the file picker, including the phone's photo library and camera. On phones the upload percentage stays visible in the collapsed bottom sheet.
- Admin screens (users, forward destinations, assignments) work on tablets and phones too; long forms become a single column.
- Mobile browsers suspend background tabs, so an upload pauses when the user switches away and resumes through the resumable upload protocol when they come back; the queue shows this as paused, not failed.
- Accessibility target: WCAG 2.2 AA, full keyboard use, and correct behaviour at 200% browser zoom.

## Visual design

The UI uses the same fonts, colours and component styles as [lme.com](https://www.lme.com/): Nunito Sans, LME navy text, deep navy bars and buttons, coral accents and pill-shaped buttons. Values were read from lme.com's live styles on 2026-10-22. fdrop keeps its own name and wordmark; showing the LME logo or name needs approval from LME's brand owners.

All values live as CSS custom properties in `fdrop-web/src/theme/tokens.css`; components never hard-code colours or sizes.

**Logo**

<img src="fdrop-web/src/assets/logo/fdrop-logo.svg" alt="fdrop logo: a white capital F inside a deep navy circle with a coral ring" width="128">

- The fdrop logo is a large capital F inside a circle: a deep navy disc, a thin coral ring, and a white geometric F drawn as a path, so it needs no font and renders the same everywhere.
- Two files live in `fdrop-web/src/assets/logo/`: `fdrop-logo.svg` (full colour) and `fdrop-mark-mono.svg` (the ring and F in one colour, for watermarks).
- Uses: the top bar (32 px, beside the name "fdrop"), the favicon (the SVG plus a 32 px PNG fallback), and the drop zone background.
- Minimum size 16 px; at that size the coral ring may vanish, which is acceptable. Keep clear space of a quarter of the logo's width around it.
- **Drop zone background:** the mono mark is centred behind the drop zone text, at 70% of the drop zone's height up to 160 px. It is applied as a CSS mask, so its colour comes from the tokens: `--color-ink` at 8% opacity normally, `--color-coral` at 15% while files are dragged over, and white at 8% in the dark theme. It is decorative (`aria-hidden`) and faint enough not to affect text contrast. On phones, where the drop zone is a short "Tap to choose files" bar, the mark shrinks with it.

**Colours**

| Token | Value | Used for |
| --- | --- | --- |
| `--color-ink` | `#13426B` (LME navy) | Body text, headings, icons, progress bar fill, links |
| `--color-navy-deep` | `#171739` | Top bar, primary buttons, dark-theme background |
| `--color-coral` | `#F4364C` | Accents: active navigation underline, keyboard focus ring, drop-zone highlight border, error and destructive icons and borders |
| `--color-mint` | `#B5E3D8` | Success backgrounds such as "Done" and "Sent" badges, always with navy text |
| `--color-surface` | `#FFFFFF` | Page background |
| `--color-surface-alt` | `#F9F9F9` | Panels, alternate table rows, drop zone background |
| `--color-border` | `#C0CDD8` | Input and secondary-button borders, dashed drop-zone border, progress track |
| `--color-divider` | `rgba(19, 66, 107, 0.2)` | Table and list dividers |
| `--color-muted` | `#696969` | Secondary text such as sizes, dates and speeds |
| `--color-link` | `#3860BE` | Inline text links |

**Typography**

| Role | Style |
| --- | --- |
| Font | Nunito Sans (weights 200, 400, 600, 700), self-hosted from the `@fontsource/nunito-sans` npm package (SIL Open Font License), no font CDN, so the `default-src 'self'` CSP holds |
| Body | 16 px / 24 px, regular, navy |
| Small body, table cells, navigation | 14 px / 21–24 px, regular |
| Page title | 32 px / 40 px, bold (26 px on phones) |
| Section label | 12 px / 16 px, bold, uppercase, 1 px letter spacing |
| Buttons | 14 px, semibold (600) |

**Components**

- **Buttons:** pill shape (24 px radius), 12 px × 32 px padding (shortened to 10 px × 20 px on phones). Primary: deep navy fill, white text. Secondary: transparent, 1 px `#C0CDD8` border, navy text. On dark backgrounds, the secondary button uses a white border and white text.
- **Workspace layout (after Dropbox and Box):** a light sidebar (`--color-sidebar`) with the logo (32 px) and name, a full-width deep navy Upload pill plus a round New folder button, line icons beside each navigation item, and a storage meter at the bottom. The active item is a tinted pill (`--color-selected`) with a small coral dot. The top bar is white with a pill-shaped search field (`--color-search`), which narrows the folder being shown as you type, suggests matching names from everywhere in a drop-down, and searches names and contents on Enter (see Search), and the account avatar. Under 1024 px the sidebar becomes a drawer behind a menu button.
- **File list:** each file or folder has its icon (file, folder, forward arrow) on a 36 px rounded tile; rows are 56 px with a soft hover tint and no zebra stripes; headers are 13 px muted text. Home shows the last uploads as **Recent** cards (12 px radius) above **All files**. The breadcrumb sits above the bold folder title, with the Expires picker, New folder and Folder settings to its right.
- **Inputs, drop-downs, cards, dialogs:** 4 px radius, 1 px `--color-border` border; raised panels and dialogs use LME's soft navy shadow (`0 20px 28px rgba(19, 66, 107, 0.2)`).
- **Keyboard focus:** a 2 px coral outline, shown only when the user is tabbing, as on lme.com.
- **Drop zone:** a 72 px strip under the file list, 2 px dashed `--color-border`, 12 px radius, reading "Drop files anywhere, or choose files, to upload to …", with the fdrop mark as a faint centred watermark behind the text (see Logo). The whole list is still the drop target while files are dragged over; the border and the watermark turn coral.
- **Progress bars:** navy fill on a `--color-border` track; the percentage in navy, semibold; finished rows get a mint "Done" badge.
- **Errors and destructive actions:** navy text with a coral icon and coral left border or outline. Coral is never used for text or as a fill behind white text: coral on white, and white on coral, measure about 3.8:1, under the 4.5:1 that WCAG AA requires for text.
- **Light and dark:** a sun/moon switch in the top bar (`role="switch"`, labelled "Dark mode") flips between the two and the choice is kept in the browser's local storage; until the user chooses, fdrop follows the system setting, and the account menu offers "Use the system's light or dark setting" to go back to that. The resolved theme is set as `data-theme` on `<html>` before the first render, so the page never flashes the wrong one.
- **Dark theme:** lme.com has none, so fdrop derives one. Deep navy background, `#13426B` panels, white text, white-outline secondary buttons, and the same coral accents.
