package io.fdrop.storage.s3;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Garage, the S3 store of the Kubernetes deployment (see "Object storage" in spec/kubernetes.md), as a single-node container at
 * the version that deployment pins, set up through its admin API the way the deployment's setup Job does it: layout,
 * then an imported key, here also allowed to create buckets so each test gets its own.
 */
final class GarageContainer extends GenericContainer<GarageContainer> {

    /**
     * The multi-platform index, so arm64 hosts get arm64 (see "CPU architecture" in spec/build-and-dev-container.md).
     * Keep in step with fdrop-kubernetes/modules/objectstore (garage_image).
     */
    static final String IMAGE = "docker.io/dxflrs/garage:v2.5.0@sha256:"
            + "677e5b758e4425a325b811c425c026bf35a4bf42f7ddbaa773de3f4fd779a5a5";
    private static final String ADMIN_TOKEN = "garage-test-admin-token";
    private static final String CONFIG = """
            metadata_dir = "/var/lib/garage/meta"
            data_dir = "/var/lib/garage/data"
            db_engine = "lmdb"
            replication_factor = 1
            rpc_bind_addr = "[::]:3901"
            rpc_public_addr = "127.0.0.1:3901"

            [s3_api]
            s3_region = "garage"
            api_bind_addr = "[::]:3900"

            [admin]
            api_bind_addr = "[::]:3903"
            """;

    final String accessKeyId;
    final String secretAccessKey;

    GarageContainer() {
        super(IMAGE);
        SecureRandom r = new SecureRandom();
        byte[] id = new byte[12];
        byte[] secret = new byte[32];
        r.nextBytes(id);
        r.nextBytes(secret);
        accessKeyId = "GK" + HexFormat.of().formatHex(id);
        secretAccessKey = HexFormat.of().formatHex(secret);
        byte[] rpc = new byte[32];
        r.nextBytes(rpc);
        withCopyToContainer(Transferable.of(CONFIG), "/etc/garage.toml");
        withEnv("GARAGE_RPC_SECRET", HexFormat.of().formatHex(rpc));
        withEnv("GARAGE_ADMIN_TOKEN", ADMIN_TOKEN);
        withExposedPorts(3900, 3903);
        waitingFor(Wait.forHttp("/health").forPort(3903).forStatusCode(503).forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(1)));
    }

    @Override
    public void start() {
        super.start();
        try {
            String status = admin("GET", "GetClusterStatus", null);
            Matcher node = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f]{64})\"").matcher(status);
            if (!node.find()) {
                throw new IllegalStateException("no Garage node in " + status);
            }
            admin("POST", "UpdateClusterLayout", "{\"roles\":[{\"id\":\"" + node.group(1)
                    + "\",\"zone\":\"test\",\"capacity\":10000000000,\"tags\":[]}]}");
            admin("POST", "ApplyClusterLayout", "{\"version\":1}");
            admin("POST", "ImportKey", "{\"accessKeyId\":\"" + accessKeyId + "\",\"secretAccessKey\":\""
                    + secretAccessKey + "\",\"name\":\"fdrop-test\"}");
            admin("POST", "UpdateKey?id=" + accessKeyId, "{\"allow\":{\"createBucket\":true}}");
        } catch (IOException e) {
            throw new IllegalStateException("cannot set up Garage", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** One admin API v2 call; fails on anything but 2xx. */
    String admin(String method, String endpoint, String json) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://" + getHost() + ":"
                            + getMappedPort(3903) + "/v2/" + endpoint))
                    .header("Authorization", "Bearer " + ADMIN_TOKEN);
            b = json == null ? b.GET() : b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IOException("Garage " + endpoint + " answered " + r.statusCode() + ": " + r.body());
            }
            return r.body();
        }
    }

    /** fdrop's S3 client for Garage: path-style, region {@code garage}, the imported key. */
    S3Client client() {
        return S3StorageBackend.client("http://" + getHost() + ":" + getMappedPort(3900), "garage",
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)), true);
    }
}
