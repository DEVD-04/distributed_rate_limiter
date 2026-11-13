package com.ratelimit.gateway.proxy;

import com.ratelimit.gateway.auth.Identity;
import com.ratelimit.gateway.proxy.UpstreamPool.Upstream;
import com.ratelimit.gateway.web.ErrorResponses;
import com.ratelimit.gateway.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Forwards a request to an instance of the downstream service its path routes to, and streams the
 * answer back. Runs on the request's virtual thread, so blocking on the upstream is cheap.
 *
 * <p>Failure handling:
 * <ul>
 *   <li>no route: 404 (TRACE and CONNECT never get this far: {@link RequestGuardFilter} answers 405);
 *   <li>connection refused or other I/O error: 502; timeout waiting for the upstream: 504;
 *   <li>a request without a body whose connection attempt failed is retried once on another instance;
 *       a request with a body is never retried, because its body may already have been consumed;
 *   <li>if the <em>client</em> fails while sending its body, that is the client's problem (400) and
 *       is not counted against the upstream.
 * </ul>
 *
 * <p>Passive health: connection errors, timeouts and 502/503/504 responses count as instance failures;
 * other responses, including 500, do not, since a single failing endpoint should not take an
 * instance out of rotation for every other endpoint.
 */
@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    private final RouteTable routes;
    private final HttpClient client;
    private final Duration readTimeout;
    private final ErrorResponses errors;

    public ProxyService(RouteTable routes, HttpClient client, ProxyProperties properties, ErrorResponses errors) {
        this.routes = routes;
        this.client = client;
        this.readTimeout = properties.readTimeout();
        this.errors = errors;
    }

    public void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<UpstreamPool> found = routes.find(request.getRequestURI());
        if (found.isEmpty()) {
            errors.write(request, response, 404, "not_found", "No route for this path.");
            return;
        }
        UpstreamPool pool = found.get();

        boolean hasBody = request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
        TrackingInputStream body = hasBody ? new TrackingInputStream(request.getInputStream()) : null;
        int maxAttempts = hasBody ? 1 : Math.min(2, pool.size());
        Set<Upstream> tried = new HashSet<>();

        for (int attempt = 1; ; attempt++) {
            Upstream upstream = pool.next(tried);
            tried.add(upstream);

            HttpRequest outbound;
            try {
                outbound = buildRequest(request, upstream, body);
            } catch (IllegalArgumentException e) {
                errors.write(request, response, 400, "bad_request", "Invalid request.");
                return;
            }

            HttpResponse<InputStream> inbound;
            try {
                inbound = client.send(outbound, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpConnectTimeoutException e) {
                pool.recordFailure(upstream);
                if (attempt < maxAttempts) {
                    continue;
                }
                log.warn("Upstream {} ({}) connect timeout", pool.service(), upstream);
                errors.write(request, response, 504, "gateway_timeout", "The upstream service timed out.");
                return;
            } catch (HttpTimeoutException e) {
                pool.recordFailure(upstream);
                log.warn("Upstream {} ({}) response timeout", pool.service(), upstream);
                errors.write(request, response, 504, "gateway_timeout", "The upstream service timed out.");
                return;
            } catch (ConnectException e) {
                pool.recordFailure(upstream);
                if (attempt < maxAttempts) {
                    continue;
                }
                log.warn("Upstream {} ({}) refused the connection", pool.service(), upstream);
                errors.write(request, response, 502, "bad_gateway", "The upstream service is unavailable.");
                return;
            } catch (IOException e) {
                if (body != null && body.failed()) {
                    errors.write(request, response, 400, "bad_request", "The request body could not be read.");
                    return;
                }
                pool.recordFailure(upstream);
                log.warn("Upstream {} ({}) I/O error: {}", pool.service(), upstream, e.toString());
                errors.write(request, response, 502, "bad_gateway", "The upstream service failed.");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.write(request, response, 502, "bad_gateway", "The request was interrupted.");
                return;
            }

            relay(response, pool, upstream, inbound);
            return;
        }
    }

    private HttpRequest buildRequest(HttpServletRequest request, Upstream upstream, TrackingInputStream body) {
        String base = upstream.base().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String query = request.getQueryString();
        URI uri = URI.create(base + request.getRequestURI() + (query == null ? "" : "?" + query));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(readTimeout);

        for (HeaderPolicy.Header header : HeaderPolicy.forwardableRequestHeaders(request)) {
            builder.header(header.name(), header.value());
        }
        // Set by the gateway, never taken from the client:
        builder.header("X-Request-Id", RequestContext.requestId(request));
        builder.header("X-Forwarded-For", RequestContext.clientIp(request));
        Identity identity = Identity.of(request);
        if (identity != null) {
            builder.header("X-User-Id", identity.userId());
            builder.header("X-Tier", identity.tier());
        }

        HttpRequest.BodyPublisher publisher;
        if (body == null) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else {
            HttpRequest.BodyPublisher streaming = HttpRequest.BodyPublishers.ofInputStream(() -> body);
            long length = request.getContentLengthLong();
            publisher = length >= 0 ? HttpRequest.BodyPublishers.fromPublisher(streaming, length) : streaming;
        }
        return builder.method(request.getMethod(), publisher).build();
    }

    /** Copies status, headers and body from the upstream response to the client. */
    private void relay(HttpServletResponse response, UpstreamPool pool, Upstream upstream,
            HttpResponse<InputStream> inbound) throws IOException {
        int status = inbound.statusCode();
        if (status == 502 || status == 503 || status == 504) {
            pool.recordFailure(upstream);
        } else {
            pool.recordSuccess(upstream);
        }

        response.setStatus(status);
        for (HeaderPolicy.Header header : HeaderPolicy.copyableResponseHeaders(inbound.headers())) {
            response.addHeader(header.name(), header.value());
        }

        try (InputStream in = inbound.body()) {
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                int read;
                try {
                    read = in.read(buffer);
                } catch (IOException e) {
                    // The upstream broke off mid-body. Headers are already sent, so the only honest
                    // signal left is to abort the response rather than end it as if complete.
                    pool.recordFailure(upstream);
                    throw e;
                }
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read); // a failure here means the client went away; not the upstream's fault
            }
        }
    }

    /** Remembers whether reading the client's request body failed, to tell client faults from upstream ones. */
    private static final class TrackingInputStream extends FilterInputStream {
        private volatile boolean failed;

        TrackingInputStream(InputStream in) {
            super(in);
        }

        boolean failed() {
            return failed;
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return super.read(b, off, len);
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }
    }
}
