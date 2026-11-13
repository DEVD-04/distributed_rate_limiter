package com.ratelimit.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClientIpResolverTests {

    /** nginx at 172.18.0.2 and an extra internal hop at 10.0.0.5 are trusted. */
    private final ClientIpResolver resolver =
            new ClientIpResolver(TrustedProxies.of(List.of("172.18.0.2", "10.0.0.0/24")));

    private String resolve(String remote, String... xff) {
        return resolver.resolve(remote, List.of(xff));
    }

    @Test
    void untrustedPeerIsTheClientAndForwardedForIsIgnored() {
        assertThat(resolve("203.0.113.9", "1.2.3.4")).isEqualTo("203.0.113.9");
    }

    @Test
    void noTrustedProxiesConfiguredAlwaysUsesTheSocketAddress() {
        ClientIpResolver none = new ClientIpResolver(TrustedProxies.of(List.of()));
        assertThat(none.resolve("172.18.0.2", List.of("1.2.3.4"))).isEqualTo("172.18.0.2");
    }

    @Test
    void trustedPeerWithoutHeaderIsItself() {
        assertThat(resolve("172.18.0.2")).isEqualTo("172.18.0.2");
        assertThat(resolve("172.18.0.2", "")).isEqualTo("172.18.0.2");
        assertThat(resolve("172.18.0.2", "   ")).isEqualTo("172.18.0.2");
    }

    @Test
    void trustedPeerPassesOnTheClientItReceivedFrom() {
        assertThat(resolve("172.18.0.2", "203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void clientCannotSpoofByPrependingToTheHeader() {
        // The client sent "X-Forwarded-For: 1.1.1.1"; nginx appended the real peer address.
        assertThat(resolve("172.18.0.2", "1.1.1.1, 203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void walksPastSeveralTrustedHops() {
        assertThat(resolve("172.18.0.2", "1.1.1.1, 203.0.113.9, 10.0.0.5")).isEqualTo("203.0.113.9");
    }

    @Test
    void multipleHeaderLinesAreTreatedAsOneChain() {
        assertThat(resolve("172.18.0.2", "1.1.1.1", "203.0.113.9, 10.0.0.5")).isEqualTo("203.0.113.9");
    }

    @Test
    void whenEveryHopIsTrustedTheLeftmostIsReturned() {
        assertThat(resolve("172.18.0.2", "10.0.0.7, 10.0.0.5")).isEqualTo("10.0.0.7");
    }

    @Test
    void garbageEntryStopsTheWalkAtTheLastValidHop() {
        assertThat(resolve("172.18.0.2", "203.0.113.9, not-an-ip")).isEqualTo("172.18.0.2");
        assertThat(resolve("172.18.0.2", "203.0.113.9:5000")).isEqualTo("172.18.0.2");
        assertThat(resolve("172.18.0.2", "203.0.113.9,,")).isEqualTo("172.18.0.2");
    }

    @Test
    void garbageBeyondAnUntrustedClientIsNeverReached() {
        assertThat(resolve("172.18.0.2", "garbage, 203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void ipv6AddressesAreReturnedInCanonicalForm() {
        assertThat(resolve("2001:db8:0:0:0:0:0:1")).isEqualTo("2001:db8::1");
        assertThat(resolve("172.18.0.2", "2001:0DB8:0:0:0:0:0:0001")).isEqualTo("2001:db8::1");
    }

    @Test
    void ipv4MappedIpv6IsTreatedAsIpv4() {
        assertThat(resolve("::ffff:172.18.0.2", "203.0.113.9")).isEqualTo("203.0.113.9");
    }
}
