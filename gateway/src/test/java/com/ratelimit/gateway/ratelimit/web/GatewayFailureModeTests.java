package com.ratelimit.gateway.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimit.gateway.GatewayApplication;
import com.ratelimit.gateway.proxy.StubUpstream;
import com.ratelimit.gateway.ratelimit.sync.NodeRegistry;
import com.ratelimit.gateway.redis.LateRedis;
import com.ratelimit.rules.ApiKeyHash;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Every {@code failure_mode}, in both modes and all algorithms, in running gateways without their Redis. */
@Testcontainers
class GatewayFailureModeTests {

    private static final String API_KEY = "failure-mode-test-key";
    private static final String NEVER_THERE = "redis://127.0.0.1:1";
    private static final String NO_REFILL = "params.refill-per-sec=0.000001";

    static StubUpstream upstream;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<ConfigurableApplicationContext> gateways = new ArrayList<>();
    private final List<String> ruleArgs = new ArrayList<>();
    private int rules;

    @BeforeAll
    static void startUpstream() throws IOException {
        upstream = new StubUpstream("images");
    }

    @AfterAll
    static void stopUpstream() {
        upstream.close();
    }

    @AfterEach
    void stopGateways() {
        gateways.forEach(ConfigurableApplicationContext::close);
    }

    /** A user rule for {@code /api/images/<name>/**}. */
    private void rule(String name, String mode, String failureMode, String algorithm, String... params) {
        String prefix = "--gateway.ratelimit.rules[" + rules++ + "].";
        ruleArgs.addAll(List.of(prefix + "id=" + (100 + rules), prefix + "name=" + name, prefix + "limit-by=USER",
                prefix + "endpoint-pattern=/api/images/" + name + "/**", prefix + "algorithm=" + algorithm,
                prefix + "mode=" + mode, prefix + "failure-mode=" + failureMode));
        for (String param : params) {
            ruleArgs.add(prefix + param);
        }
    }

    /**
     * The built-in per-IP rule falls back to a local limit too, which tests sending many requests would run
     * into. This replaces it with one that fails open; a test of the IP stage defines its own instead.
     */
    private void openIpStageUnlessDefined() {
        if (ruleArgs.stream().noneMatch(arg -> arg.endsWith(".limit-by=IP"))) {
            String prefix = "--gateway.ratelimit.rules[" + rules++ + "].";
            ruleArgs.addAll(List.of(prefix + "id=99", prefix + "name=ip-open", prefix + "limit-by=IP",
                    prefix + "endpoint-pattern=/**", prefix + "algorithm=TOKEN_BUCKET", prefix + "failure-mode=FAIL_OPEN",
                    prefix + "params.capacity=1000000", prefix + "params.refill-per-sec=1000000"));
        }
    }

    private ConfigurableApplicationContext startGateway(String redisUrl, String... extra) {
        openIpStageUnlessDefined();
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--gateway.redis.url=" + redisUrl,
                "--gateway.redis.command-timeout=200ms",
                "--gateway.auth.api-keys[0].key-hash=" + ApiKeyHash.of(API_KEY),
                "--gateway.auth.api-keys[0].user-id=alice",
                "--gateway.auth.api-keys[0].tier=free",
                "--gateway.proxy.routes[0].pattern=/api/images/**",
                "--gateway.proxy.routes[0].service=images",
                "--gateway.proxy.routes[0].instances[0]=" + upstream.url()));
        args.addAll(ruleArgs);
        args.addAll(List.of(extra));
        ConfigurableApplicationContext gateway =
                new SpringApplicationBuilder(GatewayApplication.class).run(args.toArray(String[]::new));
        gateways.add(gateway);
        return gateway;
    }

    private HttpResponse<String> call(ConfigurableApplicationContext gateway, String ruleName) {
        int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + "/api/images/" + ruleName + "/a"))
                .header("X-API-Key", API_KEY).timeout(Duration.ofSeconds(10)).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** How many answers of each status {@code requests} requests got. */
    private String statuses(ConfigurableApplicationContext gateway, String ruleName, int requests) {
        var counts = new TreeMap<Integer, Integer>();
        for (int i = 0; i < requests; i++) {
            counts.merge(call(gateway, ruleName).statusCode(), 1, Integer::sum);
        }
        return counts.toString();
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    @Test
    void eachRuleFailsTheWayItSaysInBothModesAndForEveryAlgorithm() {
        for (String mode : new String[] {"STRICT", "LOCAL_SYNC"}) {
            String m = mode.equals("STRICT") ? "strict" : "sync";
            rule(m + "-open", mode, "FAIL_OPEN", "TOKEN_BUCKET", "params.capacity=5", NO_REFILL);
            rule(m + "-closed", mode, "FAIL_CLOSED", "TOKEN_BUCKET", "params.capacity=5", NO_REFILL);
            rule(m + "-fallback", mode, "LOCAL_FALLBACK", "TOKEN_BUCKET", "params.capacity=5", NO_REFILL);
        }
        for (String failureMode : new String[] {"FAIL_OPEN", "FAIL_CLOSED", "LOCAL_FALLBACK"}) {
            String f = failureMode.toLowerCase().replace('_', '-');
            rule("window-" + f, "STRICT", failureMode, "SLIDING_WINDOW_LOG", "params.limit=4", "params.window-ms=60000");
            // 1 a second, so at most 3 s of waiting; the test takes the immediate slot and one queued one
            rule("leaky-" + f, "STRICT", failureMode, "LEAKY_BUCKET_QUEUE", "params.drain-per-sec=1",
                    "params.queue-capacity=3");
        }
        var gateway = startGateway(NEVER_THERE); // it counts only itself, so its share is the whole limit

        for (String m : new String[] {"strict", "sync"}) {
            assertThat(statuses(gateway, m + "-open", 20)).as(m).isEqualTo("{200=20}");
            assertThat(statuses(gateway, m + "-closed", 20)).as(m).isEqualTo("{503=20}");
            assertThat(statuses(gateway, m + "-fallback", 20)).as(m).isEqualTo("{200=5, 429=15}");
        }
        assertThat(statuses(gateway, "window-fail-open", 10)).isEqualTo("{200=10}");
        assertThat(statuses(gateway, "window-fail-closed", 10)).isEqualTo("{503=10}");
        assertThat(statuses(gateway, "window-local-fallback", 10)).isEqualTo("{200=4, 429=6}");

        long start = System.nanoTime();
        assertThat(statuses(gateway, "leaky-fail-open", 3)).isEqualTo("{200=3}");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("not queued").isLessThan(Duration.ofMillis(900));
        assertThat(statuses(gateway, "leaky-fail-closed", 3)).isEqualTo("{503=3}");
        start = System.nanoTime();
        assertThat(statuses(gateway, "leaky-local-fallback", 2)).isEqualTo("{200=2}");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("the second waits its turn")
                .isGreaterThan(Duration.ofMillis(800));
    }

    @Test
    void aFailClosedRefusalIsA503ThatSaysWhenToRetryAndWhichPolicy() throws IOException {
        rule("expensive", "STRICT", "FAIL_CLOSED", "TOKEN_BUCKET", "params.capacity=5", NO_REFILL);
        var gateway = startGateway(NEVER_THERE, "--gateway.redis.breaker.open-for=30s");
        // enough calls to open the breaker, after which the whole open period is quoted
        for (int i = 0; i < 20; i++) {
            call(gateway, "expensive");
        }
        int before = upstream.requests();

        HttpResponse<String> response = call(gateway, "expensive");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(header(response, "Retry-After")).isEqualTo("30");
        assertThat(header(response, "X-RateLimit-Policy")).isEqualTo("expensive");
        assertThat(header(response, "X-RateLimit-Limit")).as("not the IP stage's").isEqualTo("5");
        assertThat(header(response, "X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(header(response, "X-Request-Id")).isNotBlank();
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.get("error").asText()).isEqualTo("rate_limiter_unavailable");
        assertThat(body.get("retry_after_seconds").asInt()).isEqualTo(30);
        assertThat(body.get("policy").asText()).isEqualTo("expensive");
        assertThat(upstream.requests()).as("never reached the service").isEqualTo(before);
    }

    @Test
    void threeGatewaysThatLoseRedisEachApplyAThirdOfTheLimit() {
        rule("tb", "STRICT", "LOCAL_FALLBACK", "TOKEN_BUCKET", "params.capacity=30", NO_REFILL);
        rule("window", "STRICT", "LOCAL_FALLBACK", "SLIDING_WINDOW_LOG", "params.limit=30", "params.window-ms=60000");
        try (var redis = new LateRedis()) {
            redis.start();
            var three = List.of(startGateway(redis.url()), startGateway(redis.url()), startGateway(redis.url()));
            await().atMost(Duration.ofSeconds(5)).until(
                    () -> three.stream().allMatch(g -> g.getBean(NodeRegistry.class).nodeCount() == 3));

            redis.stopServer();

            for (String ruleName : new String[] {"tb", "window"}) {
                for (var gateway : three) {
                    assertThat(statuses(gateway, ruleName, 25)).as(ruleName).isEqualTo("{200=10, 429=15}");
                }
            }
        }
    }

    @Test
    void localSyncSpendsWhatItHasBorrowedBeforeFailingClosedAndRecoversAfterwards() {
        rule("expensive", "LOCAL_SYNC", "FAIL_CLOSED", "TOKEN_BUCKET", "params.capacity=100",
                "params.refill-per-sec=1");
        try (var redis = new LateRedis()) {
            redis.start();
            // a batch of 5, spendable for a minute
            var gateway = startGateway(redis.url(), "--gateway.lease.min-batch=5", "--gateway.lease.horizon=30s",
                    "--gateway.lease.refill-below=0", "--gateway.redis.breaker.open-for=1s");
            assertThat(call(gateway, "expensive").statusCode()).isEqualTo(200);

            redis.stopServer();

            assertThat(statuses(gateway, "expensive", 4)).isEqualTo("{200=4}");
            assertThat(statuses(gateway, "expensive", 10)).isEqualTo("{503=10}");

            redis.startServerAgain();
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                    .until(() -> call(gateway, "expensive").statusCode() == 200);
        }
    }

    @Test
    void anIpRuleThatFailsClosedRefusesBeforeAuthentication() {
        String prefix = "--gateway.ratelimit.rules[" + rules++ + "].";
        ruleArgs.addAll(List.of(prefix + "id=100", prefix + "name=strict-ip", prefix + "limit-by=IP",
                prefix + "endpoint-pattern=/**", prefix + "algorithm=TOKEN_BUCKET",
                prefix + "failure-mode=FAIL_CLOSED", prefix + "params.capacity=100", prefix + "params.refill-per-sec=1"));
        var gateway = startGateway(NEVER_THERE);
        int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();

        HttpResponse<String> anonymous;
        try {
            anonymous = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/images/a")).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new AssertionError(e);
        }

        assertThat(anonymous.statusCode()).as("not 401: it never got that far").isEqualTo(503);
        assertThat(header(anonymous, "X-RateLimit-Policy")).isEqualTo("strict-ip");
    }
}
