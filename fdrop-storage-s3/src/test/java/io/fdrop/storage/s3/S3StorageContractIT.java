package io.fdrop.storage.s3;

import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StorageContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The Storage SPI contract against a real S3 implementation (LocalStack) in a container. Runs in the
 * {@code verify} phase; needs a container runtime (Podman's Docker-compatible socket in the dev container).
 */
@Testcontainers(disabledWithoutDocker = true)
class S3StorageContractIT extends StorageContract {

    static LocalStackContainer localstack;
    static S3Client client;
    static int bucketCounter;

    @BeforeAll
    static void startS3() {
        localstack = new LocalStackContainer(DockerImageName.parse("docker.io/localstack/localstack:4.9")
                .asCompatibleSubstituteFor("localstack/localstack"))
                .withServices("s3");
        localstack.start();
        client = S3StorageBackend.client(localstack.getEndpoint().toString(), localstack.getRegion(),
                StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.getAccessKey(),
                        localstack.getSecretKey())), true);
    }

    @AfterAll
    static void stopS3() {
        if (client != null) {
            client.close();
        }
        if (localstack != null) {
            localstack.stop();
        }
    }

    @Override
    protected StorageBackend backend() {
        String bucket = "fdrop-test-" + (++bucketCounter);
        client.createBucket(r -> r.bucket(bucket));
        S3StorageBackend b = new S3StorageBackend();
        b.init(client, bucket, "prefix/", null);
        return b;
    }
}
