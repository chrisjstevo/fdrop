package io.fdrop.core.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fdrop.core.config.FdropConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KEKs in a HashiCorp Vault transit key. Data keys are wrapped with {@code transit/encrypt} and unwrapped with
 * {@code transit/decrypt}; the stored key id is {@code vault:<key>:v<version>}, the version Vault reports, so
 * rotating the transit key needs nothing from fdrop. The token is read from a file and re-read every minute.
 */
public final class VaultKeyProvider implements KeyProvider {

    public static final int CODE = 3;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VERSION = Pattern.compile("^vault:(v\\d+):");

    private final URI address;
    private final String transitKey;
    private final Path tokenFile;
    private final HttpClient http;
    private volatile String token;
    private volatile long tokenReadAt;

    public VaultKeyProvider(FdropConfig.Vault cfg) {
        if (cfg.address == null || cfg.address.isBlank()) {
            throw new IllegalStateException("storage.encryption.vault.address is not set");
        }
        this.address = URI.create(cfg.address.endsWith("/") ? cfg.address : cfg.address + "/");
        this.transitKey = cfg.transitKey;
        this.tokenFile = Path.of(cfg.tokenFile);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() {
        return "vault";
    }

    @Override
    public int code() {
        return CODE;
    }

    @Override
    public String primaryKeyId() {
        return "vault:" + transitKey;
    }

    private String token() throws IOException {
        if (token == null || System.currentTimeMillis() - tokenReadAt > 60_000) {
            token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
            tokenReadAt = System.currentTimeMillis();
        }
        return token;
    }

    private JsonNode call(String op, String key, Map<String, String> body) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(address.resolve("v1/transit/" + op + "/" + key))
                .timeout(Duration.ofSeconds(15))
                .header("X-Vault-Token", token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IOException("Vault transit " + op + " returned " + r.statusCode());
            }
            return JSON.readTree(r.body()).path("data");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted calling Vault", e);
        }
    }

    @Override
    public Wrapped wrap(byte[] dataKey) throws IOException {
        String ct = call("encrypt", transitKey, Map.of("plaintext", Base64.getEncoder().encodeToString(dataKey)))
                .path("ciphertext").asText();
        Matcher m = VERSION.matcher(ct);
        if (!m.find()) {
            throw new IOException("unexpected Vault ciphertext format");
        }
        return new Wrapped("vault:" + transitKey + ":" + m.group(1), ct.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public byte[] unwrap(String keyId, byte[] wrapped) throws IOException {
        String[] parts = keyId.split(":");
        if (parts.length != 3 || !parts[0].equals("vault")) {
            throw new EncryptionException(EncryptionException.Reason.UNKNOWN_KEY, "not a Vault key id: " + keyId);
        }
        String pt = call("decrypt", parts[1], Map.of("ciphertext", new String(wrapped, StandardCharsets.US_ASCII)))
                .path("plaintext").asText();
        return Base64.getDecoder().decode(pt);
    }

    /** Registered for {@code keyProvider: vault}. */
    public static final class Factory implements KeyProviderFactory {
        @Override
        public String name() {
            return "vault";
        }

        @Override
        public KeyProvider create(FdropConfig.Encryption cfg) {
            return new VaultKeyProvider(cfg.vault);
        }
    }
}
