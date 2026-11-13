package com.ratelimit.gateway.web;

import jakarta.servlet.http.HttpServletRequest;

/** Per-request values set by the early filters and read by later pipeline stages. */
public final class RequestContext {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final String REQUEST_ID_ATTR = RequestContext.class.getName() + ".requestId";
    private static final String CLIENT_IP_ATTR = RequestContext.class.getName() + ".clientIp";

    private RequestContext() {
    }

    static void setRequestId(HttpServletRequest request, String requestId) {
        request.setAttribute(REQUEST_ID_ATTR, requestId);
    }

    static void setClientIp(HttpServletRequest request, String clientIp) {
        request.setAttribute(CLIENT_IP_ATTR, clientIp);
    }

    /** The request id assigned by {@link RequestIdFilter}, or {@code null} before it has run. */
    public static String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(REQUEST_ID_ATTR);
    }

    /** The client IP resolved by {@link ClientIpFilter}, or {@code null} before it has run. */
    public static String clientIp(HttpServletRequest request) {
        return (String) request.getAttribute(CLIENT_IP_ATTR);
    }
}
