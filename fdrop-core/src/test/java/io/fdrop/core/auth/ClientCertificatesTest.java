package io.fdrop.core.auth;

import io.fdrop.core.TestCerts;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.error.FdropException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientCertificatesTest {

    @TempDir
    Path dir;

    @Test
    void namesForMatching() {
        TestCerts.Issued ca = TestCerts.ca("Test CA");
        TestCerts.Issued c = TestCerts.client(ca, "CN=erp-export,O=Example", "DNS:ERP.example.com",
                "URI:spiffe://example/erp", "EMAIL:ops@example.com");
        // either order, either spacing, OpenSSL's slash form: all the same subject
        String subject = ClientCertificates.subject(c.cert());
        assertThat(ClientCertificates.normaliseSubject("CN=erp-export, O=Example")).isEqualTo(subject);
        assertThat(ClientCertificates.normaliseSubject("o=example,cn=ERP-EXPORT")).isEqualTo(subject);
        assertThat(ClientCertificates.normaliseSubject("/CN=erp-export/O=Example")).isEqualTo(subject);
        assertThat(ClientCertificates.normaliseSubject("CN=other,O=Example")).isNotEqualTo(subject);
        assertThat(ClientCertificates.sans(c.cert())).containsExactlyInAnyOrder("DNS:erp.example.com",
                "URI:spiffe://example/erp", "EMAIL:ops@example.com");
        assertThat(ClientCertificates.fingerprint(c.cert())).hasSize(64).matches("[0-9a-f]+");

        assertThat(ClientCertificates.normaliseSan("dns: ERP.Example.com")).isEqualTo("DNS:erp.example.com");
        assertThatThrownBy(() -> ClientCertificates.normaliseSan("ip:10.0.0.1")).isInstanceOf(FdropException.class);
        assertThatThrownBy(() -> ClientCertificates.normaliseSubject("not a dn")).isInstanceOf(FdropException.class);
    }

    @Test
    void readsTheFormatsProxiesSend() {
        TestCerts.Issued c = TestCerts.selfSigned("CN=app1");
        String pem = c.certPem();
        String fp = ClientCertificates.fingerprint(c.cert());

        // nginx $ssl_client_escaped_cert
        assertThat(fp(ClientCertificates.fromHeader(URLEncoder.encode(pem, StandardCharsets.UTF_8)))).isEqualTo(fp);
        // Envoy X-Forwarded-Client-Cert
        String xfcc = "Hash=abc;Cert=\"" + URLEncoder.encode(pem, StandardCharsets.UTF_8) + "\";Subject=\"CN=app1\"";
        assertThat(fp(ClientCertificates.fromHeader(xfcc))).isEqualTo(fp);
        // PEM folded onto one line
        assertThat(fp(ClientCertificates.fromHeader(pem.replace("\n", " ").trim()))).isEqualTo(fp);

        assertThat(ClientCertificates.fromHeader("garbage")).isEmpty();
        assertThat(ClientCertificates.fromHeader(null)).isEmpty();
    }

    @Test
    void validatesChainsAgainstTheTrustedCas() throws Exception {
        TestCerts.Issued ca = TestCerts.ca("Trusted CA");
        TestCerts.Issued other = TestCerts.ca("Other CA");
        Path bundle = dir.resolve("cas.pem");
        Files.writeString(bundle, ca.certPem());
        FdropConfig.AppMtls cfg = new FdropConfig.AppMtls();
        cfg.trustedCas = bundle.toString();
        ClientCertificates certs = new ClientCertificates(cfg);

        assertThat(certs.chainsToTrustedCa(TestCerts.client(ca, "CN=a").chain())).isTrue();
        assertThat(certs.chainsToTrustedCa(TestCerts.client(other, "CN=a").chain())).isFalse();
        assertThat(certs.chainsToTrustedCa(TestCerts.selfSigned("CN=a").chain())).isFalse();

        // no CAs configured: nothing chains, pinned certificates still work elsewhere
        assertThat(new ClientCertificates(new FdropConfig.AppMtls())
                .chainsToTrustedCa(TestCerts.client(ca, "CN=a").chain())).isFalse();
    }

    @Test
    void expiryIsChecked() {
        assertThat(ClientCertificates.current(TestCerts.selfSigned("CN=a").cert())).isTrue();
        assertThat(ClientCertificates.current(TestCerts.expired("CN=a").cert())).isFalse();
    }

    private static String fp(List<java.security.cert.X509Certificate> chain) {
        assertThat(chain).isNotEmpty();
        return ClientCertificates.fingerprint(chain.get(0));
    }
}
