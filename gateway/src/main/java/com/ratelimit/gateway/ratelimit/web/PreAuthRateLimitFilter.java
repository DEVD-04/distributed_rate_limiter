package com.ratelimit.gateway.ratelimit.web;

import com.ratelimit.gateway.rules.RuleContext;
import com.ratelimit.gateway.web.RequestContext;
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
 * Stage 1: limits by client IP before authentication, so a flood of requests with bad or missing
 * credentials is throttled instead of reaching the auth path. A token spent here is not refunded
 * if a later stage rejects the request; pre-auth traffic is exactly what this stage protects against.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PreAuthRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitEnforcer enforcer;

    public PreAuthRateLimitFilter(RateLimitEnforcer enforcer) {
        this.enforcer = enforcer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        RuleContext context = RuleContext.preAuth(
                RequestContext.clientIp(request), request.getMethod(), request.getRequestURI());
        if (enforcer.enforce(context, response)) {
            chain.doFilter(request, response);
        }
    }
}
