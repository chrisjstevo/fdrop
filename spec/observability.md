# Observability

[fdrop specification](../SPEC.md)

fdrop exposes a Prometheus metrics endpoint, `GET /metrics`, on a separate management port (default 8081, HTTPS with the same certificate as the web port), together with health and readiness checks. Prometheus scrapes it with `scheme: https` and the issuing CA, and Kubernetes probes use `scheme: HTTPS`. User traffic on the HTTPS and SFTP ports never reaches it, and the main HTTP port does not serve `/metrics`.

- Structured logs with a request/session id shared across the Netty handler, storage calls and audit entries.

## Management endpoints

| Path (port 8081) | Purpose |
| --- | --- |
| `GET /metrics` | Prometheus scrape endpoint: Prometheus text format, or OpenMetrics when the scraper asks for it (`Accept: application/openmetrics-text`), so exemplars and created timestamps are available |
| `GET /healthz` | Liveness: the process is up and its event loops respond. Used for container restarts |
| `GET /readyz` | Readiness: the metadata DB answers, a `stat` of the storage root succeeds, and with encryption at rest the primary KEK can wrap and unwrap a test key (checked at most once a minute). Keycloak being unreachable reports `degraded` with HTTP 200, so logged-in sessions keep working; DB, storage or KEK failure returns 503 |

- Built with Micrometer's `PrometheusMeterRegistry` (`micrometer-registry-prometheus`), served by a small Netty listener of its own so a busy main port cannot starve scrapes.
- Access: `management.bind` (default `0.0.0.0` in the container) and an optional bearer token, `management.metricsToken`. When the token is set, `/metrics` requires `Authorization: Bearer <token>`; `/healthz` and `/readyz` stay open for the container runtime. The management port must not be published to the internet.
- Scrapes are cheap: gauges for counts (folders, users, jobs by state) are refreshed from the metadata DB every 30 s in the background, never computed during a scrape.

## Metrics

All fdrop metrics are prefixed `fdrop_`. Durations are histograms in seconds, sizes in bytes, and counters end in `_total`.

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `fdrop_build_info` | gauge (1) | `version`, `commit`, `java_version` | Which build is running |
| `fdrop_http_requests_seconds` | histogram | `method`, `route`, `status` | HTTP request duration; `route` is the route template (`/api/v1/files/content`), never the raw URL |
| `fdrop_http_connections_active` | gauge | — | Open HTTP connections |
| `fdrop_sftp_sessions_active` | gauge | — | Open SFTP sessions |
| `fdrop_sftp_auth_total` | counter | `method` (password, keyboard_interactive, publickey), `result` (success, failure) | SFTP login attempts |
| `fdrop_app_auth_total` | counter | `protocol` (http, sftp), `method` (mtls, password, publickey), `result` (success, failure, method_not_allowed, source_refused) | Application account logins |
| `fdrop_app_credentials_expiring` | gauge | `type` (password, certificate), `within` (30d, 7d) | Application passwords and certificates expiring soon |
| `fdrop_two_factor_total` | counter | `result` (satisfied, step_up, refused) | Sign-ins needing two factors: already done, sent back for a second factor, or refused because Keycloak did not confirm it |
| `fdrop_logins_total` | counter | `result` (success, failure, refresh_failed) | Browser logins through Keycloak |
| `fdrop_transfer_bytes_total` | counter | `protocol` (http, sftp), `direction` (upload, download) | Bytes moved by users |
| `fdrop_upload_sessions` | gauge | `state` | Resumable upload sessions by state |
| `fdrop_upload_chunks_total` | counter | `result` (stored, duplicate, rejected) | Chunk PUTs and their outcome |
| `fdrop_upload_assembly_seconds` | histogram | `backend` | Time to concatenate chunks into the final file |
| `fdrop_uploads_completed_total` | counter | `protocol`, `went_to` (home, shared, forward) | Finished uploads, matching My uploads rows |
| `fdrop_storage_operation_seconds` | histogram | `backend` (local, s3), `operation`, `outcome` (ok, error) | Storage SPI calls: stat, list, read, write, multipart_part, multipart_complete, delete, move |
| `fdrop_storage_used_bytes` | gauge | — | Total bytes stored across all users |
| `fdrop_forward_jobs` | gauge | `state`, `destination` | Forward jobs by state; `destination` is the destination id |
| `fdrop_forward_send_seconds` | histogram | `destination`, `outcome` | Time to send one file |
| `fdrop_forward_bytes_total` | counter | `destination` | Bytes forwarded |
| `fdrop_forward_failures_total` | counter | `destination`, `reason` (host_key_mismatch, auth_rejected, connect, io, conflict) | Failed send attempts, including ones that will be retried |
| `fdrop_keycloak_request_seconds` | histogram | `operation` (token, jwks, password_grant, admin_api), `outcome` | Calls to Keycloak |
| `fdrop_users` | gauge | `kind` (user, application) | Users and application accounts known to fdrop |
| `fdrop_folders` | gauge | `type` (shared, forward) | Managed folders |
| `fdrop_expiry_set_total` | counter | `protocol` (http, sftp), `source` (upload, folder_default, home_default, changed, forward_keep, cleared) | Expiries set, changed or cleared |
| `fdrop_expiry_refused_total` | counter | `protocol`, `reason` (syntax, too_long, not_allowed, conflicting) | Expiry values refused |
| `fdrop_expiry_files` | gauge | — | Files that have an expiry |
| `fdrop_expiry_overdue_files` | gauge | — | Files more than 5 min past their expiry and not yet deleted |
| `fdrop_expiry_deleted_total` | counter | `backend`, `outcome` (deleted, already_gone, error) | Expiry delete attempts |
| `fdrop_expiry_delete_lag_seconds` | histogram | `backend` | Time from a file's expiry to its deletion from storage |
| `fdrop_encryption_bytes_total` | counter | `operation` (encrypt, decrypt) | Plaintext bytes through the encryption layer |
| `fdrop_encryption_failures_total` | counter | `reason` (header_mac, segment_auth, truncated, unknown_key, plaintext_refused) | Reads refused because an object failed authentication |
| `fdrop_encryption_plaintext_reads_total` | counter | — | Plaintext objects served while `allowPlaintextReads` is on |
| `fdrop_encryption_kek_seconds` | histogram | `provider`, `operation` (wrap, unwrap), `outcome` | Calls to the key-encryption key provider |
| `fdrop_encryption_dek_cache_total` | counter | `result` (hit, miss) | Data key cache lookups |
| `fdrop_encryption_objects` | gauge | `key_id` | Stored objects per KEK id, from the daily inventory (`plaintext` for unencrypted ones) |
| `fdrop_encryption_reencrypt_objects` | gauge | `state` (pending, done, failed) | Objects in the running re-encryption job |
| `fdrop_search_queries_total` | counter | `outcome` (ok, invalid, rate_limited, error) | Searches |
| `fdrop_search_query_seconds` | histogram | — | Search time, filtering and result checks included |
| `fdrop_search_documents` | gauge | — | Documents in this node's index |
| `fdrop_search_index_lag_seconds` | gauge | — | Age of the oldest change this node has not applied |
| `fdrop_search_extract_total` | counter | `result` (ok, unsupported, too_large, protected, timeout, failed) | Text extractions |
| `fdrop_search_extract_seconds` | histogram | — | Time to extract one file's text |
| `fdrop_audit_events_total` | counter | `group` | Audit events recorded |
| `fdrop_audit_write_failures_total` | counter | — | Audit events that could not be written to the DB (still in the log file) |
| `fdrop_audit_buffer_events` | gauge | — | Audit events waiting for the DB |
| `fdrop_audit_index_lag_seconds` | gauge | — | Age of the newest audit event this node's audit index has not applied |
| `fdrop_audit_queries_total` | counter | `outcome` (ok, invalid, error) | Audit log searches |
| `fdrop_audit_query_seconds` | histogram | — | Audit log search time |
| `fdrop_audit_chain_intact` | gauge | — | 1 when the last chain check found every node's chain intact, 0 when it found a broken link |
| `fdrop_db_client_cert_expiry_seconds` | gauge | — | Seconds until the metadata DB client certificate (`db.tls.cert`) expires; absent with H2 or `db.tls.mode: disable` |

Micrometer's standard JVM and process metrics (`jvm_memory_*`, `jvm_gc_*`, `jvm_threads_*`, `process_*`), Netty's pooled buffer memory, and the DB connection pool (`hikaricp_*`) are included too.

**Label rules.** Labels never contain usernames, `sub` values, file names, paths, folder names, IP addresses or destination hostnames. That keeps personal data out of the monitoring system and keeps the number of time series bounded. Destinations are labelled by id, which admins can map to a name in the admin UI. A unit test fails the build if any `fdrop_` metric gains a label outside an allow-list.

**Histograms** use fixed buckets (5 ms, 25 ms, 100 ms, 500 ms, 1 s, 5 s, 30 s, 2 min, 10 min), overridable with `management.histogramBuckets`; no client-side percentiles, so quantiles are computed in Prometheus and aggregate correctly across instances.

## Scraping, alerts and dashboard

`deploy/prometheus/` ships an example scrape job and alert rules; `deploy/grafana/fdrop-dashboard.json` is a ready-made dashboard (traffic, uploads, storage latency, forwarding by destination, Keycloak, JVM).

```yaml
# deploy/prometheus/prometheus.yml (excerpt)
scrape_configs:
  - job_name: fdrop
    scrape_interval: 15s
    metrics_path: /metrics
    authorization:
      credentials_file: /etc/prometheus/fdrop-metrics-token
    static_configs:
      - targets: ["fdrop.internal:8081"]
```

| Alert | Fires when | Severity |
| --- | --- | --- |
| `FdropDown` | `up{job="fdrop"} == 0` for 2 min | critical |
| `FdropNotReady` | `/readyz` returns 503 (via the blackbox exporter) for 5 min | critical |
| `FdropStorageErrors` | storage `outcome="error"` above 1% of operations for 10 min | critical |
| `FdropForwardFailing` | any destination with failures and no successful send for 30 min | warning |
| `FdropForwardHostKeyMismatch` | any increase in `reason="host_key_mismatch"` | critical |
| `FdropKeycloakErrors` | Keycloak `outcome="error"` above 5% for 10 min | warning |
| `FdropSftpAuthFailureSpike` | more than 50 SFTP login failures in 5 min | warning |
| `FdropAppAuthFailures` | more than 20 application login failures in 5 min | warning |
| `FdropAppCredentialExpiring` | any application password or certificate expiring within 7 days | warning |
| `FdropUploadAssemblySlow` | 95th percentile assembly time over 5 min for 30 min | warning |
| `FdropAuditWriteFailing` | any `fdrop_audit_write_failures_total` increase, or the buffer over half full for 5 min | critical |
| `FdropAuditChainBroken` | `fdrop_audit_chain_intact` is 0: the last chain check found a broken link | critical |
| `FdropAuditIndexLag` | `fdrop_audit_index_lag_seconds` over 15 min for 15 min | warning |
| `FdropDbClientCertExpiring` | `fdrop_db_client_cert_expiry_seconds` under 14 days | warning |
| `FdropSearchIndexLag` | `fdrop_search_index_lag_seconds` over 15 min for 15 min | warning |
| `FdropSearchExtractFailing` | `failed` and `timeout` above 20% of extractions over 1 h | warning |
| `FdropDecryptionFailures` | any increase in `fdrop_encryption_failures_total`: stored data was altered or corrupted | critical |
| `FdropKmsErrors` | KEK provider `outcome="error"` above 1% for 5 min | critical |
| `FdropPlaintextObjects` | `fdrop_encryption_objects{key_id="plaintext"}` > 0 for 7 days after encryption was turned on | warning |
| `FdropExpiryOverdue` | any file still not deleted an hour after its expiry (`fdrop_expiry_overdue_files` > 0 for 55 min) | warning |
| `FdropExpiryDeleteErrors` | expiry deletes with `outcome="error"` above 5% for 15 min | warning |
| `FdropHeapHigh` | JVM heap above 90% of max for 15 min | warning |
