package com.ratelimit.gateway.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.rules.RuleContext;
import com.ratelimit.rules.RuleSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitEnforcerTests {

    private static final RuleContext CONTEXT = RuleContext.postAuth("free", "alice", null, "GET", "/x");

    private static MockHttpServletResponse enforce(Decision decision, boolean expectedProceed) throws Exception {
        RateLimiter limiter = (rule, key, cost) -> decision;
        RateLimitEnforcer enforcer = new RateLimitEnforcer(RuleSet.of(1, List.of()), limiter, new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(enforcer.enforce(CONTEXT, response)).isEqualTo(expectedProceed);
        return response;
    }

    @Test
    void secondsAreRoundedUpSoClientsNeverRetryEarly() {
        assertThat(RateLimitEnforcer.wholeSecondsUp(0)).isZero();
        assertThat(RateLimitEnforcer.wholeSecondsUp(1)).isEqualTo(1);
        assertThat(RateLimitEnforcer.wholeSecondsUp(999)).isEqualTo(1);
        assertThat(RateLimitEnforcer.wholeSecondsUp(1000)).isEqualTo(1);
        assertThat(RateLimitEnforcer.wholeSecondsUp(1001)).isEqualTo(2);
        assertThat(RateLimitEnforcer.wholeSecondsUp(2500)).isEqualTo(3);
    }

    @Test
    void aSubSecondWaitIsReportedAsOneSecondInTheSingular() throws Exception {
        MockHttpServletResponse response = enforce(Decision.rejected(10, 0, 400, 5000), false);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"error\":\"rate_limit_exceeded\",\"message\":\"Too many requests. Retry after 1 second.\","
                        + "\"retry_after_seconds\":1,\"policy\":\"default-user\"}");
    }

    @Test
    void retryAfterIsNeverZeroEvenIfTheLimiterReportsZero() throws Exception {
        MockHttpServletResponse response = enforce(Decision.rejected(10, 0, 0, 0), false);

        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void waitsAreRoundedUpToWholeSeconds() throws Exception {
        MockHttpServletResponse response = enforce(Decision.rejected(10, 0, 2500, 9000), false);

        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo("9");
        assertThat(response.getContentAsString()).contains("Retry after 3 seconds.");
    }

    @Test
    void anAllowedRequestGetsHeadersAndNoBody() throws Exception {
        MockHttpServletResponse response = enforce(Decision.allowed(100, 42, 2200), true);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("100");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("42");
        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo("3");
        assertThat(response.getHeader("X-RateLimit-Policy")).isEqualTo("default-user");
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(response.getContentAsString()).isEmpty();
    }
}
