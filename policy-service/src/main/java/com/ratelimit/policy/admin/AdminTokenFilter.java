package com.ratelimit.policy.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimit.rules.ApiKeyHash;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires the admin token on <em>every</em> request, whatever the path, so a new endpoint cannot be
 * added unprotected. Tokens are compared as hashes in constant time, so response timing reveals nothing
 * about how much of a guess was right.
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Token";

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);

    private final byte[] expected;
    private final ObjectMapper json;

    public AdminTokenFilter(AdminProperties properties, ObjectMapper json) {
        this.expected = digest(properties.adminToken());
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null || !MessageDigest.isEqual(expected, digest(presented))) {
            log.warn("Refused admin request without a valid token: {} from {}", request.getMethod(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(json.writeValueAsString(
                    new ApiError("unauthorized", "A valid " + HEADER + " header is required.")));
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] digest(String token) {
        return ApiKeyHash.of(token).getBytes(StandardCharsets.US_ASCII);
    }
}
