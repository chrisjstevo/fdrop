package io.fdrop.core.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLoaderTest {

    @Test
    void defaultsWithoutAFile() {
        FdropConfig c = new ConfigLoader(Map.of()).loadFromString("{}");
        assertThat(c.http.port).isEqualTo(8080);
        assertThat(c.management.port).isEqualTo(8081);
        assertThat(c.upload.chunkSize.bytes()).isEqualTo(8L * 1024 * 1024);
        assertThat(c.storage.type).isEqualTo("local");
    }

    @Test
    void yamlSizesDurationsAndSubstitution() {
        String yaml = """
                http:
                  port: 9000
                  simpleUploadMax: 16MiB
                auth:
                  oidc:
                    webClientSecret: ${WEB_SECRET}
                    sftpClientSecret: ${MISSING:-fallback}
                storage:
                  type: s3
                  s3:
                    bucket: my-bucket
                upload:
                  ttl: 12h
                limits:
                  maxFileSize: 1GiB
                """;
        FdropConfig c = new ConfigLoader(Map.of("WEB_SECRET", "s3cret")).loadFromString(yaml);
        assertThat(c.http.port).isEqualTo(9000);
        assertThat(c.http.simpleUploadMax.bytes()).isEqualTo(16L * 1024 * 1024);
        assertThat(c.auth.oidc.webClientSecret).isEqualTo("s3cret");
        assertThat(c.auth.oidc.sftpClientSecret).isEqualTo("fallback");
        assertThat(c.storage.s3.bucket).isEqualTo("my-bucket");
        assertThat(Durations.parse(c.upload.ttl).toHours()).isEqualTo(12);
        assertThat(c.limits.maxFileSize.bytes()).isEqualTo(1024L * 1024 * 1024);
    }

    @Test
    void environmentOverridesMatchKeysCaseInsensitively() {
        Map<String, String> env = Map.of(
                "FDROP_STORAGE_S3_BUCKET", "env-bucket",
                "FDROP_AUTH_OIDC_WEBCLIENTSECRET", "from-env",
                "FDROP_HTTP_PORT", "8888",
                "FDROP_FORWARD_ALLOWEDHOSTS", "a.example, b.example",
                "FDROP_SFTP_PROXYPROTOCOL", "true");
        FdropConfig c = new ConfigLoader(env).loadFromString("storage:\n  s3:\n    bucket: file-bucket\n");
        assertThat(c.storage.s3.bucket).isEqualTo("env-bucket");
        assertThat(c.auth.oidc.webClientSecret).isEqualTo("from-env");
        assertThat(c.http.port).isEqualTo(8888);
        assertThat(c.forward.allowedHosts).containsExactly("a.example", "b.example");
        assertThat(c.sftp.proxyProtocol).isTrue();
    }

    @Test
    void parsesSizeUnits() {
        assertThat(DataSize.parse("5MB").bytes()).isEqualTo(5_000_000);
        assertThat(DataSize.parse("5MiB").bytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(DataSize.parse("100GiB").bytes()).isEqualTo(100L * 1024 * 1024 * 1024);
        assertThat(DataSize.parse("123").bytes()).isEqualTo(123);
        assertThat(Durations.parse("7d").toDays()).isEqualTo(7);
        assertThat(Durations.parse("30s").toSeconds()).isEqualTo(30);
    }
}
