package io.fdrop.storage.local;

import io.fdrop.core.error.FdropException;
import io.fdrop.core.storage.StorageBackend;
import io.fdrop.core.storage.StorageContract;
import io.fdrop.core.storage.StoragePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageContractTest extends StorageContract {

    @TempDir
    Path dir;

    @Override
    protected StorageBackend backend() throws Exception {
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir.resolve("root-" + System.nanoTime()));
        return b;
    }

    @Override
    protected long minPartSize() {
        return 64 * 1024;
    }

    @Test
    void refusesSymlinksAndReservedDirectories() throws Exception {
        LocalStorageBackend b = new LocalStorageBackend();
        Path root = dir.resolve("sym");
        b.init(root);
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createDirectories(root.resolve("users"));
        Files.createSymbolicLink(root.resolve("users/evil"), outside);
        assertThatThrownBy(() -> b.stat(StoragePath.of("users/evil/secret.txt"))).isInstanceOf(FdropException.class);
        assertThatThrownBy(() -> b.stat(StoragePath.of(".fdrop-tmp/x"))).isInstanceOf(FdropException.class);
        assertThat(b.list(StoragePath.ROOT, false).map(o -> o.name()).toList()).doesNotContain(".fdrop-tmp",
                ".fdrop-parts");
    }
}
