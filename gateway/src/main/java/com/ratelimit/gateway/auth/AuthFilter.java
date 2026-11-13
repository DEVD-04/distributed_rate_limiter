package com.ratelimit.gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimit.gateway.web.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the request from {@code Authorization: Bearer <jwt>} or {@code X-API-Key}.
 *
 * <p>If an {@code Authorization} header is present it alone decides: a bad token is a 401 even
 * when a valid API key is also sent, so a caller cannot probe both mechanisms at once. Failures
 * always return the same generic body; the precise reason goes to the log only.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtValidator jwtValidator;
    private final ApiKeyStore apiKeys;
    private final List<String> publicPaths;
    private final ObjectMapper json;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public AuthFilter(JwtValidator jwtValidator, ApiKeyStore apiKeys, AuthProperties properties,
            ObjectMapper json) {
        this.jwtValidator = jwtValidator;
        this.apiKeys = apiKeys;
        this.publicPaths = properties.publicPaths();
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return publicPaths.stream().anyMatch(pattern -> matcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            Identity.attachTo(request, authenticate(request));
        } catch (AuthException e) {
            log.warn("Authentication failed: {}", e.getMessage());
            reject(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private Identity authenticate(HttpServletRequest request) throws AuthException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            if (!authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
                throw new AuthException(AuthException.Reason.MALFORMED, "unsupported Authorization scheme");
            }
            return jwtValidator.validate(authorization.substring(BEARER.length()).trim());
        }
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKeys.findByHash(ApiKeyStore.sha256Hex(apiKey.trim()))
                    .orElseThrow(() -> new AuthException(AuthException.Reason.UNKNOWN_API_KEY, "no such active key"));
        }
        throw new AuthException(AuthException.Reason.MISSING_CREDENTIALS, "no Authorization or X-API-Key");
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "unauthorized");
        body.put("message", "Missing, invalid or expired credentials.");
        body.put("request_id", RequestContext.requestId(request));

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(body));
    }
}
