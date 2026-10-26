package io.fdrop.storage.local;

import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.crypto.EncryptingStorage;
import io.fdrop.core.crypto.LocalKeyProvider;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StorageContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** The whole Storage SPI contract, through encryption at rest on the local backend. */
class EncryptedLocalStorageContractTest extends StorageContract {

    @TempDir
    Path dir;

    @Override
    protected StorageBackend backend() throws Exception {
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("root-" + System.nanoTime()));
        FdropConfig.Encryption cfg = new FdropConfig.Encryption();
        cfg.local.keys = "k1:" + Base64.getEncoder().encodeToString(new byte[32]);
        return new EncryptingStorage(b, new LocalKeyProvider(cfg.local.keys), cfg, new FdropMetrics(List.of(1.0)));
    }

    @Override
    protected long minPartSize() {
        return 64 * 1024;
    }
}
