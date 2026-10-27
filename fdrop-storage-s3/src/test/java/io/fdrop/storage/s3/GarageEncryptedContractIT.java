package io.fdrop.storage.s3;

import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.crypto.EncryptingStorage;
import io.fdrop.core.crypto.KeyProviders;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StorageContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Base64;
import java.util.List;

/**
 * The Storage SPI contract through encryption at rest on Garage, with a local KEK as the Kubernetes deployment's
 * {@code kind} environment uses it: Garage only ever holds ciphertext.
 */
@Testcontainers(disabledWithoutDocker = true)
class GarageEncryptedContractIT extends StorageContract {

    static GarageContainer garage;
    static S3Client client;
    static int bucketCounter;

    @BeforeAll
    static void startGarage() {
        garage = new GarageContainer();
        garage.start();
        client = garage.client();
    }

    @AfterAll
    static void stopGarage() {
        if (client != null) {
            client.close();
        }
        if (garage != null) {
            garage.stop();
        }
    }

    @Override
    protected StorageBackend backend() throws Exception {
        String bucket = "fdrop-garage-enc-" + (++bucketCounter);
        client.createBucket(r -> r.bucket(bucket));
        S3StorageBackend b = new S3StorageBackend();
        b.init(client, bucket, "prefix/", null);
        FdropConfig.Encryption cfg = new FdropConfig.Encryption();
        cfg.keyProvider = "local";
        cfg.local.keys = "k8s1:" + Base64.getEncoder().encodeToString(new byte[32]);
        EncryptingStorage s = new EncryptingStorage(b, KeyProviders.load(cfg), cfg, new FdropMetrics(List.of(1.0)));
        s.checkKeys();
        return s;
    }

    @Override
    protected long minPartSize() {
        return 5L * 1024 * 1024;
    }
}
