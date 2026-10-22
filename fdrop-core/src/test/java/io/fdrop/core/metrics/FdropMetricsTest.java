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
        m.update(new FdropMetrics.Snapshot(3, 1000, Map.of("shared", 1L), Map.of("OPEN", 2L),
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
                "fdrop_sftp_auth_total", "fdrop_keycloak_request_seconds");
        assertThat(m.scrape("application/openmetrics-text")).contains("# EOF");
    }
}
