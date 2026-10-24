package io.fdrop.cli;

import io.fdrop.core.FakeDirectory;
import io.fdrop.core.Fdrop;
import io.fdrop.core.TestCerts;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.model.Enums.AppMethod;
import io.fdrop.core.service.AppAccountService;
import io.fdrop.http.HttpServer;
import io.fdrop.storage.local.LocalStorageBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shaded jar as a scheduler runs it: a separate {@code java -jar} process against a real fdrop, checking
 * stdout, {@code --json} and exit codes.
 */
class CliProcessIT {

    record Run(int code, String out, String err) {
    }

    @TempDir
    Path dir;

    final TestCerts.Issued serverCa = TestCerts.ca("fdrop test CA");
    Fdrop app;
    HttpServer server;
    List<String> connection;

    @BeforeEach
    void start() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.http.port = 0;
        cfg.http.mtls.enabled = true;
        cfg.http.mtls.port = 0;
        cfg.auth.twoFactor.require = "none";
        Path[] serverFiles = TestCerts.server(serverCa).write(dir, "server");
        cfg.http.tls.cert = serverFiles[0].toString();
        cfg.http.tls.key = serverFiles[1].toString();
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(Files.createDirectories(dir.resolve("files")));
        app = TestSupport.fdrop(cfg, b, new FakeDirectory());
        server = new HttpServer(app).start();

        TestCerts.Issued appCert = TestCerts.selfSigned("CN=app-proc");
        Path[] client = appCert.write(dir, "app");
        Path ca = dir.resolve("ca.pem");
        Files.writeString(ca, serverCa.certPem());
        Principal admin = TestSupport.admin("admin1");
        app.accounts.ensure(admin);
        app.accounts.ensure(TestSupport.user("alice"));
        app.appAccounts.create(admin, new AppAccountService.CreateInput("app-proc", null, "alice",
                EnumSet.of(AppMethod.MTLS), List.of()));
        app.appAccounts.addCertificate(admin, "sub-app-proc", new AppAccountService.CertInput(appCert.certPem(),
                null, null));
        connection = List.of("--url", "https://localhost:" + server.mtlsPort(), "--cert", client[0].toString(),
                "--key", client[1].toString(), "--ca", ca.toString());
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        app.close();
    }

    private Run fdrop(String... args) throws Exception {
        String jar = System.getProperty("fdrop.cli.jar");
        assertThat(jar).as("fdrop.cli.jar system property (set by failsafe)").isNotNull();
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectError(dir.resolve("err.txt").toFile()).redirectOutput(dir.resolve("out.txt").toFile());
        // keep the runs' saved sessions out of the developer's home
        pb.environment().put("XDG_STATE_HOME", dir.resolve("state").toString());
        Process p = pb.start();
        assertThat(p.waitFor(2, TimeUnit.MINUTES)).isTrue();
        return new Run(p.exitValue(), Files.readString(dir.resolve("out.txt"), StandardCharsets.UTF_8),
                Files.readString(dir.resolve("err.txt"), StandardCharsets.UTF_8));
    }

    private String[] with(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(connection);
        return all.toArray(new String[0]);
    }

    @Test
    void asASchedulerSeesIt() throws Exception {
        Run v = fdrop("--version");
        assertThat(v.code()).isZero();
        assertThat(v.out()).startsWith("fdrop-cli ");

        Run who = fdrop(with("whoami", "--json"));
        assertThat(who.code()).as(who.err()).isZero();
        assertThat(Api.JSON.readTree(who.out()).path("username").asText()).isEqualTo("app-proc");

        Path f = Files.writeString(dir.resolve("orders.csv"), "id,total\n1,9.99\n");
        Run up = fdrop(with("upload", f.toString(), "/home/", "--json"));
        assertThat(up.code()).as(up.err()).isZero();
        assertThat(Api.JSON.readTree(up.out()).path("path").asText()).isEqualTo("/home/orders.csv");
        assertThat(up.err()).isEmpty();

        Run again = fdrop(with("upload", f.toString(), "/home/"));
        assertThat(again.code()).isEqualTo(CliException.EXISTS);
        assertThat(again.err()).startsWith("fdrop: ").contains("--overwrite");

        Run bad = fdrop("upload", "--nonsense");
        assertThat(bad.code()).isEqualTo(CliException.USAGE);
    }
}
