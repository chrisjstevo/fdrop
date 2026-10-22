package io.fdrop.core.storage;

import io.fdrop.core.error.FdropException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoragePathTest {

    @Test
    void normalises() {
        assertThat(StoragePath.of("/users//u1/./docs/").toString()).isEqualTo("users/u1/docs");
        assertThat(StoragePath.of("/").isRoot()).isTrue();
        assertThat(StoragePath.of("a/b/c").parent().toString()).isEqualTo("a/b");
        assertThat(StoragePath.of("a/b/c").name()).isEqualTo("c");
        assertThat(StoragePath.of("a/b/c").startsWith(StoragePath.of("a/b"))).isTrue();
        assertThat(StoragePath.of("a/bc").startsWith(StoragePath.of("a/b"))).isFalse();
        assertThat(StoragePath.of("a/b/c").relativize(StoragePath.of("a")).toString()).isEqualTo("b/c");
    }

    @Test
    void refusesUnsafeNames() {
        assertThatThrownBy(() -> StoragePath.of("a/../b")).isInstanceOf(FdropException.class);
        assertThatThrownBy(() -> StoragePath.of("a/\u0001")).isInstanceOf(FdropException.class);
        assertThatThrownBy(() -> StoragePath.of("a/b\\c")).isInstanceOf(FdropException.class);
        assertThatThrownBy(() -> StoragePath.of("x".repeat(256))).isInstanceOf(FdropException.class);
        assertThat(StoragePath.of("ünïcødé file (1).pdf").name()).isEqualTo("ünïcødé file (1).pdf");
    }
}
