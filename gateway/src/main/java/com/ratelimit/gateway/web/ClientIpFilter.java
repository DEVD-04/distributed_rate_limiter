package com.ratelimit.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves the client IP once per request, right after the request id is assigned. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ClientIpFilter extends OncePerRequestFilter {

    private final ClientIpResolver resolver;

    public ClientIpFilter(ClientIpResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var forwardedFor = Collections.list(request.getHeaders("X-Forwarded-For"));
        RequestContext.setClientIp(request, resolver.resolve(request.getRemoteAddr(), forwardedFor));
        chain.doFilter(request, response);
    }
}
