package com.ratelimit.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTests {

    private final RequestIdFilter filter = new RequestIdFilter();

    private record Result(MockHttpServletRequest request, MockHttpServletResponse response, String mdcInsideChain) {
    }

    private Result run(String incomingId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        if (incomingId != null) {
            request.addHeader("X-Request-Id", incomingId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdc = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                mdc.set(MDC.get("requestId"));
            }
        };
        filter.doFilter(request, response, chain);
        return new Result(request, response, mdc.get());
    }

    @Test
    void generatesAnIdWhenNoneIsSupplied() throws Exception {
        Result r = run(null);

        String id = RequestContext.requestId(r.request());
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(r.response().getHeader("X-Request-Id")).isEqualTo(id);
    }

    @Test
    void propagatesAValidSuppliedId() throws Exception {
        Result r = run("client-req_42.a");

        assertThat(RequestContext.requestId(r.request())).isEqualTo("client-req_42.a");
        assertThat(r.response().getHeader("X-Request-Id")).isEqualTo("client-req_42.a");
    }

    @Test
    void replacesUnsafeIds() throws Exception {
        for (String bad : new String[] {"has space", "new\nline", "semi;colon", "", "a".repeat(129), "<script>"}) {
            Result r = run(bad);
            assertThat(RequestContext.requestId(r.request()))
                    .as("input %s", bad.length() > 20 ? bad.substring(0, 20) + "..." : bad)
                    .isNotEqualTo(bad)
                    .hasSize(36);
        }
    }

    @Test
    void acceptsAnIdAtTheLengthLimit() throws Exception {
        String max = "a".repeat(128);
        assertThat(RequestContext.requestId(run(max).request())).isEqualTo(max);
    }

    @Test
    void idIsInTheLoggingContextDuringTheRequestAndRemovedAfter() throws Exception {
        Result r = run("trace-1");

        assertThat(r.mdcInsideChain()).isEqualTo("trace-1");
        assertThat(MDC.get("requestId")).isNull();
    }
}
