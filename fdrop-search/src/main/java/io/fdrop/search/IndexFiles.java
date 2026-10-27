package io.fdrop.search;

import io.fdrop.core.crypto.EncryptingStorage;
import io.fdrop.core.crypto.KeyProvider;
import io.fdrop.core.crypto.StreamFormat;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;

/** A Lucene index's directory on this node and its key: shared by the file search index and the audit index. */
final class IndexFiles {

    private IndexFiles() {
    }

    /**
     * The index key, wrapped by the storage KEK in {@code root/index.key}; null when encryption at rest is off. When
     * encryption is turned on or off, the index in {@code root/index} is thrown away, to be rebuilt.
     */
    static byte[] key(Path root, EncryptingStorage encryption) throws IOException {
        KeyProvider kp = encryption.keyProvider();
        Path file = root.resolve("index.key");
        if (kp == null || !encryption.enabled()) {
            if (Files.exists(file) && kp != null) {
                return readKey(file, kp);
            }
            if (Files.exists(file)) {
                wipe(root.resolve("index"));
                Files.delete(file);
            }
            return null;
        }
        if (Files.exists(file)) {
            return readKey(file, kp);
        }
        wipe(root.resolve("index"));
        byte[] key = StreamFormat.newDataKey();
        writeKey(file, kp, key);
        return key;
    }

    private static byte[] readKey(Path file, KeyProvider kp) throws IOException {
        String[] f = Files.readString(file, StandardCharsets.UTF_8).strip().split("\\|", 2);
        byte[] key = kp.unwrap(f[0], Base64.getDecoder().decode(f[1]));
        if (!f[0].equals(kp.primaryKeyId()) && !kp.primaryKeyId().startsWith("vault:")) {
            writeKey(file, kp, key);
        }
        return key;
    }

    private static void writeKey(Path file, KeyProvider kp, byte[] key) throws IOException {
        KeyProvider.Wrapped w = kp.wrap(key);
        Path tmp = file.resolveSibling("index.key.tmp");
        Files.writeString(tmp, w.keyId() + "|" + Base64.getEncoder().encodeToString(w.wrapped()),
                StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    static Directory directory(Path path, byte[] key) throws IOException {
        Directory fs = FSDirectory.open(path);
        return key == null ? fs : new EncryptedDirectory(fs, key);
    }

    static void wipe(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> s = Files.walk(path)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    static long size(Path path) {
        if (!Files.exists(path)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(path)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
