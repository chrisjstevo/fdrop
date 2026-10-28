# Audit log

[fdrop specification](../SPEC.md)

Admins can read and search the whole audit log in the web app, on an **Admin: Audit log** page. Until now the audit log has been JSON lines in each node's log file, which only someone with shell access to every node could read. It still goes there, for log shipping and SIEMs. Every event is now also kept in the metadata DB, where all nodes' events are together, and indexed with Lucene in an **audit index that is separate from the file search index**: different documents, fields, analyzers and access rules, a different directory, and no way for a query to cross between them.

## What is recorded

Every call to `Audit.log` produces one event with:

| Field | Notes |
| --- | --- |
| `id` | A UUID, shown in the UI and used in links |
| `ts` | When it happened, to the millisecond (UTC) |
| `node` | The node that recorded it |
| `event` | The event name already used in the log (`login`, `upload`, `download`, `folder.member`, `file.expired`, `storage.decrypt.failed`, …) |
| `group` | A family derived from the name, for filtering: **Sign-in**, **Files**, **Folders and sharing**, **Forwarding**, **Expiry**, **Accounts**, **Encryption**, **Search**, **Audit**, **System** |
| `user` | Who acted: a username, an application account, or `system` |
| `ip`, `protocol` | Client address (as recorded today, through trusted proxies) and HTTP or SFTP |
| `outcome` | `success`, `failure` or `refused`, from the event's own `outcome` or `result` field, or from its name (`….refused`, `….failed`) |
| `summary` | One line for the table, built from the event's main fields, e.g. "uploaded /shared/Invoices/2026-10.pdf (1.4 MB)" |
| `data` | Every field the event carried, as JSON, exactly as written to the log file |

Audit events never contain passwords, keys, tokens, file contents or search query text (unless an operator turned on `search.auditQueries`); that rule is unchanged and covered by tests. They do contain personal data (usernames, IP addresses, file names), which is why only admins can see them and every look is itself recorded (below).

## Storage and integrity

- **Writing.** `Audit.log` still writes its JSON line to the `fdrop.audit` logger first, then hands the event to the **audit store**, which inserts it into `audit_event` in batches (every 200 ms or 500 events). A request never waits for the DB. If the DB is unreachable, events wait in a bounded buffer (`audit.bufferSize`, 10,000) and are written when it is back. If the buffer fills, further events are dropped from the DB copy only: the log file still has them, `fdrop_audit_write_failures_total` counts them and `FdropAuditWriteFailing` fires.
- **Append-only.** fdrop's code never updates or deletes an audit row, except the retention prune below. Operators can also take `UPDATE` and `DELETE` on `audit_event` away from fdrop's DB user, leaving only retention to a separate maintenance role, and the migration ships the `GRANT`s for that.
- **Tamper evidence.** Each node chains its own events: every row stores `node_seq` (1, 2, 3… per node) and `hash = SHA-256(prev_hash ‖ canonical JSON of the row)`. Editing, deleting or reordering any row breaks the chain from that point on. Chains are per node so nodes never wait for each other.
  - **Verify chain** on the page, and a daily background check, walk every node's chain and report the first broken link.
  - A broken chain is an `audit.chain.broken` event and fires the critical `FdropAuditChainBroken` alert.
  - The chain shows that the database copy was changed after the fact. It cannot stop someone with DB write access from rewriting a whole chain end to end. For that, ship the log file to write-once storage too, which this feature keeps possible.
- **Retention.** Events older than `audit.retention` (default 400 days) are pruned nightly. The prune records an `audit.pruned` event with the cut-off and the hash of the last pruned row of each node, stored as that chain's new anchor in `audit_anchor`, so verification still works from there on.

## The audit index

The audit index is a Lucene index on each node, in `audit.indexDir` (default `./data/audit-index`), never inside `search.indexDir`. It is built from `audit_event` in `seq` order, the same way the file search index follows its change feed. The last `seq` applied is kept in each commit, so a restart continues where it stopped and a missing or broken index is rebuilt from the DB. With encryption at rest on it is encrypted the same way as the search index (`EncryptedDirectory`, under its own index key wrapped by the storage KEK), since it holds personal data.

| Field | Type | Use |
| --- | --- | --- |
| `seq`, `ts` | `LongPoint` + doc values, stored | Ordering, date ranges, paging |
| `event`, `group`, `outcome`, `protocol`, `node` | `StringField` + `SortedSetDocValues` | Exact filters and facet counts |
| `user` | `StringField` (exact) and `TextField` | `user:alice` and free-text matches |
| `ip` | `InetAddressPoint` and `StringField` | Exact address, and ranges for CIDR filters (`ip:10.20.0.0/16`) |
| `path` | `TextField` with the path analyzer from Search, plus `StringField` for prefixes | `path:/shared/Invoices` finds everything under it |
| `text` | `TextField` (standard analyzer) | Every field name and value of the event, for free text |
| `summary` | Stored | Shown in results |

The full event (`data`) is not stored in the index: results are read back from `audit_event` by `seq`. So the DB stays the single record, and the index can be thrown away and rebuilt at any time.

## The Admin: Audit log page

Reached from the Admin group in the sidebar, for holders of `fdrop-admin` signed in with two factors (like every admin page). It looks like the other workspace pages: a table on desktop, cards on phones.

- **Default view:** the newest events first, read straight from the DB, so an event is there the moment it is written, without waiting for the index. 50 per page, with "Load more" (paged by `seq`, so new events never shift pages).
- **Columns:** Time (in the admin's time zone, exact UTC on hover), Event (name with a group badge), User, IP, Outcome (failures and refusals with the coral error icon), Summary. A row opens a side panel with every field of the event, its node, its position in the chain, a **Copy as JSON** button, and links: "Events by this user", "Events from this IP", and "Other events for this path" (each fills in the filter).
- **Search and filters:**
  - The search box takes free text over every field, and the same small syntax as file search (`"phrase"`, `-word`, `a | b`, `prefix*`), plus `event:`, `group:`, `user:`, `ip:` (an address or a CIDR), `path:`, `outcome:` and `node:`. Examples: `user:erp-export outcome:refused`, `ip:203.0.113.0/24 group:sign-in`, `path:/shared/Finance -event:download`.
  - Beside it: a date range (last hour, 24 hours, 7 days, 30 days, custom), group chips with counts for the current search (Lucene facets), and outcome chips.
  - Searching uses the audit index. A note says when the index is more than a few seconds behind ("Searching events up to 14:02:11; newer events appear shortly").
- **Follow live:** a toggle that refreshes the default view every 5 seconds, for watching sign-ins during an incident. It pauses while a row is open.
- **Export:** the current search or filter as JSON Lines (the exact log lines) or CSV, up to `audit.exportMax` (100,000) events, streamed. A larger export is refused with a hint to narrow the dates.
- **Verify chain:** runs the integrity check and shows per node: events checked, first and last time, and "intact" or the first broken event.
- **Accessibility and size:** keyboard reachable filters and rows, the side panel as a dialog that returns focus, and at phone width the table becomes cards (time, event, user, outcome) with filters in a sheet.

## Who can see it

- Only `fdrop-admin`, and only from a two-factor session (`403 two-factor-required` otherwise), like every admin API. Standard users and application accounts get `403`. Admins see every event, their own included. There is no way to hide an event from other admins.
- **Looking is recorded.** Opening the page, each search and each export write `audit.viewed`, `audit.searched` (with the query and filters, since this is admin activity, not user content) and `audit.exported` (with the filters and the number of events) as audit events themselves.
- The audit index answers only the audit API; the file search API never reads it, and the audit API never reads the file index.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/admin/audit?q=&group=&event=&user=&ip=&outcome=&from=&to=&cursor=&limit=` | Events, newest first; with `q` or filters other than time, through the audit index; returns facet counts per group and outcome |
| GET | `/api/v1/admin/audit/{id}` | One event with all its fields and its chain position |
| GET | `/api/v1/admin/audit/export?format=jsonl\|csv&…` | The same selection, streamed as a download |
| GET | `/api/v1/admin/audit/events` | Event names and groups seen, for the filter menus |
| POST | `/api/v1/admin/audit/verify` | Check every node's chain; returns per-node results |
| GET/POST | `/api/v1/admin/audit/index`, `/api/v1/admin/audit/index/rebuild` | This node's audit index status (events, lag); rebuild it |

```json
{
  "total": 3,
  "indexedUpTo": "2026-10-27T09:41:07.412Z",
  "facets": { "group": { "Sign-in": 3 }, "outcome": { "refused": 2, "success": 1 } },
  "nextCursor": "c2VxOjQ4MjE5",
  "events": [
    {
      "id": "6f1c…", "seq": 48231, "ts": "2026-10-27T09:40:55.120Z", "node": "fdrop-1",
      "event": "app.login", "group": "Sign-in", "user": "erp-export", "ip": "198.51.100.23",
      "protocol": "HTTP", "outcome": "refused", "summary": "mTLS login refused: address not allowed"
    }
  ]
}
```

## Metrics and alerts

- `fdrop_audit_events_total{group}`, `fdrop_audit_write_failures_total`, `fdrop_audit_buffer_events` (waiting for the DB), `fdrop_audit_index_lag_seconds`, `fdrop_audit_queries_total{outcome}`, `fdrop_audit_query_seconds` and `fdrop_audit_chain_intact`.
- `FdropAuditWriteFailing` (any write failure or a buffer over half full for 5 min, critical), `FdropAuditChainBroken` (any `audit.chain.broken`, critical), `FdropAuditIndexLag` (lag over 15 min, warning).

## Metadata

- `audit_event(seq BIGINT generated, id, ts, node, node_seq, prev_hash, hash, event, event_group, user_name, ip, protocol, outcome, summary, data TEXT)`, indexed on `ts`, `(node, node_seq)` (unique), `(user_name, ts)` and `(event, ts)`.
- `audit_anchor(node, node_seq, hash, pruned_at)`: where each node's chain starts after pruning.
- On each node, outside the DB: the audit index in `audit.indexDir`, its last applied `seq` in the commit data, and its wrapped index key.
