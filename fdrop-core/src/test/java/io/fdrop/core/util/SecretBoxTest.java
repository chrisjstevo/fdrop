package io.fdrop.core.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretBoxTest {

    @Test
    void roundTripsAndDetectsTampering() {
        byte[] key = new byte[32];
        key[0] = 7;
        SecretBox box = SecretBox.fromBase64(Base64.getEncoder().encodeToString(key));
        byte[] sealed = box.seal("-----BEGIN OPENSSH PRIVATE KEY-----".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(box.open(sealed), StandardCharsets.UTF_8)).startsWith("-----BEGIN");
        assertThat(box.seal(new byte[]{1})).isNotEqualTo(box.seal(new byte[]{1}));
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> box.open(sealed)).isInstanceOf(IllegalStateException.class);
        assertThat(SecretBox.fromBase64("")).isNull();
    }
}
