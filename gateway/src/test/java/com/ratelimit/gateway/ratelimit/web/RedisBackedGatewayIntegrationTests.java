package com.ratelimit.gateway.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ratelimit.gateway.auth.TokenFactory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole gateway (filters, rules, router) with a real Redis behind it: one rule per algorithm, each
 * proven by what it does to the response and by what it leaves in Redis.
 */
@Testcontainers
@SpringBootTest(properties = {
        // rule 1: STRICT token bucket, 3 tokens
        "gateway.ratelimit.rules[0].id=1",
        "gateway.ratelimit.rules[0].name=tb-strict",
        "gateway.ratelimit.rules[0].limit-by=USER",
        "gateway.ratelimit.rules[0].endpoint-pattern=/test/user/**",
        "gateway.ratelimit.rules[0].algorithm=TOKEN_BUCKET",
        "gateway.ratelimit.rules[0].params.capacity=3",
        "gateway.ratelimit.rules[0].params.refill-per-sec=0.01",
        // rule 2: the same limit but LOCAL_SYNC
        "gateway.ratelimit.rules[1].id=2",
        "gateway.ratelimit.rules[1].name=tb-local",
        "gateway.ratelimit.rules[1].limit-by=USER",
        "gateway.ratelimit.rules[1].endpoint-pattern=/test/local/**",
        "gateway.ratelimit.rules[1].algorithm=TOKEN_BUCKET",
        "gateway.ratelimit.rules[1].mode=LOCAL_SYNC",
        "gateway.ratelimit.rules[1].params.capacity=3",
        "gateway.ratelimit.rules[1].params.refill-per-sec=0.01",
        // rule 3: sliding window, 2 per minute
        "gateway.ratelimit.rules[2].id=3",
        "gateway.ratelimit.rules[2].name=window",
        "gateway.ratelimit.rules[2].limit-by=USER",
        "gateway.ratelimit.rules[2].endpoint-pattern=/test/window/**",
        "gateway.ratelimit.rules[2].algorithm=SLIDING_WINDOW_LOG",
        "gateway.ratelimit.rules[2].params.limit=2",
        "gateway.ratelimit.rules[2].params.window-ms=60000",
        // rule 4: leaky bucket, 5 per second, 5 may queue
        "gateway.ratelimit.rules[3].id=4",
        "gateway.ratelimit.rules[3].name=leaky",
        "gateway.ratelimit.rules[3].limit-by=USER",
        "gateway.ratelimit.rules[3].endpoint-pattern=/test/leaky/**",
        "gateway.ratelimit.rules[3].algorithm=LEAKY_BUCKET_QUEUE",
        "gateway.ratelimit.rules[3].params.drain-per-sec=5",
        "gateway.ratelimit.rules[3].params.queue-capacity=5",
})
@AutoConfigureMockMvc
class RedisBackedGatewayIntegrationTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final KeyPair KEYS = TokenFactory.newRsaKeyPair();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    static RedisClient inspector;
    static StatefulRedisConnection<String, String> redis;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("gateway.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        // these tests are about limiting: on a busy machine a call over the 15 ms default would be decided
        // by the failure_mode instead, from a local bucket that starts empty
        registry.add("gateway.redis.command-timeout", () -> "500ms");
        registry.add("gateway.auth.jwt.issuer", () -> TokenFactory.ISSUER);
        registry.add("gateway.auth.jwt.audience", () -> TokenFactory.AUDIENCE);
        registry.add("gateway.auth.jwt.public-keys.k1", () -> TokenFactory.publicPem(KEYS));
    }

    @BeforeAll
    static void connectInspector() {
        inspector = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redis = inspector.connect();
    }

    @AfterAll
    static void disconnectInspector() {
        redis.close();
        inspector.shutdown();
    }

    @Autowired
    MockMvc mvc;

    private static String uniqueUser() {
        return "user-" + SEQUENCE.incrementAndGet();
    }

    private static String bearer(String user) {
        return "Bearer " + new TokenFactory(Instant.now()).subject(user).tier("free").signRs256(KEYS.getPrivate());
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String uniqueIp() {
        return "198.51.100." + (SEQUENCE.incrementAndGet() % 250 + 1);
    }

    private MvcResult call(String path, String user, String ip) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer(user)).with(fromIp(ip))).andReturn();
    }

    // ---- STRICT token bucket lives in Redis ----

    @Test
    void aStrictRuleIsEnforcedAndItsStateIsInRedis() throws Exception {
        String user = uniqueUser();
        String ip = uniqueIp();
        for (int remaining = 2; remaining >= 0; remaining--) {
            mvc.perform(get("/test/user/a").header("Authorization", bearer(user)).with(fromIp(ip)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Policy", "tb-strict"))
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(remaining)));
        }
        mvc.perform(get("/test/user/a").header("Authorization", bearer(user)).with(fromIp(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.policy").value("tb-strict"));

        assertThat(redis.sync().exists("rl:{1:" + user + "}:tb")).isEqualTo(1);
    }

    @Test
    void theIpStageUsesRedisToo() throws Exception {
        String ip = uniqueIp();
        call("/test/user/a", uniqueUser(), ip);

        // default-ip is the built-in rule with id -1, STRICT, so its bucket is in Redis
        assertThat(redis.sync().exists("rl:{-1:" + ip + "}:tb")).isEqualTo(1);
    }

    // ---- LOCAL_SYNC borrows from the same kind of bucket ----

    @Test
    void aLocalSyncRuleIsEnforcedByBorrowingFromRedis() throws Exception {
        String user = uniqueUser();
        String ip = uniqueIp();
        for (int i = 0; i < 3; i++) {
            assertThat(call("/test/local/a", user, ip).getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult rejected = call("/test/local/a", user, ip);

        assertThat(rejected.getResponse().getStatus()).isEqualTo(429);
        assertThat(rejected.getResponse().getContentAsString()).contains("\"policy\":\"tb-local\"");
        // the shared bucket: all three tokens were lent out
        assertThat(Double.parseDouble(redis.sync().hget("rl:{2:" + user + "}:tb", "tokens"))).isLessThan(1);
    }

    // ---- sliding window ----

    @Test
    void aSlidingWindowRuleLimitsPerWindowAndKeepsTheLogInRedis() throws Exception {
        String user = uniqueUser();
        String ip = uniqueIp();
        assertThat(call("/test/window/a", user, ip).getResponse().getStatus()).isEqualTo(200);
        assertThat(call("/test/window/a", user, ip).getResponse().getStatus()).isEqualTo(200);

        MvcResult rejected = call("/test/window/a", user, ip);

        assertThat(rejected.getResponse().getStatus()).isEqualTo(429);
        assertThat(rejected.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("2");
        assertThat(Long.parseLong(rejected.getResponse().getHeader("Retry-After"))).isBetween(58L, 60L);
        assertThat(redis.sync().zcard("rl:{3:" + user + "}:sw")).isEqualTo(2);
    }

    // ---- leaky bucket ----

    @Test
    void aLeakyBucketRuleHoldsRequestsBackAtTheDrainRate() throws Exception {
        String user = uniqueUser();
        String ip = uniqueIp();
        long start = System.nanoTime();

        for (int i = 0; i < 4; i++) {
            MvcResult r = call("/test/leaky/a", user, ip);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getHeader("X-RateLimit-Policy")).isEqualTo("leaky");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 4 requests at 5 per second: the first is immediate, the other three follow 200 ms apart
        assertThat(elapsedMs).isBetween(500L, 1_500L);
    }

    @Test
    void aBurstBeyondTheQueueGetsTooManyRequestsWithTheLeakyPolicy() throws Exception {
        String user = uniqueUser();
        String ip = uniqueIp();
        CountDownLatch go = new CountDownLatch(1);
        List<MvcResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(executor.submit(() -> {
                    go.await();
                    return call("/test/leaky/burst", user, ip);
                }));
            }
            go.countDown();
            for (Future<MvcResult> f : futures) {
                results.add(f.get());
            }
        }

        long admitted = results.stream().filter(r -> r.getResponse().getStatus() == 200).count();
        List<MvcResult> refused = results.stream().filter(r -> r.getResponse().getStatus() == 429).toList();

        assertThat(admitted).isBetween(6L, 8L);          // 1 immediate + 5 queued, and a tick or two of drain
        assertThat(refused).hasSize(16 - (int) admitted);
        for (MvcResult r : refused) {
            assertThat(r.getResponse().getContentAsString()).contains("\"policy\":\"leaky\"");
            assertThat(Long.parseLong(r.getResponse().getHeader("Retry-After"))).isGreaterThanOrEqualTo(1);
        }
        assertThat(CountingController.hits("/test/leaky/burst")).isEqualTo((int) admitted); // refused ones never reached the handler
    }
}
