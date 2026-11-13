package com.ratelimit.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.net.InetAddresses;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedProxiesTests {

    private static boolean trusted(TrustedProxies proxies, String ip) {
        return proxies.isTrusted(InetAddresses.forString(ip));
    }

    @Test
    void emptyListTrustsNobody() {
        assertThat(trusted(TrustedProxies.of(List.of()), "127.0.0.1")).isFalse();
    }

    @Test
    void singleAddressMatchesOnlyItself() {
        TrustedProxies p = TrustedProxies.of(List.of("172.18.0.2"));
        assertThat(trusted(p, "172.18.0.2")).isTrue();
        assertThat(trusted(p, "172.18.0.3")).isFalse();
    }

    @Test
    void cidrRangesRespectThePrefixLength() {
        TrustedProxies p = TrustedProxies.of(List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.1.128/25"));
        assertThat(trusted(p, "10.255.255.255")).isTrue();
        assertThat(trusted(p, "11.0.0.1")).isFalse();
        assertThat(trusted(p, "172.31.255.255")).isTrue();
        assertThat(trusted(p, "172.32.0.1")).isFalse();
        assertThat(trusted(p, "192.168.1.200")).isTrue();
        assertThat(trusted(p, "192.168.1.100")).isFalse();
    }

    @Test
    void slashZeroMatchesEverythingInThatFamily() {
        TrustedProxies p = TrustedProxies.of(List.of("0.0.0.0/0"));
        assertThat(trusted(p, "203.0.113.9")).isTrue();
        assertThat(trusted(p, "2001:db8::1")).isFalse();
    }

    @Test
    void ipv6RangesAndLoopback() {
        TrustedProxies p = TrustedProxies.of(List.of("::1", "fd00::/8"));
        assertThat(trusted(p, "::1")).isTrue();
        assertThat(trusted(p, "fd12:3456::1")).isTrue();
        assertThat(trusted(p, "fe80::1")).isFalse();
    }

    @Test
    void entriesAreTrimmed() {
        assertThat(trusted(TrustedProxies.of(List.of("  10.0.0.1  ")), "10.0.0.1")).isTrue();
    }

    @Test
    void invalidEntriesFailFast() {
        for (String bad : new String[] {"not-an-ip", "10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/x", "::1/129", "", "10.0.0.256", "localhost"}) {
            assertThatThrownBy(() -> TrustedProxies.of(List.of(bad)))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
