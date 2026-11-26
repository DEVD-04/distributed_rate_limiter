package com.ratelimit.gateway.ratelimit.web;

import com.ratelimit.gateway.auth.Identity;
import com.ratelimit.rules.RuleContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stage 2: limits by user or API key, with the caller's tier, after authentication. Requests that
 * skipped authentication (public paths) have no identity and are limited by stage 1 only.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class PostAuthRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitEnforcer enforcer;

    public PostAuthRateLimitFilter(RateLimitEnforcer enforcer) {
        this.enforcer = enforcer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Identity identity = Identity.of(request);
        if (identity == null) {
            chain.doFilter(request, response);
            return;
        }
        RuleContext context = RuleContext.postAuth(identity.tier(), identity.userId(), identity.apiKeyId(),
                request.getMethod(), request.getRequestURI());
        if (enforcer.enforce(context, response)) {
            chain.doFilter(request, response);
        }
    }
}
