package com.ratelimit.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ratelimit.gateway.ratelimit.FakeNanoClock;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteTableTests {

    private static ProxyProperties.RouteEntry route(String pattern, String service, String... instances) {
        return new ProxyProperties.RouteEntry(pattern, service, java.util.Arrays.stream(instances).map(URI::create).toList());
    }

    private static RouteTable table(ProxyProperties.RouteEntry... routes) {
        return RouteTable.of(new ProxyProperties(List.of(routes), Duration.ofSeconds(1), Duration.ofSeconds(5),
                new ProxyProperties.Ejection(3, Duration.ofSeconds(10))), new FakeNanoClock());
    }

    private static String serviceFor(RouteTable table, String path) {
        return table.find(path).map(UpstreamPool::service).orElse(null);
    }

    @Test
    void routesByPathPrefix() {
        RouteTable table = table(
                route("/api/images/**", "images", "http://i1:8080", "http://i2:8080"),
                route("/api/text/**", "text", "http://t1:8080"));

        assertThat(serviceFor(table, "/api/images/cat.png")).isEqualTo("images");
        assertThat(serviceFor(table, "/api/text/summarize")).isEqualTo("text");
        assertThat(table.find("/api/images/x").orElseThrow().size()).isEqualTo(2);
    }

    @Test
    void theMostSpecificPatternWinsRegardlessOfConfigurationOrder() {
        RouteTable table = table(
                route("/api/**", "generic", "http://g:1"),
                route("/api/images/**", "images", "http://i:1"),
                route("/api/images/raw/*", "raw", "http://r:1"));

        assertThat(serviceFor(table, "/api/other")).isEqualTo("generic");
        assertThat(serviceFor(table, "/api/images/x")).isEqualTo("images");
        assertThat(serviceFor(table, "/api/images/raw/x")).isEqualTo("raw");
    }

    @Test
    void unroutedPathsHaveNoPool() {
        RouteTable table = table(route("/api/**", "api", "http://a:1"));

        assertThat(table.find("/other")).isEmpty();
        assertThat(table.find("/")).isEmpty();
    }

    @Test
    void poolsCanBeLookedUpByServiceName() {
        RouteTable table = table(route("/api/**", "api", "http://a:1"));

        assertThat(table.pool("api")).isPresent();
        assertThat(table.pool("nope")).isEmpty();
    }

    @Test
    void invalidRoutesAreRejectedNamingTheirPosition() {
        assertThatThrownBy(() -> table(route("api/**", "x", "http://a:1")))
                .hasMessageContaining("routes[0]").hasMessageContaining("start with '/'");
        assertThatThrownBy(() -> table(route("/a/**", " ", "http://a:1")))
                .hasMessageContaining("service name");
        assertThatThrownBy(() -> table(route("/a/**", "x")))
                .hasMessageContaining("at least one instance");
        assertThatThrownBy(() -> table(route("/a/**/b", "x", "http://a:1")))
                .hasMessageContaining("invalid pattern");
        assertThatThrownBy(() -> table(route("/a/**", "x", "http://a:1"), route("/b/**", "x", "http://b:1")))
                .hasMessageContaining("routes[1]").hasMessageContaining("duplicate service");
        assertThatThrownBy(() -> table(route("/a/**", "x", "http://a:1"), route("/a/**", "y", "http://b:1")))
                .hasMessageContaining("duplicate pattern");
    }

    @Test
    void instancesMustBeBareHttpBaseUrls() {
        assertThatThrownBy(() -> table(route("/a/**", "x", "ftp://a:1"))).hasMessageContaining("http(s)");
        assertThatThrownBy(() -> table(route("/a/**", "x", "a:1"))).hasMessageContaining("http(s)");
        assertThatThrownBy(() -> table(route("/a/**", "x", "http://a:1/base"))).hasMessageContaining("scheme://host");
        assertThatThrownBy(() -> table(route("/a/**", "x", "http://a:1?x=1"))).hasMessageContaining("scheme://host");
        assertThatThrownBy(() -> table(route("/a/**", "x", "http://user:pw@a:1"))).hasMessageContaining("scheme://host");
        table(route("/a/**", "x", "http://a:1/", "https://b"));
    }
}
