package com.ratelimit.gateway.proxy;

import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Decides which headers cross the gateway in each direction. */
final class HeaderPolicy {

    /** Headers that describe one connection, not the message, and must never be forwarded (RFC 9110). */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    /** Headers the JDK HTTP client sets itself (and refuses to let callers set). */
    private static final Set<String> SET_BY_CLIENT = Set.of("host", "content-length", "expect");

    /**
     * Request headers the gateway either generates itself or must keep from the downstream service:
     * identity and forwarding headers a client could forge, and the credentials the gateway consumed.
     */
    private static final Set<String> GATEWAY_CONTROLLED_REQUEST = Set.of(
            "x-user-id", "x-tier", "x-request-id",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port", "x-real-ip", "forwarded",
            "authorization", "x-api-key");

    private static final Set<String> NEVER_COPIED_FROM_UPSTREAM = Set.of("date", "server", "x-request-id");

    private HeaderPolicy() {
    }

    /** A header to send, name and value. */
    record Header(String name, String value) {
    }

    /** The headers of the incoming request that may be passed to the downstream service. */
    static List<Header> forwardableRequestHeaders(HttpServletRequest request) {
        Set<String> listedInConnection = connectionListed(Collections.list(request.getHeaders("Connection")));
        List<Header> result = new ArrayList<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || SET_BY_CLIENT.contains(lower)
                    || GATEWAY_CONTROLLED_REQUEST.contains(lower) || listedInConnection.contains(lower)) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                result.add(new Header(name, value));
            }
        }
        return result;
    }

    /** The headers of the downstream response that may be passed to the client. */
    static List<Header> copyableResponseHeaders(HttpHeaders upstream) {
        Set<String> listedInConnection = connectionListed(upstream.allValues("connection"));
        List<Header> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : upstream.map().entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || listedInConnection.contains(lower)
                    || NEVER_COPIED_FROM_UPSTREAM.contains(lower) || lower.startsWith("x-ratelimit-")) {
                continue; // the gateway owns request ids and rate-limit headers
            }
            for (String value : entry.getValue()) {
                result.add(new Header(entry.getKey(), value));
            }
        }
        return result;
    }

    /** Header names a {@code Connection} header declares to be hop-by-hop for this message. */
    private static Set<String> connectionListed(List<String> connectionValues) {
        Set<String> names = new HashSet<>();
        for (String value : connectionValues) {
            for (String token : value.split(",")) {
                String name = token.trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
