package io.fdrop.core.service;

import io.fdrop.core.error.FdropException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostPolicyTest {

    @Test
    void emptyListDisablesForwarding() {
        assertThatThrownBy(() -> new HostPolicy(List.of()).check("example.com"))
                .isInstanceOf(FdropException.class).hasMessageContaining("disabled");
    }

    @Test
    void exactNamesAndRangesAllowPrivateTargets() {
        HostPolicy p = new HostPolicy(List.of("localhost", "10.0.0.0/8", "127.0.0.1/32"));
        assertThatCode(() -> p.check("localhost")).doesNotThrowAnyException();
        assertThatCode(() -> p.check("10.20.30.40")).doesNotThrowAnyException();
        assertThatCode(() -> p.check("127.0.0.1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> p.check("192.168.1.1")).isInstanceOf(FdropException.class);
    }

    @Test
    void wildcardsNeverReachPrivateAddresses() {
        HostPolicy p = new HostPolicy(List.of("*.localhost"));
        assertThatThrownBy(() -> p.check("foo.localhost")).isInstanceOf(FdropException.class);
    }

    @Test
    void cidrMatching() throws Exception {
        assertThat(HostPolicy.inCidr(InetAddress.getByName("10.1.2.3"), "10.0.0.0/8")).isTrue();
        assertThat(HostPolicy.inCidr(InetAddress.getByName("11.1.2.3"), "10.0.0.0/8")).isFalse();
        assertThat(HostPolicy.inCidr(InetAddress.getByName("192.168.5.9"), "192.168.4.0/23")).isTrue();
        assertThat(HostPolicy.inCidr(InetAddress.getByName("192.168.6.9"), "192.168.4.0/23")).isFalse();
    }
}
