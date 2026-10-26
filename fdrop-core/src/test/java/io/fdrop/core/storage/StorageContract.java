package io.fdrop.core.storage;

import io.fdrop.core.error.FdropException;
import io.fdrop.core.util.Hashing;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Storage SPI contract. Each backend's test extends this and supplies a fresh backend; the same behaviour is
 * then required of the local file system and S3 alike.
 */
public abstract class StorageContract {

    /** A clean, initialised backend for one test. */
    protected abstract StorageBackend backend() throws Exception;

    /** Smallest part size the backend accepts for non-final parts. */
    protected long minPartSize() {
        return 5L * 1024 * 1024;
    }

    protected static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    protected static void write(StorageBackend s, String key, byte[] data, boolean overwrite) throws IOException {
        try (WriteHandle h = s.openWrite(StoragePath.of(key), new WriteOptions(null, overwrite, data.length))) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) {
                h.write(buf);
            }
            h.commit();
        }
    }

    protected static byte[] read(StorageBackend s, String key, long offset, long length) throws IOException {
        try (ReadableByteChannel ch = s.openRead(StoragePath.of(key), offset, length)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Channels.newInputStream(ch).transferTo(out);
            return out.toByteArray();
        }
    }

    @Test
    public void writeIsInvisibleUntilCommit() throws Exception {
        StorageBackend s = backend();
        StoragePath p = StoragePath.of("users/u1/a.txt");
        WriteHandle h = s.openWrite(p, WriteOptions.DEFAULT);
        h.write(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(s.stat(p)).isEmpty();
        ObjectInfo info = h.commit();
        assertThat(info.size()).isEqualTo(5);
        assertThat(s.stat(p)).get().extracting(ObjectInfo::size).isEqualTo(5L);
        assertThat(read(s, "users/u1/a.txt", 0, -1)).asString(StandardCharsets.UTF_8).isEqualTo("hello");
    }

    @Test
    public void abortDiscards() throws Exception {
        StorageBackend s = backend();
        StoragePath p = StoragePath.of("users/u1/aborted.bin");
        WriteHandle h = s.openWrite(p, WriteOptions.DEFAULT);
        h.write(ByteBuffer.wrap(bytes(1000, 1)));
        h.abort();
        assertThat(s.stat(p)).isEmpty();
    }

    @Test
    public void refusesOverwriteUnlessAsked() throws Exception {
        StorageBackend s = backend();
        write(s, "users/u1/x", "one".getBytes(StandardCharsets.UTF_8), false);
        assertThatThrownBy(() -> write(s, "users/u1/x", "two".getBytes(StandardCharsets.UTF_8), false))
                .isInstanceOf(FdropException.class);
        write(s, "users/u1/x", "three".getBytes(StandardCharsets.UTF_8), true);
        assertThat(read(s, "users/u1/x", 0, -1)).asString(StandardCharsets.UTF_8).isEqualTo("three");
    }

    @Test
    public void rangedRead() throws Exception {
        StorageBackend s = backend();
        byte[] data = bytes(100_000, 2);
        write(s, "users/u1/r.bin", data, false);
        byte[] part = read(s, "users/u1/r.bin", 1000, 500);
        assertThat(part).hasSize(500);
        assertThat(part[0]).isEqualTo(data[1000]);
        assertThat(part[499]).isEqualTo(data[1499]);
        assertThat(read(s, "users/u1/r.bin", 99_990, -1)).hasSize(10);
    }

    @Test
    public void listsDirectChildrenAndRecursive() throws Exception {
        StorageBackend s = backend();
        write(s, "users/u1/docs/a.txt", bytes(10, 3), false);
        write(s, "users/u1/docs/sub/b.txt", bytes(20, 4), false);
        s.mkdirs(StoragePath.of("users/u1/docs/empty"));
        try (Stream<ObjectInfo> direct = s.list(StoragePath.of("users/u1/docs"), false)) {
            assertThat(direct.map(o -> o.name() + (o.directory() ? "/" : "")).sorted().toList())
                    .containsExactly("a.txt", "empty/", "sub/");
        }
        try (Stream<ObjectInfo> all = s.list(StoragePath.of("users/u1/docs"), true)) {
            assertThat(all.filter(o -> !o.directory()).map(o -> o.path().toString()).sorted().toList())
                    .containsExactly("users/u1/docs/a.txt", "users/u1/docs/sub/b.txt");
        }
        assertThat(s.stat(StoragePath.of("users/u1/docs/empty"))).get().extracting(ObjectInfo::directory)
                .isEqualTo(true);
        assertThat(s.usage(StoragePath.of("users/u1"))).isEqualTo(30);
    }

    @Test
    public void moveAndDeleteTrees() throws Exception {
        StorageBackend s = backend();
        write(s, "users/u1/t/a", bytes(5, 5), false);
        write(s, "users/u1/t/deep/b", bytes(6, 6), false);
        s.move(StoragePath.of("users/u1/t"), StoragePath.of("users/u1/t2"));
        assertThat(s.stat(StoragePath.of("users/u1/t"))).isEmpty();
        assertThat(s.stat(StoragePath.of("users/u1/t2/deep/b"))).isPresent();
        write(s, "users/u1/file", bytes(1, 7), false);
        s.move(StoragePath.of("users/u1/file"), StoragePath.of("users/u1/t2/file"));
        assertThat(s.stat(StoragePath.of("users/u1/t2/file"))).isPresent();
        s.delete(StoragePath.of("users/u1/t2"));
        assertThat(s.stat(StoragePath.of("users/u1/t2"))).isEmpty();
        assertThat(s.stat(StoragePath.of("users/u1/t2/deep/b"))).isEmpty();
        s.delete(StoragePath.of("users/u1/does-not-exist"));
    }

    @Test
    public void multipartConcatenatesInOrderAndSurvivesResume() throws Exception {
        StorageBackend s = backend();
        int part = (int) minPartSize();
        byte[] p1 = bytes(part, 10);
        byte[] p2 = bytes(part, 11);
        byte[] p3 = bytes(1234, 12);
        StoragePath target = StoragePath.of("users/u1/big.bin");
        // total and part size up front, as resumable uploads give them (encryption at rest needs both)
        MultipartUpload mp = s.beginMultipart(target, new WriteOptions(null, false, 2L * part + p3.length, part));
        PartInfo i2 = mp.putPart(2, Channels.newChannel(new java.io.ByteArrayInputStream(p2)), p2.length,
                Hashing.sha256Hex(p2));
        // a different handle, as after a restart
        MultipartUpload again = s.resumeMultipart(target, mp.id());
        PartInfo i1 = again.putPart(1, Channels.newChannel(new java.io.ByteArrayInputStream(p1)), p1.length, null);
        PartInfo i3 = again.putPart(3, Channels.newChannel(new java.io.ByteArrayInputStream(p3)), p3.length, null);
        assertThat(s.stat(target)).isEmpty();
        ObjectInfo done = again.complete(List.of(i1, i2, i3));
        assertThat(done.size()).isEqualTo(p1.length + p2.length + p3.length);
        byte[] all = read(s, "users/u1/big.bin", 0, -1);
        assertThat(java.util.Arrays.copyOfRange(all, 0, part)).isEqualTo(p1);
        assertThat(java.util.Arrays.copyOfRange(all, part, 2 * part)).isEqualTo(p2);
        assertThat(java.util.Arrays.copyOfRange(all, 2 * part, all.length)).isEqualTo(p3);
    }

    @Test
    public void multipartRejectsWrongHashAndShortParts() throws Exception {
        StorageBackend s = backend();
        MultipartUpload mp = s.beginMultipart(StoragePath.of("users/u1/bad.bin"),
                new WriteOptions(null, false, 1000, minPartSize()));
        byte[] data = bytes(1000, 13);
        assertThatThrownBy(() -> mp.putPart(1, Channels.newChannel(new java.io.ByteArrayInputStream(data)),
                data.length, "00".repeat(32))).isInstanceOf(IOException.class).hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> mp.putPart(1, Channels.newChannel(new java.io.ByteArrayInputStream(data)),
                data.length + 10, null)).isInstanceOf(IOException.class).hasMessageContaining("short");
        mp.abort();
    }

    @Test
    public void rejectsTraversal() {
        assertThatThrownBy(() -> StoragePath.of("users/u1/../u2/secret")).isInstanceOf(FdropException.class);
        assertThatThrownBy(() -> StoragePath.of("users/u1/a\u0000b")).isInstanceOf(FdropException.class);
    }
}
