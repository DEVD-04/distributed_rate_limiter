package com.ratelimit.gateway.ratelimit.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.api.Reason;
import com.ratelimit.rules.ResolvedRule;
import com.ratelimit.rules.RuleContext;
import com.ratelimit.rules.RuleResolver;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Applies one rate-limit stage to a request: picks the winning rule, charges the limiter, and either
 * annotates the response with the {@code X-RateLimit-*} headers or writes the refusal: 429, or 503 when
 * the rule fails closed and the limiter's store cannot be used.
 */
@Component
public class RateLimitEnforcer {

    private final RuleResolver rules;
    private final RateLimiter limiter;
    private final ObjectMapper json;

    public RateLimitEnforcer(RuleResolver rules, RateLimiter limiter, ObjectMapper json) {
        this.rules = rules;
        this.limiter = limiter;
        this.json = json;
    }

    /**
     * @return {@code true} if the request may continue; {@code false} if a refusal has been written and
     *         the request must go no further
     */
    public boolean enforce(RuleContext context, HttpServletResponse response) throws IOException {
        ResolvedRule resolved = rules.resolve(context);
        Decision decision = limiter.acquire(resolved.rule(), resolved.key(), 1);
        String policy = resolved.rule().name();

        // set for a 503 too: they replace any an earlier stage set, which would describe a different rule
        addRateLimitHeaders(response, policy, decision);
        if (decision.allowed()) {
            return true;
        }
        if (decision.reason() == Reason.FAILED_CLOSED) {
            writeRefusal(response, 503, "rate_limiter_unavailable", "The request cannot be checked right now.",
                    policy, decision);
        } else {
            writeRefusal(response, 429, "rate_limit_exceeded", "Too many requests.", policy, decision);
        }
        return false;
    }

    private static void addRateLimitHeaders(HttpServletResponse response, String policy, Decision decision) {
        response.setHeader("X-RateLimit-Limit", Long.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", Long.toString(wholeSecondsUp(decision.resetMs())));
        response.setHeader("X-RateLimit-Policy", policy);
    }

    private void writeRefusal(HttpServletResponse response, int status, String error, String what, String policy,
            Decision decision) throws IOException {
        long retryAfterSeconds = Math.max(1, wholeSecondsUp(decision.retryAfterMs()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", what + " Retry after " + retryAfterSeconds
                + (retryAfterSeconds == 1 ? " second." : " seconds."));
        body.put("retry_after_seconds", retryAfterSeconds);
        body.put("policy", policy);

        response.setStatus(status);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(body));
    }

    /** Milliseconds to whole seconds, rounded up, so a client never retries too early. */
    static long wholeSecondsUp(long millis) {
        return Math.ceilDiv(millis, 1000L);
    }
}
