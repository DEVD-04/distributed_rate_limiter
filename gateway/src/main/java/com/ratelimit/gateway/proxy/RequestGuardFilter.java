package com.ratelimit.gateway.proxy;

import com.ratelimit.gateway.web.ErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests the gateway will not handle, before anything else looks at them.
 *
 * <p><b>Paths that are not in canonical form.</b> Rate-limit rules match on the request path and the
 * proxy forwards that same path, so the two must always agree. A path such as
 * {@code /api/images/../admin} or {@code /api/images/%2e%2e/admin} would be matched as an images
 * request but could be resolved by the downstream service to {@code /admin}. Refusing anything that
 * could be interpreted two ways closes that gap, whatever the downstream does with it.
 *
 * <p><b>TRACE and CONNECT.</b> TRACE makes a server echo the request, credentials included; CONNECT
 * opens tunnels. A proxy has no business with either, and the gateway should not rely on the
 * servlet container's configuration to refuse them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestGuardFilter extends OncePerRequestFilter {

    private final ErrorResponses errors;

    public RequestGuardFilter(ErrorResponses errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        if (method.equals("TRACE") || method.equals("CONNECT")) {
            errors.write(request, response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "method_not_allowed", "Method not allowed.");
            return;
        }
        if (!isCanonical(request.getRequestURI())) {
            errors.write(request, response, HttpServletResponse.SC_BAD_REQUEST, "bad_request", "Invalid request path.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** @param rawPath the request path exactly as received, still percent-encoded */
    static boolean isCanonical(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/")) {
            return false;
        }
        for (int i = 0; i < rawPath.length(); i++) {
            char c = rawPath.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '\\' || c == ';') {
                return false; // control characters, spaces, non-ASCII, backslash, path parameters
            }
        }
        if (rawPath.contains("//")) {
            return false;
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")) {
            return false; // an encoded dot, slash, backslash or NUL
        }
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
