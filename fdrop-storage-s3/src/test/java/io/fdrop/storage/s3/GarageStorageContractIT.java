package io.fdrop.storage.s3;

import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StorageContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The Storage SPI contract against Garage, the in-cluster S3 store of the Kubernetes deployment: multipart parts and
 * completion, copies, batch deletes and listings through fdrop's S3 backend, with the AWS SDK's request checksums.
 */
@Testcontainers(disabledWithoutDocker = true)
class GarageStorageContractIT extends StorageContract {

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
    protected StorageBackend backend() {
        String bucket = "fdrop-garage-" + (++bucketCounter);
        client.createBucket(r -> r.bucket(bucket));
        S3StorageBackend b = new S3StorageBackend();
        b.init(client, bucket, "prefix/", null);
        return b;
    }

    @Override
    protected long minPartSize() {
        return 5L * 1024 * 1024;
    }
}
