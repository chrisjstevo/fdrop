package io.fdrop.core.metrics;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Fails the build if any fdrop_ metric gains a label outside the allow-list in the spec. */
class FdropMetricsTest {

    private static final Pattern LINE = Pattern.compile("^(fdrop_[a-z_]+?)(_bucket|_count|_sum|_max)?(?:\\{([^}]*)})?\\s.*");

    @Test
    void everyMetricUsesOnlyAllowedLabels() {
        FdropMetrics m = new FdropMetrics(List.of(0.1, 1.0));
        m.httpRequest("GET", "/api/v1/files", 200, 1_000_000);
        m.sftpAuth("password", true);
        m.login("success");
        m.transfer("http", "upload", 10);
        m.chunk("stored");
        m.assembly("local", 1000);
        m.uploadCompleted("sftp", "home");
        m.storage("local", "write", true, 1000);
        m.forwardSend("dst_1", true, 1000);
        m.forwardBytes("dst_1", 100);
        m.forwardFailure("dst_1", "connect");
        m.keycloakCall("token", true, 1000);
        m.appAuth("http", "mtls", "success");
        m.appCredentialsExpiring(Map.of(List.of("certificate", "30d"), 1L));
        m.expirySet("http", "upload");
        m.expiryRefused("sftp", "syntax");
        m.expiryDeleted("local", "deleted");
        m.expiryDeleteLag("local", 1500);
        m.expiryGauges(4, 0);
        m.encryptionBytes("encrypt", 100);
        m.encryptionFailure("segment_auth");
        m.plaintextRead();
        m.kek("local", "wrap", true, 1000);
        m.dekCache(true);
        m.encryptionObjects(Map.of("k1", 3L));
        m.reencryptObjects(Map.of("pending", 2L));
        m.searchQuery("ok", 1000);
        m.searchIndex(10, 1.5);
        m.searchExtract("ok", 1000);
        m.update(new FdropMetrics.Snapshot(Map.of("user", 3L, "application", 1L), 1000, Map.of("shared", 1L), Map.of("OPEN", 2L),
                Map.of(List.of("QUEUED", "dst_1"), 1L)));

        String text = m.scrape(null);
        Set<String> seen = new HashSet<>();
        for (String line : text.split("\n")) {
            Matcher mt = LINE.matcher(line);
            if (!mt.matches()) {
                continue;
            }
            String base = mt.group(1);
            assertThat(FdropMetrics.ALLOWED_LABELS).as("unknown metric %s", base).containsKey(base);
            seen.add(base);
            for (String pair : (mt.group(3) == null ? "" : mt.group(3)).split(",")) {
                if (pair.isBlank()) {
                    continue;
                }
                String label = pair.substring(0, pair.indexOf('='));
                if (label.equals("le")) {
                    continue;
                }
                assertThat(FdropMetrics.ALLOWED_LABELS.get(base)).as("label %s on %s", label, base).contains(label);
            }
        }
        assertThat(seen).contains("fdrop_build_info", "fdrop_http_requests_seconds", "fdrop_transfer_bytes_total",
                "fdrop_storage_used_bytes", "fdrop_forward_jobs", "fdrop_forward_bytes_total",
                "fdrop_sftp_auth_total", "fdrop_keycloak_request_seconds", "fdrop_users", "fdrop_app_auth_total",
                "fdrop_app_credentials_expiring", "fdrop_expiry_set_total", "fdrop_expiry_refused_total",
                "fdrop_expiry_files", "fdrop_expiry_overdue_files", "fdrop_expiry_deleted_total",
                "fdrop_expiry_delete_lag_seconds", "fdrop_encryption_bytes_total", "fdrop_encryption_failures_total",
                "fdrop_encryption_plaintext_reads_total", "fdrop_encryption_kek_seconds",
                "fdrop_encryption_dek_cache_total", "fdrop_encryption_objects", "fdrop_encryption_reencrypt_objects",
                "fdrop_search_queries_total", "fdrop_search_query_seconds", "fdrop_search_documents",
                "fdrop_search_index_lag_seconds", "fdrop_search_extract_total", "fdrop_search_extract_seconds");
        assertThat(m.scrape("application/openmetrics-text")).contains("# EOF");
    }
}
