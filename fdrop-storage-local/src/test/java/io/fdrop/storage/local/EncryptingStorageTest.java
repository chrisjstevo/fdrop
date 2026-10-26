package io.fdrop.storage.local;

import io.fdrop.core.config.FdropConfig;
import io.fdrop.core.crypto.EncryptingStorage;
import io.fdrop.core.crypto.EncryptionException;
import io.fdrop.core.crypto.LocalKeyProvider;
import io.fdrop.core.crypto.StreamFormat;
import io.fdrop.core.metrics.FdropMetrics;
import io.fdrop.core.storage.MultipartUpload;
import io.fdrop.core.storage.ObjectInfo;
import io.fdrop.core.storage.PartInfo;
import io.fdrop.core.storage.StoragePath;
import io.fdrop.core.storage.WriteHandle;
import io.fdrop.core.storage.WriteOptions;
import io.fdrop.core.util.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Encryption at rest over the local backend: the format on disk, ranged reads, tampering, multipart, keys. */
class EncryptingStorageTest {

    static final int S = StreamFormat.SEGMENT;
    static final String K1 = "k1:" + Base64.getEncoder().encodeToString(new byte[32]);
    static final String K2 = "k2:" + Base64.getEncoder().encodeToString(filled(32, (byte) 7));

    @TempDir
    Path dir;

    Path root;
    LocalStorageBackend local;
    FdropMetrics metrics = new FdropMetrics(List.of(1.0));

    @BeforeEach
    void setUp() throws Exception {
        root = dir.resolve("root");
        local = new LocalStorageBackend();
        local.init(root);
    }

    private static byte[] filled(int n, byte v) {
        byte[] b = new byte[n];
        Arrays.fill(b, v);
        return b;
    }

    private EncryptingStorage storage(String keyring, boolean enabled, boolean plaintextReads) {
        FdropConfig.Encryption cfg = new FdropConfig.Encryption();
        cfg.enabled = enabled;
        cfg.allowPlaintextReads = plaintextReads;
        cfg.local.keys = keyring;
        return new EncryptingStorage(local, keyring == null ? null : new LocalKeyProvider(keyring), cfg, metrics);
    }

    private EncryptingStorage storage() {
        return storage(K1, true, false);
    }

    private static byte[] data(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    private static void write(EncryptingStorage s, String key, byte[] data) throws IOException {
        try (WriteHandle h = s.openWrite(StoragePath.of(key), new WriteOptions(null, true, data.length))) {
            ByteBuffer b = ByteBuffer.wrap(data);
            // odd-sized writes, so segment boundaries fall inside them
            while (b.hasRemaining()) {
                int n = Math.min(b.remaining(), 10_007);
                h.write(b.slice(b.position(), n));
                b.position(b.position() + n);
            }
            assertThat(h.commit().size()).isEqualTo(data.length);
        }
    }

    private static byte[] read(EncryptingStorage s, String key, long offset, long length) throws IOException {
        try (ReadableByteChannel ch = s.openRead(StoragePath.of(key), offset, length)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Channels.newInputStream(ch).transferTo(out);
            return out.toByteArray();
        }
    }

    private Path raw(String key) {
        return root.resolve(key);
    }

    @Test
    void roundTripsAtSegmentBoundariesAndStoresOnlyCiphertext() throws Exception {
        EncryptingStorage s = storage();
        for (int n : new int[]{0, 1, S - 1, S, S + 1, 3 * S + 7}) {
            byte[] d = data(n);
            write(s, "users/u/f" + n, d);
            assertThat(read(s, "users/u/f" + n, 0, -1)).as("size %d", n).isEqualTo(d);
            assertThat(s.stat(StoragePath.of("users/u/f" + n)).orElseThrow().size()).isEqualTo(n);
            byte[] stored = Files.readAllBytes(raw("users/u/f" + n));
            assertThat(stored.length).isEqualTo(StreamFormat.storedSize(n));
            assertThat(StreamFormat.plainSize(stored.length)).isEqualTo(n);
            assertThat(StreamFormat.hasMagic(stored)).isTrue();
            if (n >= 64) {
                // no 64-byte run of the plaintext appears in what is stored
                assertThat(indexOf(stored, Arrays.copyOfRange(d, n / 2, n / 2 + 64))).isEqualTo(-1);
            }
        }
        assertThat(s.list(StoragePath.of("users/u"), false).filter(o -> o.name().equals("f" + S))
                .findFirst().orElseThrow().size()).isEqualTo(S);
        assertThat(s.usage(StoragePath.of("users/u"))).isEqualTo(0 + 1 + (S - 1) + S + (S + 1) + (3L * S + 7));
    }

    @Test
    void everyRangeOfAThreeSegmentFile() throws Exception {
        EncryptingStorage s = storage();
        byte[] d = data(3 * S - 100);
        write(s, "users/u/r.bin", d);
        long[] points = {0, 1, S - 1, S, S + 1, 2 * S - 1, 2 * S, 2 * S + 5, d.length - 1};
        for (long from : points) {
            for (long len : new long[]{1, 2, S - 1, S, S + 1, 2 * S + 3, d.length}) {
                byte[] got = read(s, "users/u/r.bin", from, len);
                int end = (int) Math.min(d.length, from + len);
                assertThat(got).as("range %d+%d", from, len).isEqualTo(Arrays.copyOfRange(d, (int) from, end));
            }
        }
        assertThat(read(s, "users/u/r.bin", d.length, 10)).isEmpty();
        assertThat(read(s, "users/u/r.bin", 7, -1)).isEqualTo(Arrays.copyOfRange(d, 7, d.length));
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private void assertFails(EncryptingStorage s, String key, EncryptionException.Reason reason) {
        assertThatThrownBy(() -> read(s, key, 0, -1)).isInstanceOfSatisfying(EncryptionException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void tamperingIsDetectedAndNoPlaintextIsReturned() throws Exception {
        byte[] d = data(3 * S + 10);
        String[] keys = {"users/u/seg", "users/u/hdr", "users/u/swap", "users/u/cut", "users/u/extra", "users/u/other"};
        EncryptingStorage s = storage();
        for (String k : keys) {
            write(s, k, d);
        }
        // a changed byte inside a segment, and inside the header
        flip(raw("users/u/seg"), StreamFormat.HEADER_LENGTH + S + 100);
        flip(raw("users/u/hdr"), 40);
        // two whole segments swapped
        byte[] b = Files.readAllBytes(raw("users/u/swap"));
        int a = (int) StreamFormat.segmentOffset(0);
        int c = (int) StreamFormat.segmentOffset(1);
        byte[] first = Arrays.copyOfRange(b, a, a + StreamFormat.STORED_SEGMENT);
        System.arraycopy(b, c, b, a, StreamFormat.STORED_SEGMENT);
        System.arraycopy(first, 0, b, c, StreamFormat.STORED_SEGMENT);
        Files.write(raw("users/u/swap"), b);
        // the final segment dropped: still a valid size, but the new last segment is not marked final
        byte[] cut = Files.readAllBytes(raw("users/u/cut"));
        Files.write(raw("users/u/cut"), Arrays.copyOf(cut, (int) StreamFormat.segmentOffset(3)));
        write(s, "users/u/short", d);
        byte[] sh = Files.readAllBytes(raw("users/u/short"));
        Files.write(raw("users/u/short"), Arrays.copyOf(sh, (int) StreamFormat.segmentOffset(3) + 5));
        // bytes after the final segment
        Files.write(raw("users/u/extra"), new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);

        // another object's header in front of this object's segments
        byte[] other = Files.readAllBytes(raw("users/u/other"));
        System.arraycopy(Files.readAllBytes(raw("users/u/seg")), 0, other, 0, StreamFormat.HEADER_LENGTH);
        Files.write(raw("users/u/other"), other);

        EncryptingStorage fresh = storage();
        assertFails(fresh, "users/u/seg", EncryptionException.Reason.SEGMENT);
        assertFails(fresh, "users/u/hdr", EncryptionException.Reason.HEADER);
        assertFails(fresh, "users/u/swap", EncryptionException.Reason.SEGMENT);
        assertFails(fresh, "users/u/cut", EncryptionException.Reason.SEGMENT);
        // three extra bytes still make a plausible size, so the stretched final segment fails to authenticate
        assertFails(fresh, "users/u/extra", EncryptionException.Reason.SEGMENT);
        assertFails(fresh, "users/u/other", EncryptionException.Reason.SEGMENT);
        // a stored size no valid object has: a final segment shorter than its nonce and tag
        assertFails(fresh, "users/u/short", EncryptionException.Reason.TRUNCATED);
        // a range that avoids the damaged segment still reads
        assertThat(read(fresh, "users/u/seg", 0, 100)).isEqualTo(Arrays.copyOf(d, 100));
        assertThat(metrics.scrape(null)).contains("fdrop_encryption_failures_total{reason=\"segment_auth\"}");
    }

    private static void flip(Path f, int at) throws IOException {
        byte[] b = Files.readAllBytes(f);
        b[at] ^= 0x01;
        Files.write(f, b);
    }

    @Test
    void multipartPartsEncryptIndependentlyAndResumeOnAnotherNode() throws Exception {
        int part = 2 * S;
        byte[] d = data(2 * part + 999);
        StoragePath target = StoragePath.of("users/u/big.bin");
        EncryptingStorage s = storage();
        MultipartUpload mp = s.beginMultipart(target, new WriteOptions(null, false, d.length, part));
        assertThat(mp.id()).startsWith("fdrop-enc1|").doesNotContain(Base64.getEncoder().encodeToString(new byte[32]));
        PartInfo p3 = put(mp, 3, Arrays.copyOfRange(d, 2 * part, d.length));
        // resent: encrypted again with new nonces, same plaintext
        p3 = put(mp, 3, Arrays.copyOfRange(d, 2 * part, d.length));
        // another node, cold caches, finds the header in the id
        EncryptingStorage other = storage();
        MultipartUpload again = other.resumeMultipart(target, mp.id());
        PartInfo p1 = put(again, 1, Arrays.copyOfRange(d, 0, part));
        PartInfo p2 = put(again, 2, Arrays.copyOfRange(d, part, 2 * part));
        assertThat(again.listParts()).extracting(PartInfo::size).containsExactlyInAnyOrder((long) part, (long) part,
                999L);
        ObjectInfo done = again.complete(List.of(p1, p2, p3));
        assertThat(done.size()).isEqualTo(d.length);
        assertThat(read(storage(), "users/u/big.bin", 0, -1)).isEqualTo(d);
        assertThat(Files.size(raw("users/u/big.bin"))).isEqualTo(StreamFormat.storedSize(d.length));

        MultipartUpload bad = s.beginMultipart(StoragePath.of("users/u/bad.bin"),
                new WriteOptions(null, false, 10, part));
        assertThatThrownBy(() -> bad.putPart(1, Channels.newChannel(new ByteArrayInputStream(new byte[10])), 10,
                "00".repeat(32))).isInstanceOf(IOException.class).hasMessageContaining("SHA-256");
        assertThat(bad.listParts()).isEmpty();
    }

    private static PartInfo put(MultipartUpload m, int n, byte[] d) throws IOException {
        return m.putPart(n, Channels.newChannel(new ByteArrayInputStream(d)), d.length, Hashing.sha256Hex(d));
    }

    @Test
    void plaintextIsServedOnlyWhileMigrating() throws Exception {
        storage(null, false, false);
        EncryptingStorage off = storage(null, false, false);
        byte[] d = data(5000);
        write(off, "users/u/old.txt", d);
        assertThat(Files.readAllBytes(raw("users/u/old.txt"))).isEqualTo(d);

        EncryptingStorage migrating = storage(K1, true, true);
        assertThat(read(migrating, "users/u/old.txt", 10, 20)).isEqualTo(Arrays.copyOfRange(d, 10, 30));
        assertThat(migrating.stat(StoragePath.of("users/u/old.txt")).orElseThrow().size()).isEqualTo(5000);
        write(migrating, "users/u/new.txt", d);
        assertThat(StreamFormat.hasMagic(Files.readAllBytes(raw("users/u/new.txt")))).isTrue();

        EncryptingStorage strict = storage(K1, true, false);
        assertFails(strict, "users/u/old.txt", EncryptionException.Reason.PLAINTEXT);
        assertThat(read(strict, "users/u/new.txt", 0, -1)).isEqualTo(d);
    }

    @Test
    void rotatedKeysKeepOldFilesReadable() throws Exception {
        byte[] d = data(S + 3);
        write(storage(K1, true, false), "users/u/a", d);
        EncryptingStorage rotated = storage(K2 + "," + K1, true, false);
        write(rotated, "users/u/b", d);
        assertThat(rotated.keyProvider().primaryKeyId()).isEqualTo("k2");
        assertThat(read(rotated, "users/u/a", 0, -1)).isEqualTo(d);
        assertThat(read(rotated, "users/u/b", 0, -1)).isEqualTo(d);
        assertFails(storage(K2, true, false), "users/u/a", EncryptionException.Reason.UNKNOWN_KEY);
        // turning encryption off still decrypts what is stored
        assertThat(read(storage(K2, false, false), "users/u/b", 0, -1)).isEqualTo(d);
    }
}
