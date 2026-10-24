package io.fdrop.cli;

import io.fdrop.core.TestCerts;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.OutputEncryptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialsTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"RSA", "EC", "Ed25519"})
    void loadsPemCertificateAndPkcs8Key(String alg) throws Exception {
        TestCerts.Issued c = TestCerts.selfSigned("CN=app-" + alg, alg);
        Path[] files = c.write(dir, "app");
        Credentials creds = Credentials.fromPem(files[0], files[1], null);
        assertThat(creds.key().getAlgorithm()).isEqualTo(alg.equals("Ed25519") ? "EdDSA" : alg);
        assertThat(creds.fingerprint()).hasSize(64);
        assertThat(creds.chain()).hasSize(1);
    }

    @Test
    void readsAnEncryptedKeyWithItsPasswordFile() throws Exception {
        TestCerts.Issued c = TestCerts.selfSigned("CN=enc");
        Path cert = dir.resolve("app.crt");
        Files.writeString(cert, c.certPem());
        OutputEncryptor enc = new JceOpenSSLPKCS8EncryptorBuilder(JcaPKCS8Generator.AES_256_CBC)
                .setProvider(new BouncyCastleProvider()).setPassword("s3cret".toCharArray()).build();
        StringWriter pem = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(pem)) {
            w.writeObject(new JcaPKCS8Generator(c.keys().getPrivate(), enc));
        }
        Path key = dir.resolve("app.key");
        Files.writeString(key, pem.toString());
        assertThat(pem.toString()).startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----");

        Path pw = dir.resolve("pw");
        Files.writeString(pw, "s3cret\n");
        assertThat(Credentials.fromPem(cert, key, Settings.password(pw)).leaf()).isEqualTo(c.cert());

        assertThatThrownBy(() -> Credentials.fromPem(cert, key, null)).isInstanceOf(CliException.class)
                .hasMessageContaining("--key-password-file")
                .satisfies(e -> assertThat(((CliException) e).code()).isEqualTo(CliException.USAGE));
        assertThatThrownBy(() -> Credentials.fromPem(cert, key, "wrong".toCharArray()))
                .hasMessageContaining("wrong password");
    }

    @Test
    void refusesTraditionalKeysWithAHint() throws Exception {
        Path cert = TestCerts.selfSigned("CN=x").write(dir, "x")[0];
        Path key = dir.resolve("old.key");
        Files.writeString(key, "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAKj34GkxFhD90vcNLYLInFEX6Ppy1tPf9Cnzj4p4WGeKLs1Pt8Qu\n"
                + "-----END RSA PRIVATE KEY-----\n");
        assertThatThrownBy(() -> Credentials.fromPem(cert, key, null)).isInstanceOf(CliException.class)
                .hasMessageContaining("openssl pkcs8 -topk8");
    }

    @Test
    void refusesAKeyThatIsNotTheCertificates() throws Exception {
        Path cert = TestCerts.selfSigned("CN=a").write(dir, "a")[0];
        Path otherKey = TestCerts.selfSigned("CN=b").write(dir, "b")[1];
        assertThatThrownBy(() -> Credentials.fromPem(cert, otherKey, null)).isInstanceOf(CliException.class)
                .hasMessageContaining("does not belong");
        assertThatThrownBy(() -> Credentials.fromPem(dir.resolve("missing.crt"), otherKey, null))
                .hasMessageContaining("no such file");
    }

    @Test
    void loadsPkcs12() throws Exception {
        TestCerts.Issued c = TestCerts.selfSigned("CN=p12");
        Path p12 = dir.resolve("app.p12");
        try (OutputStream out = Files.newOutputStream(p12)) {
            c.keyStore("p12pass".toCharArray()).store(out, "p12pass".toCharArray());
        }
        assertThat(Credentials.fromPkcs12(p12, "p12pass".toCharArray()).leaf()).isEqualTo(c.cert());
        assertThatThrownBy(() -> Credentials.fromPkcs12(p12, "nope".toCharArray())).isInstanceOf(CliException.class)
                .hasMessageContaining("password");
    }
}
