package com.ratelimit.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server standing in for a downstream service. Behaviour depends on the request path:
 * <ul>
 *   <li>{@code .../slow}: answers after 1.5 s;
 *   <li>{@code .../status/NNN}: answers with that status;
 *   <li>{@code .../big}: answers with a 1 MiB body;
 *   <li>anything else: a JSON description of the request it received.
 * </ul>
 * Every response also carries headers the gateway must not let through ({@code X-RateLimit-Limit},
 * {@code X-Request-Id}) and one it should ({@code X-Custom}).
 */
final class StubUpstream implements AutoCloseable {

    static final int BIG_RESPONSE_BYTES = 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final String name;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Map<String, List<String>> lastHeaders = Map.of();

    StubUpstream(String name) throws IOException {
        this.name = name;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /** A loopback URL nothing is listening on, so connections to it are refused. */
    static String deadUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requests() {
        return requests.get();
    }

    /** Headers of the most recent request, names lower-cased. */
    Map<String, List<String>> lastHeaders() {
        return lastHeaders;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        Map<String, List<String>> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
        lastHeaders = headers;

        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getRawPath();
        String uri = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());

        exchange.getResponseHeaders().add("X-Instance", name);
        exchange.getResponseHeaders().add("X-Custom", "yes");
        exchange.getResponseHeaders().add("X-RateLimit-Limit", "999");
        exchange.getResponseHeaders().add("X-Request-Id", "upstream-id");

        try (exchange) {
            if (path.endsWith("/slow")) {
                sleep(1500);
            }
            if (path.contains("/status/")) {
                int status = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                respond(exchange, status, "status " + status);
            } else if (path.endsWith("/big")) {
                respondBytes(exchange, 200, "application/octet-stream", bigBody());
            } else {
                Map<String, Object> echo = new LinkedHashMap<>();
                echo.put("instance", name);
                echo.put("method", exchange.getRequestMethod());
                echo.put("uri", uri);
                echo.put("bodyLength", requestBody.length);
                echo.put("bodySha256", sha256(requestBody));
                respondBytes(exchange, 200, "application/json", JSON.writeValueAsBytes(echo));
            }
        }
    }

    static byte[] bigBody() {
        byte[] body = new byte[BIG_RESPONSE_BYTES];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i % 251);
        }
        return body;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        respondBytes(exchange, status, "text/plain", text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void respondBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
