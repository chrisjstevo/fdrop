package io.fdrop.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsTest {

    @TempDir
    Path dir;

    @Command(name = "t")
    static final class Holder {
        @Mixin
        FdropCli.Connection connection;
    }

    private FdropCli.Connection parse(Map<String, String> env, String... args) {
        Holder h = new Holder();
        new CommandLine(h).setDefaultValueProvider(Settings.load(args, env)).parseArgs(args);
        return h.connection;
    }

    private Path config(String text) throws Exception {
        Path f = dir.resolve("cli.properties");
        Files.writeString(f, text);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        return f;
    }

    @Test
    void optionsBeatEnvironmentWhichBeatsTheFile() throws Exception {
        Path f = config("url=https://from-file:8444\ncert=/file/app.crt\nca=/file/ca.pem\n");
        Map<String, String> env = Map.of("FDROP_URL", "https://from-env:8444", "FDROP_CONFIG_FILE", f.toString());

        assertThat(parse(env, "--url", "https://from-arg:8444").url).hasToString("https://from-arg:8444");
        assertThat(parse(env).url).hasToString("https://from-env:8444");
        assertThat(parse(Map.of("FDROP_CONFIG_FILE", f.toString())).url).hasToString("https://from-file:8444");
        assertThat(parse(env).cert).isEqualTo(Path.of("/file/app.crt"));
        // --config wins over $FDROP_CONFIG_FILE
        Path other = dir.resolve("other.properties");
        Files.writeString(other, "url=https://other:8444\n");
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rw-------"));
        assertThat(parse(Map.of("FDROP_CONFIG_FILE", f.toString()), "--config", other.toString()).url)
                .hasToString("https://other:8444");
    }

    @Test
    void refusesAConfigFileOthersCanRead() throws Exception {
        Path f = config("url=https://x\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> Settings.load(new String[]{"--config", f.toString()}, Map.of()))
                .isInstanceOf(CliException.class).hasMessageContaining("chmod 600");
        assertThatThrownBy(() -> Settings.load(new String[]{"--config=" + dir.resolve("none")}, Map.of()))
                .hasMessageContaining("not found");
    }

    @Test
    void durationsAndPasswordFiles() throws Exception {
        assertThat(Settings.duration("90s")).isEqualTo(Duration.ofSeconds(90));
        assertThat(Settings.duration("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(Settings.duration("2d")).isEqualTo(Duration.ofDays(2));
        assertThatThrownBy(() -> Settings.duration("soon")).isInstanceOf(CliException.class);
        Path pw = dir.resolve("pw");
        Files.writeString(pw, "pa ss\r\n");
        assertThat(new String(Settings.password(pw))).isEqualTo("pa ss");
        assertThat(Settings.password(null)).isNull();
    }

    @Test
    void targetPaths() {
        assertThat(Uploader.target("/home/", Path.of("/tmp/r.csv"))).isEqualTo("/home/r.csv");
        assertThat(Uploader.target("/shared/fin/x.csv", Path.of("r.csv"))).isEqualTo("/shared/fin/x.csv");
        assertThatThrownBy(() -> Uploader.target("home/", Path.of("r.csv"))).isInstanceOf(CliException.class);
    }

    @Test
    void sessionKeysDependOnEverythingThatMustMatch() {
        String k = SessionStore.key("https://a", "fp", "/home/x", 10, 1, "sha");
        assertThat(SessionStore.key("https://a", "fp", "/home/x", 10, 1, "sha")).isEqualTo(k);
        assertThat(SessionStore.key("https://b", "fp", "/home/x", 10, 1, "sha")).isNotEqualTo(k);
        assertThat(SessionStore.key("https://a", "fp2", "/home/x", 10, 1, "sha")).isNotEqualTo(k);
        assertThat(SessionStore.key("https://a", "fp", "/home/x", 10, 1, "sha2")).isNotEqualTo(k);
        assertThat(SessionStore.defaultDir(Map.of("XDG_STATE_HOME", "/s"))).isEqualTo(Path.of("/s/fdrop/uploads"));
    }
}
