package com.ratelimit.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HeaderPolicyTests {

    private static List<String> names(List<HeaderPolicy.Header> headers) {
        return headers.stream().map(h -> h.name().toLowerCase()).distinct().toList();
    }

    @Test
    void ordinaryRequestHeadersPassThroughIncludingRepeatedOnes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept", "application/json");
        request.addHeader("User-Agent", "test/1.0");
        request.addHeader("Cookie", "a=b");
        request.addHeader("X-Custom", "one");
        request.addHeader("X-Custom", "two");

        List<HeaderPolicy.Header> forwarded = HeaderPolicy.forwardableRequestHeaders(request);

        assertThat(names(forwarded)).containsExactlyInAnyOrder("accept", "user-agent", "cookie", "x-custom");
        assertThat(forwarded).filteredOn(h -> h.name().equals("X-Custom")).extracting(HeaderPolicy.Header::value)
                .containsExactly("one", "two");
    }

    @Test
    void hopByHopHeadersAreDropped() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (String name : List.of("Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "TE",
                "Trailer", "Transfer-Encoding", "Upgrade")) {
            request.addHeader(name, "x");
        }
        request.addHeader("Accept", "*/*");

        assertThat(names(HeaderPolicy.forwardableRequestHeaders(request))).containsExactly("accept");
    }

    @Test
    void headersNamedInConnectionAreDroppedToo() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Connection", "close, X-Hop , x-other");
        request.addHeader("X-Hop", "1");
        request.addHeader("X-Other", "2");
        request.addHeader("X-Kept", "3");

        assertThat(names(HeaderPolicy.forwardableRequestHeaders(request))).containsExactly("x-kept");
    }

    @Test
    void headersTheJdkClientSetsItselfAreDropped() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "gateway");
        request.addHeader("Content-Length", "5");
        request.addHeader("Expect", "100-continue");
        request.addHeader("Accept", "*/*");

        assertThat(names(HeaderPolicy.forwardableRequestHeaders(request))).containsExactly("accept");
    }

    @Test
    void identityForwardingAndCredentialHeadersAreNeverTakenFromTheClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (String name : List.of("X-User-Id", "X-Tier", "X-Request-Id", "X-Forwarded-For", "X-Forwarded-Host",
                "X-Forwarded-Proto", "X-Forwarded-Port", "X-Real-IP", "Forwarded", "Authorization", "X-API-Key")) {
            request.addHeader(name, "forged");
        }
        request.addHeader("Accept", "*/*");

        assertThat(names(HeaderPolicy.forwardableRequestHeaders(request))).containsExactly("accept");
    }

    @Test
    void headerNamesAreMatchedCaseInsensitively() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-USER-id", "forged");
        request.addHeader("AUTHORIZATION", "Bearer x");
        request.addHeader("cOnNeCtIoN", "close");

        assertThat(HeaderPolicy.forwardableRequestHeaders(request)).isEmpty();
    }

    private static HttpHeaders upstream(Map<String, List<String>> map) {
        return HttpHeaders.of(map, (a, b) -> true);
    }

    @Test
    void ordinaryResponseHeadersAreCopiedIncludingRepeatedOnes() {
        HttpHeaders headers = upstream(Map.of(
                "Content-Type", List.of("application/json"),
                "Content-Length", List.of("12"),
                "Set-Cookie", List.of("a=1", "b=2"),
                "X-Custom", List.of("yes")));

        List<HeaderPolicy.Header> copied = HeaderPolicy.copyableResponseHeaders(headers);

        assertThat(names(copied)).containsExactlyInAnyOrder("content-type", "content-length", "set-cookie", "x-custom");
        assertThat(copied).filteredOn(h -> h.name().equalsIgnoreCase("set-cookie")).hasSize(2);
    }

    @Test
    void hopByHopAndConnectionListedResponseHeadersAreDropped() {
        HttpHeaders headers = upstream(Map.of(
                "Connection", List.of("close, X-Hop"),
                "Keep-Alive", List.of("timeout=5"),
                "Transfer-Encoding", List.of("chunked"),
                "X-Hop", List.of("1"),
                "X-Kept", List.of("2")));

        assertThat(names(HeaderPolicy.copyableResponseHeaders(headers))).containsExactly("x-kept");
    }

    @Test
    void theGatewayOwnsRequestIdRateLimitDateAndServerHeaders() {
        HttpHeaders headers = upstream(Map.of(
                "X-Request-Id", List.of("upstream"),
                "X-RateLimit-Limit", List.of("999"),
                "X-RateLimit-Remaining", List.of("999"),
                "X-RateLimit-Policy", List.of("fake"),
                "Date", List.of("whenever"),
                "Server", List.of("secret-internal-1.0"),
                "X-Kept", List.of("ok")));

        assertThat(names(HeaderPolicy.copyableResponseHeaders(headers))).containsExactly("x-kept");
    }
}
