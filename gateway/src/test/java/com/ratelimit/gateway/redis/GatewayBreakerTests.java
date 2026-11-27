package com.ratelimit.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ratelimit.gateway.GatewayApplication;
import com.ratelimit.gateway.proxy.StubUpstream;
import com.ratelimit.rules.ApiKeyHash;
import io.lettuce.core.RedisClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The circuit breaker in a running gateway. Every request here makes two limit checks, one for its IP and
 * one for its user; the command timeout is set long so that waiting for it is easy to see.
 */
@Testcontainers
class GatewayBreakerTests {

    private static final String API_KEY = "breaker-test-key";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(200);

    static StubUpstream upstream;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<ConfigurableApplicationContext> gateways = new ArrayList<>();

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

    private ConfigurableApplicationContext startGateway(String url, String mode, String openFor) {
        String rule = "--gateway.ratelimit.rules[0].";
        ConfigurableApplicationContext gateway = new SpringApplicationBuilder(GatewayApplication.class).run(
                "--server.port=0",
                "--gateway.redis.url=" + url,
                "--gateway.redis.command-timeout=" + COMMAND_TIMEOUT.toMillis() + "ms",
                "--gateway.redis.breaker.open-for=" + openFor,
                "--gateway.auth.api-keys[0].key-hash=" + ApiKeyHash.of(API_KEY),
                "--gateway.auth.api-keys[0].user-id=alice",
                "--gateway.auth.api-keys[0].tier=free",
                rule + "id=10", rule + "name=images", rule + "limit-by=USER",
                rule + "endpoint-pattern=/api/images/**", rule + "algorithm=TOKEN_BUCKET", rule + "mode=" + mode,
                rule + "params.capacity=1000", rule + "params.refill-per-sec=1",
                "--gateway.proxy.routes[0].pattern=/api/images/**",
                "--gateway.proxy.routes[0].service=images",
                "--gateway.proxy.routes[0].instances[0]=" + upstream.url());
        gateways.add(gateway);
        return gateway;
    }

    private HttpResponse<Void> call(ConfigurableApplicationContext gateway) {
        int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/images/a"))
                .header("X-API-Key", API_KEY).timeout(Duration.ofSeconds(5)).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static long header(HttpResponse<?> response, String name) {
        return response.headers().firstValueAsLong(name).orElse(-1);
    }

    /** Whether Redis decided the request: one let through unchecked reports the whole limit as remaining. */
    private static boolean checked(HttpResponse<?> response) {
        return header(response, "X-RateLimit-Remaining") < header(response, "X-RateLimit-Limit");
    }

    @Test
    void aFrozenRedisIsSoonNoLongerWaitedForAndIsUsedAgainOnceItAnswers() {
        try (var redis = new LateRedis()) {
            redis.start();
            var gateway = startGateway(redis.url(), "STRICT", "1s");
            RedisBreaker breaker = gateway.getBean(RedisBreaker.class);
            assertThat(checked(call(gateway))).isTrue();

            redis.freeze();
            int sent = 0;
            while (breaker.isClosed() && sent < 20) {
                assertThat(call(gateway).statusCode()).isEqualTo(200); // handled as before: let through
                sent++;
            }
            assertThat(breaker.isClosed()).as("open after %d requests", sent).isFalse();

            long start = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                assertThat(call(gateway).statusCode()).isEqualTo(200);
            }
            // waiting for both of each request's checks would take 10 x 2 x 200 ms = 4 s
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));

            redis.unfreeze();

            // no traffic at all: the heartbeat's calls are the trial calls
            await().atMost(Duration.ofSeconds(10)).until(breaker::isClosed);
            assertThat(checked(call(gateway))).isTrue();
        }
    }

    /**
     * With the design's 5 s wait. Redis is usable again at the first trial calls after the wait: at worst
     * a wait that has just begun when Redis returns, plus a heartbeat.
     */
    @Test
    void withTheDefaultWaitLimitsAreCheckedAgainWithinEightSecondsOfRedisReturning() throws InterruptedException {
        try (var redis = new LateRedis()) {
            redis.start();
            var gateway = startGateway(redis.url(), "STRICT", "5s");
            RedisBreaker breaker = gateway.getBean(RedisBreaker.class);
            assertThat(checked(call(gateway))).isTrue();

            redis.stopServer();
            for (int i = 0; i < 40; i++) {
                assertThat(call(gateway).statusCode()).isEqualTo(200);
                Thread.sleep(100);
            }
            assertThat(breaker.isClosed()).isFalse();

            long start = System.nanoTime();
            redis.startServerAgain();

            await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).until(() -> checked(call(gateway)));
            System.out.printf("checked again %d ms after starting Redis%n",
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
        }
    }

    @Test
    void whileTheBreakerIsOpenNothingIsSentToRedisNotEvenBorrowingOrHeartbeats() throws InterruptedException {
        try (var redis = new LateRedis()) {
            redis.start();
            var gateway = startGateway(redis.url(), "LOCAL_SYNC", "1m");
            RedisBreaker breaker = gateway.getBean(RedisBreaker.class);
            assertThat(call(gateway).statusCode()).isEqualTo(200);
            for (int i = 0; i < 40 && breaker.isClosed(); i++) { // Redis is fine; these failures are made up
                try {
                    breaker.call(() -> {
                        throw new IllegalStateException("made up");
                    });
                } catch (IllegalStateException expected) {
                    // counted by the breaker
                }
            }
            assertThat(breaker.isClosed()).isFalse();
            Thread.sleep(500); // anything already on its way arrives

            RedisClient client = RedisClient.create(redis.url());
            try (var direct = client.connect()) {
                long before = scriptCalls(direct.sync().info("commandstats"));

                for (int i = 0; i < 20; i++) {
                    assertThat(call(gateway).statusCode()).isEqualTo(200);
                }
                Thread.sleep(2_500); // two heartbeats would have been due

                assertThat(scriptCalls(direct.sync().info("commandstats"))).isEqualTo(before);
                assertThat(before).as("there were calls before").isPositive();
            } finally {
                client.shutdown();
            }
        }
    }

    private static long scriptCalls(String commandStats) {
        Matcher m = Pattern.compile("cmdstat_evalsha:calls=(\\d+)").matcher(commandStats);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }
}
