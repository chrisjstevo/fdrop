# Search

[fdrop specification](../SPEC.md)

Users search the names and contents of every file they can see: their own Home and the shared folders they own or belong to. Search runs on [Apache Lucene](https://lucene.apache.org/) embedded in fdrop, with text taken out of documents by [Apache Tika](https://tika.apache.org/). It is started from the search box in the top bar. A user can never find, or learn anything from, a file they cannot open: access is checked inside every query and again on every result.

## What is searched

| Searched | Not searched (v1) |
| --- | --- |
| File and folder names, in Home and shared folders | Forward folders: their files pass through on their way to a partner (`search.includeForwardFolders` turns them on) |
| The text of plain text, CSV, JSON, XML, Markdown, source code, HTML (text only), RTF, PDF, Word, Excel, PowerPoint, OpenDocument and email (`.eml`) files | Images and scanned PDFs (no OCR), audio and video, and the contents of archives (`.zip` is found by name only) |
| The first `search.extract.maxTextPerFile` (20 MiB) of text per file, from files up to `search.extract.maxFileSize` (100 MiB) | Password-protected or encrypted documents, which are found by name and shown as "contents not searchable" |

- **Name matches rank first** (field boosts: name ×3, folder path ×1.5, contents ×1), then by Lucene's BM25 relevance, with newer files ahead on ties.
- **Text analysis:** contents use Lucene's `StandardAnalyzer` (Unicode word breaks, lower-casing, no stop words, so "to be" finds what it should). Names use an analyzer that also splits on `_`, `-`, `.`, digits and camelCase, so `nightly-2026-10-26.csv` is found by `nightly`, `2026` or `csv`, plus edge n-grams so the first letters of a name match while typing.

## Query syntax

The box takes plain words; every word must match, in the name or the contents. A small, safe syntax is offered and shown in a help tip under the results:

| Type | Finds |
| --- | --- |
| `invoice acme` | Files with both words, in any order |
| `"credit note"` | The exact phrase |
| `invoice -draft` | Files with "invoice" but not "draft" |
| `invoice \| receipt` | Either word |
| `inv*` | Words starting with "inv" (prefixes only; at least 2 letters before the `*`) |
| `name:report` | The word in the file name only (also `content:`) |
| `type:pdf` | Only PDFs (also `doc`, `sheet`, `slides`, `text`, `email`, `other`) |

- Queries are parsed with Lucene's `SimpleQueryParser`, never the classic query parser, so no input can build a regular-expression, fuzzy-everything or leading-wildcard query that scans the whole index. A query is at most 500 characters and 32 terms; a prefix expands to at most 1,024 terms. A query that breaks those limits gets `400` with problem type `invalid-search` and a plain-language reason.
- Spelling suggestions ("did you mean") are not offered: Lucene builds them from words across the whole index, which would reveal words in other users' files.

## Access control

Access is enforced in three places, so a mistake in one cannot leak a file:

1. **In the index.** Every document carries an `area` field: `home:<sub>` for files under a user's Home, `folder:<folderId>` for files in a managed folder. Each query is wrapped in a filter (`BooleanQuery` with a `FILTER` clause on a `TermInSetQuery`) listing only the areas the caller may read **right now**: their own Home plus the folders they own or are a member of, read from the metadata DB for every query, exactly as `PathResolver` does. Removing a member, deleting a folder or transferring ownership takes effect on the next search, with nothing to re-index.
2. **On every result.** Each hit is resolved again as the caller through `FileService.stat` before it is returned; a hit that fails (a race with a move, a delete, an expired file) is dropped silently. Snippets and highlights are only ever built for hits that passed both checks.
3. **No shared dictionaries.** Name suggestions while typing come from a filtered prefix query over the caller's areas, never from a global suggester or term dictionary, and result counts are counts of filtered hits. Nothing a user sees is computed from documents outside their areas.

- Application accounts search on the same terms through the API. Admins get no extra reach: an admin finds only their own Home and their own folders.
- Expired files are excluded by a filter on their `expiresAt` field at query time, from the expiry instant on, even before the sweeper deletes them.
- Files in folders the caller can only read are found and can be downloaded, as in the file list.

## Index and documents

Each node keeps its own Lucene index on local disk (`search.indexDir`) and answers searches from it, so search needs no extra service, and adding a node adds search capacity. All nodes build the same index from the same change feed in the metadata DB.

One Lucene document per file:

| Field | Type | Use |
| --- | --- | --- |
| `id` | `StringField`, stored | The storage key; updates and deletes replace by it |
| `area` | `StringField` | `home:<sub>` or `folder:<folderId>`: the access filter |
| `relPath` | `TextField` (path analyzer) and stored | Path inside its area, e.g. `reports/q3/board.pdf`; the shown location is rebuilt from the folder's current name at query time, so renaming a shared folder re-indexes nothing |
| `name` | `TextField` (name analyzer), stored, with term vectors | Name matching and highlighting |
| `content` | `TextField` (standard analyzer), stored compressed, with offsets | Content matching and snippets |
| `type` | `StringField` | Type family for `type:` and the filter chips |
| `size`, `modified`, `expiresAt` | `LongPoint` with `NumericDocValues` | Filters, sorting, expiry |
| `extractState` | `StringField`, stored | `ok`, `unsupported`, `too_large`, `protected`, `failed`: why contents may be missing |

Folders get a lighter document (name and path only) so a search finds them too.

**Change feed.** Whenever fdrop changes what a user could find, it appends a row to `search_change(seq, op, storage_key, to_key, at)` in the same transaction as the change itself:

| Event | Change |
| --- | --- |
| Upload completed (any protocol), copy | `INDEX key` |
| File or folder deleted, file expired, folder deleted, user deleted | `DELETE key` (a folder deletes everything under it) |
| Move or rename | `MOVE key → to_key`: the document is rewritten from its stored fields under the new key and area, with no re-extraction |
| Folder renamed, member added or removed, ownership transferred | Nothing: names are resolved and access is checked at query time |

Each node's indexer reads the feed in `seq` order and records the last `seq` it applied in the Lucene commit's user data, so after a crash or restart it continues exactly where its last commit ended; the commit and the position move together. The indexer commits every `search.commitInterval` (5 s) when there are changes, and searches use a near-real-time reader refreshed every second, so an upload is normally searchable within a few seconds. Feed rows older than `search.feedRetention` (7 days) are pruned; a node that was down longer, or whose index is missing or corrupt, rebuilds from a full listing of the storage instead.

**Text extraction** is the expensive part, so it happens once per file version, not once per node:

- The first node to reach an `INDEX` change claims it in `search_extract(storage_key, etag, state, claimed_by, claimed_until)`, reads the file through the Storage SPI (so through decryption), and runs Tika.
- The extracted text is stored once as an object under `system/search-text/<sha256 of key and etag>`, written through the encryption layer like any file and never reachable from a user's tree. Other nodes index from that object instead of extracting again, and a rebuild reuses it.
- Extraction runs in a **separate, short-lived JVM** started by fdrop for each file (`ExtractMain`: the document on stdin, its text on stdout) with a 512 MiB heap (`search.extract.forkHeap`), a 60 s time limit (`search.extract.timeout`) and no network, because document parsers process untrusted files and have a history of crashes, runaway memory and vulnerabilities. A crash or timeout marks the file `failed`; its name is still indexed.
- At most `search.extract.workers` (2) extractions run per node, at low priority, so search indexing never slows uploads or downloads. Extraction is skipped for files over `maxFileSize`, and the text is cut at `maxTextPerFile`.
- The text objects are deleted with their file and do not count against anyone's quota.
- The cached text is keyed by file version and by `maxTextPerFile`, so after changing the limit a rebuild (or the next upload of a file) reads the files again with the new limit.

**Rebuild.** An admin can rebuild a node's index from the **Admin: Search index** screen (or by deleting `search.indexDir` and restarting): the node lists the storage, indexes every file from its cached text (extracting only what is missing), and keeps answering from the old index until the new one is complete, then swaps.

**Encryption at rest.** The index holds words from users' files, so with `storage.encryption.enabled` it is encrypted too: the index directory is opened through `EncryptedDirectory`, a Lucene `FilterDirectory` that encrypts every index file with AES-256-CTR (CTR, because Lucene reads index files at random offsets) under a per-file key derived with HKDF from the node's index key. The index key is a data key wrapped by the storage KEK and kept in `search.indexDir/index.key`; rotating the KEK re-wraps it. A stolen disk or backup of a node therefore reveals neither file contents nor the words in them. Without storage encryption, keep `search.indexDir` on an encrypted volume.

## Searching from the search box

The search field in the top bar ("Search files and contents") starts every search:

- **While typing** (from 2 characters, 150 ms after the last key), a drop-down shows up to 8 files and folders whose **names** match, each with its icon and location, from everywhere the user can see. Below them, a last item reads **Search contents for "…"**. Arrow keys move through the list, Enter opens the highlighted file's folder (with the file highlighted) or, on the last item, runs the full search; Escape closes the list. Inside a folder, the file list also keeps narrowing to matching names as before, so finding something in the folder in front of you is instant.
- **Enter** (or the last item) opens the results page at `/search?q=…`, which searches names and contents everywhere the user can see.
- The field has `role="combobox"` with an `aria-controls`'d `listbox`, and the result count is announced in a polite live region, so it works fully by keyboard and with screen readers. On phones the field sits under the top bar (as now) and the drop-down becomes a full-width sheet.

**Results page.**

- The query stays in the search box. Above the results: the count ("37 files"), scope chips **Everywhere** and **In <the folder you came from>**, type chips (**All**, **PDFs**, **Documents**, **Spreadsheets**, **Presentations**, **Text**, **Email**, **Other**), a **Modified** filter (any time, last 7 days, last 30 days, this year) and sort (**Best match** or **Newest**).
- Each result is a row in the workspace style: its file icon on a tile, the name with matched words in bold, its location as a breadcrumb (`Shared: Invoices / 2026`), and up to two snippets of about 160 characters from the contents with the matched words marked, then size, modified date and expiry. Row actions: open the folder (the file highlighted), download, and set expiry where the user may write.
- A file whose contents could not be read shows "Name match only" with the reason on hover (for example "Password-protected", "Too large to search").
- 20 results per page, with "Load more" (a cursor, up to `search.maxResults`, 1,000). No results: suggestions to check spelling, use fewer words, or search everywhere instead of one folder.
- Snippets come from the first 4 MiB of a file's text; a file that matches only further in is still found, with no snippet. This keeps a page of results fast when files hold up to 20 MiB of text.
- Snippets are sent as plain text segments (`[{ "text": "…", "match": true }, …]`), never as HTML, and rendered by React as text with `<mark>`, so a document's text cannot inject markup.
- Under the results, a muted note: "Files uploaded in the last few seconds may not appear yet."

## API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/search?q=&scope=all\|folder&path=&type=&modifiedFrom=&sort=relevance\|newest&cursor=&limit=` | Search names and contents everywhere the caller can see, or under one folder (`scope=folder&path=/shared/Invoices`) |
| GET | `/api/v1/search/suggest?q=` | Up to 8 name matches for the search box drop-down |
| GET | `/api/v1/admin/search` | This node's index: documents, size, last applied change, lag, extraction counts by state (admin) |
| POST | `/api/v1/admin/search/rebuild` | Rebuild this node's index in the background (admin) |

Example:

```http
GET /api/v1/search?q=%22credit%20note%22%20acme&limit=2 HTTP/1.1
Authorization: Bearer eyJhbGciOi…
```

```json
{
  "total": 3,
  "nextCursor": "c2NvcmU6MTIuNHxpZDp…",
  "results": [
    {
      "path": "/shared/Invoices/2026/credit-note-0412.pdf",
      "name": [{ "text": "credit-note-0412.pdf", "match": false }],
      "location": "Shared: Invoices / 2026",
      "type": "pdf",
      "size": 90112,
      "modified": "2026-10-25T14:02:11Z",
      "expiresAt": "2026-10-31T14:02:11Z",
      "snippets": [[
        { "text": "…issued to ", "match": false },
        { "text": "Acme", "match": true },
        { "text": " Ltd. This ", "match": false },
        { "text": "credit note", "match": true },
        { "text": " cancels invoice 2026-0391…", "match": false }
      ]],
      "contents": "ok"
    }
  ]
}
```

- Searches count against a per-user rate limit (`search.rateLimitPerMinute`, 60); over it the API returns `429` with `Retry-After`.
- A search for a folder the caller cannot see (`scope=folder&path=…`) returns `404`, like listing it would.

## Audit and metrics

- Audit: `search` entries record the user, scope, number of results and duration, but **not** the query text, which is the user's own content (set `search.auditQueries: true` to include it). `search.rebuild` records the acting admin. `search.extract.failed` records the storage key and reason.
- Metrics: `fdrop_search_queries_total{outcome}` (ok, invalid, rate_limited, error), `fdrop_search_query_seconds`, `fdrop_search_documents`, `fdrop_search_index_lag_seconds` (age of the oldest change this node has not applied), `fdrop_search_extract_total{result}` (ok, unsupported, too_large, protected, timeout, failed) and `fdrop_search_extract_seconds`. Query text never appears in labels or logs.
- Alerts: `FdropSearchIndexLag` (lag over 15 min for 15 min, warning) and `FdropSearchExtractFailing` (more than 20% of extractions `failed` or `timeout` over 1 h, warning).

## Metadata

- `search_change(seq BIGINT generated, op INDEX|DELETE|MOVE, storage_key, to_key, at)`, indexed on `seq`; pruned after `search.feedRetention`.
- `search_extract(storage_key, etag, state PENDING|OK|UNSUPPORTED|TOO_LARGE|PROTECTED|FAILED, text_key, claimed_by, claimed_until, attempts, updated_at)`, primary key `(storage_key, etag)`.
- On each node, outside the DB: the Lucene index in `search.indexDir`, its commit user data holding the last applied `seq`, and `index.key` (the wrapped index key).
