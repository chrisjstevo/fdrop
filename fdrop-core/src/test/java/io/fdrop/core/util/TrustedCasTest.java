package io.fdrop.core.util;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.fdrop.core.TestCerts;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.OidcClient;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.metrics.FdropMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** fdrop's outbound calls: https only, trusting a private CA only where it is configured. */
class TrustedCasTest {

    @TempDir
    Path dir;

    TestCerts.Issued ca = TestCerts.ca("Internal CA");
    HttpsServer server;
    String url;

    @BeforeEach
    void start() throws Exception {
        TestCerts.Issued cert = TestCerts.server(ca);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(cert.keyStore(new char[0]), new char[0]);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        url = "https://localhost:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private int get(SSLContext tls) throws Exception {
        try (HttpClient c = HttpClient.newBuilder().sslContext(tls).build()) {
            return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        }
    }

    @Test
    void aPrivateCaIsTrustedOnlyWhenNamed() throws Exception {
        assertThatThrownBy(() -> get(TrustedCas.context(null))).isInstanceOf(SSLHandshakeException.class);
        Path pem = dir.resolve("ca.pem");
        Files.writeString(pem, ca.certPem());
        assertThat(get(TrustedCas.context(pem.toString()))).isEqualTo(204);
    }

    @Test
    void theJdkCasStayTrustedBesideThePrivateOne() throws Exception {
        Path pem = dir.resolve("other.pem");
        Files.writeString(pem, TestCerts.ca("Other CA").certPem());
        // a certificate neither the bundle nor the JDK trusts is refused
        assertThatThrownBy(() -> get(TrustedCas.context(pem.toString())))
                .isInstanceOf(SSLHandshakeException.class);
        assertThat(TrustedCas.trustManager(pem.toString()).getAcceptedIssuers())
                .hasSizeGreaterThan(TrustedCas.trustManager(null).getAcceptedIssuers().length);
    }

    @Test
    void anEmptyBundleIsAnError() throws Exception {
        Path pem = dir.resolve("empty.pem");
        Files.writeString(pem, "");
        assertThatThrownBy(() -> TrustedCas.trustManager(pem.toString())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no certificate");
    }

    @Test
    void plainHttpIsRefusedForKeycloakAndS3() {
        assertThatThrownBy(() -> TrustedCas.requireHttps("storage.s3.endpointOverride", "http://garage:3900"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be an https:// URL");
        TrustedCas.requireHttps("storage.s3.endpointOverride", null);
        TrustedCas.requireHttps("storage.s3.endpointOverride", "HTTPS://garage:3443");

        FdropConfig.Oidc oidc = TestSupport.config().auth.oidc;
        oidc.issuer = "http://localhost:8180/realms/fdrop";
        assertThatThrownBy(() -> new OidcClient(oidc, new FdropMetrics(List.of(1.0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("auth.oidc.issuer");
    }
}
