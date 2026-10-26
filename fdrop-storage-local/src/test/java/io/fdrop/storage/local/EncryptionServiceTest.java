package io.fdrop.storage.local;

import io.fdrop.core.Fdrop;
import io.fdrop.core.TestSupport;
import io.fdrop.core.auth.Principal;
import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.crypto.StreamFormat;
import io.fdrop.core.error.FdropException;
import io.fdrop.core.service.EncryptionService;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.storage.WriteHandle;
import io.fdrop.core.storage.WriteOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Inventory, plaintext migration and key retirement through the re-encryption job. */
class EncryptionServiceTest {

    static final String TEST1 = "test1:" + Base64.getEncoder().encodeToString(new byte[32]);
    static final String K2 = "k2:" + Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII));

    @TempDir
    Path dir;

    Fdrop app;
    final Principal admin = TestSupport.admin("root");

    private Fdrop start(FdropConfig cfg) throws Exception {
        LocalStorageBackend b = new LocalStorageBackend();
        b.init(dir);
        app = TestSupport.fdrop(cfg, b);
        app.accounts.ensure(admin);
        return app;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
    }

    private static void write(io.fdrop.core.storage.StorageBackend s, String key, String text) throws IOException {
        byte[] d = text.getBytes(StandardCharsets.UTF_8);
        try (WriteHandle h = s.openWrite(StoragePath.of(key), new WriteOptions(null, true, d.length))) {
            h.write(ByteBuffer.wrap(d));
            h.commit();
        }
    }

    private String read(String key) throws IOException {
        try (ReadableByteChannel ch = app.storage.openRead(StoragePath.of(key), 0, -1)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Channels.newInputStream(ch).transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private void runJob() throws Exception {
        for (int i = 0; i < 200 && app.encryptionService.status(admin).job().orElseThrow().state().equals("SCANNING");
             i++) {
            Thread.sleep(25);
        }
        app.encryptionService.work();
        assertThat(app.encryptionService.status(admin).job().orElseThrow().state()).isEqualTo("FINISHED");
    }

    @Test
    void migratesPlaintextAndRetiresAKey() throws Exception {
        FdropConfig cfg = TestSupport.config();
        cfg.storage.encryption.allowPlaintextReads = true;
        start(cfg);
        // a file from before encryption, written straight to the backend, and one written since
        write(app.encryption.inner(), "users/sub-root/old.txt", "stored before encryption");
        write(app.storage, "users/sub-root/new.txt", "stored encrypted");
        assertThat(app.encryptionService.inventory()).containsOnlyKeys("plaintext", "test1");

        assertThatThrownBy(() -> app.encryptionService.start(TestSupport.user("alice"), null, true))
                .isInstanceOf(FdropException.class);
        assertThatThrownBy(() -> app.encryptionService.start(admin, "test1", false))
                .isInstanceOf(FdropException.class).hasMessageContaining("primary");
        app.encryptionService.start(admin, null, true);
        runJob();
        assertThat(StreamFormat.hasMagic(Files.readAllBytes(dir.resolve("users/sub-root/old.txt")))).isTrue();
        assertThat(read("users/sub-root/old.txt")).isEqualTo("stored before encryption");
        EncryptionService.Status st = app.encryptionService.status(admin);
        assertThat(st.inventory()).containsOnlyKeys("test1");
        assertThat(st.inventory().get("test1").objects()).isEqualTo(2);
        assertThat(st.job().orElseThrow().objectsDone()).isEqualTo(1);

        // rotate: k2 becomes primary, test1 stays to read; then retire test1
        app.close();
        FdropConfig rotated = TestSupport.config();
        rotated.db.url = cfg.db.url;
        rotated.storage.encryption.local.keys = K2 + "," + TEST1;
        start(rotated);
        assertThat(read("users/sub-root/new.txt")).isEqualTo("stored encrypted");
        app.encryptionService.start(admin, "test1", false);
        assertThatThrownBy(() -> app.encryptionService.start(admin, "test1", false))
                .isInstanceOf(FdropException.class);
        runJob();
        assertThat(app.encryptionService.status(admin).inventory()).containsOnlyKeys("k2");

        // with test1 gone from the keyring, everything still reads
        app.close();
        FdropConfig retired = TestSupport.config();
        retired.db.url = cfg.db.url;
        retired.storage.encryption.local.keys = K2;
        start(retired);
        assertThat(read("users/sub-root/old.txt")).isEqualTo("stored before encryption");
        assertThat(read("users/sub-root/new.txt")).isEqualTo("stored encrypted");
    }

    @Test
    void startupFailsWithoutAWorkingKey() {
        FdropConfig cfg = TestSupport.config();
        cfg.storage.encryption.local.keys = "";
        assertThatThrownBy(() -> start(cfg)).hasMessageContaining("storage.encryption.local.keys");
    }
}
