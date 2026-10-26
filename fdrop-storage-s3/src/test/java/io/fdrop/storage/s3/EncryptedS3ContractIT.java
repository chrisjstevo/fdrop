package io.fdrop.storage.s3;

import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.crypto.EncryptingStorage;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StorageContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.util.List;

/**
 * The Storage SPI contract through encryption at rest on S3 (LocalStack), with the data keys wrapped by LocalStack's
 * KMS: server-side CompleteMultipartUpload of encrypted parts, ranged reads and the aws-kms key provider together.
 */
@Testcontainers(disabledWithoutDocker = true)
class EncryptedS3ContractIT extends StorageContract {

    static LocalStackContainer localstack;
    static S3Client client;
    static KmsClient kms;
    static String keyId;
    static int bucketCounter;

    @BeforeAll
    static void start() {
        localstack = new LocalStackContainer(DockerImageName.parse("docker.io/localstack/localstack:4.9")
                .asCompatibleSubstituteFor("localstack/localstack"))
                .withServices("s3", "kms");
        localstack.start();
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                localstack.getAccessKey(), localstack.getSecretKey()));
        client = S3StorageBackend.client(localstack.getEndpoint().toString(), localstack.getRegion(), creds, true);
        kms = KmsClient.builder().endpointOverride(URI.create(localstack.getEndpoint().toString()))
                .region(Region.of(localstack.getRegion())).credentialsProvider(creds)
                .httpClient(AwsCrtHttpClient.builder().build()).build();
        keyId = kms.createKey(b -> b.description("fdrop test KEK")).keyMetadata().arn();
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (kms != null) {
            kms.close();
        }
        if (localstack != null) {
            localstack.stop();
        }
    }

    @Override
    protected StorageBackend backend() throws Exception {
        String bucket = "fdrop-enc-" + (++bucketCounter);
        client.createBucket(r -> r.bucket(bucket));
        S3StorageBackend b = new S3StorageBackend();
        b.init(client, bucket, "prefix/", null);
        FdropConfig.Encryption cfg = new FdropConfig.Encryption();
        cfg.keyProvider = "aws-kms";
        EncryptingStorage s = new EncryptingStorage(b, new KmsKeyProvider(kms, keyId), cfg,
                new FdropMetrics(List.of(1.0)));
        s.checkKeys();
        return s;
    }

    @Override
    protected long minPartSize() {
        return 5L * 1024 * 1024;
    }
}
