package com.ratelimit.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ratelimit.rules.snapshot.SnapshotCodec;
import com.ratelimit.rules.snapshot.SnapshotKeys;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Publishing triggered by changes alone. The periodic reconcile is set to an hour (it runs once at
 * startup), so anything that reaches Redis afterwards got there because a change asked for it.
 */
@Testcontainers
@SpringBootTest(properties = {
        "policy.admin-token=" + PublishAfterChangeTests.TOKEN,
        "policy.publish.reconcile-interval=1h"})
@AutoConfigureMockMvc
@Import(PostgresTestSupport.class)
class PublishAfterChangeTests {

    static final String TOKEN = "after-change-test-token-0123456789";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedisClient redisClient;
    static StatefulRedisConnection<String, String> redis;

    @DynamicPropertySource
    static void redisUrl(DynamicPropertyRegistry registry) {
        registry.add("policy.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @BeforeAll
    static void connect() {
        redisClient = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redis = redisClient.connect();
    }

    @AfterAll
    static void disconnect() {
        redis.close();
        redisClient.shutdown();
    }

    @Autowired MockMvc mvc;

    private int create(String name) throws Exception {
        return mvc.perform(post("/admin/rules").header("X-Admin-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "limitBy": "USER", "endpointPattern": "/%s/**", "algorithm": "TOKEN_BUCKET",
                                 "params": {"capacity": 5, "refill_per_sec": 1}}""".formatted(name, name)))
                .andReturn().getResponse().getStatus();
    }

    private static List<String> publishedRuleNames() {
        String text = redis.sync().hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT);
        return text == null ? List.of() : SnapshotCodec.decode(text).rules().stream().map(r -> r.name()).toList();
    }

    @Test
    void aSingleChangeReachesRedisWithinTwoSeconds() throws Exception {
        assertThat(create("single")).isEqualTo(201);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(publishedRuleNames()).contains("single"));
    }

    /**
     * More simultaneous changes than the database pool has connections (10). Publishing on the request
     * thread would deadlock here: each request would keep its connection while waiting for another.
     * The last change must also be delivered, even though many publish requests collapse into few runs.
     */
    @Test
    void manyMoreSimultaneousChangesThanDatabaseConnectionsAllSucceedAndTheLastIsPublished() throws Exception {
        int changes = 40;
        CountDownLatch go = new CountDownLatch(1);
        long started = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < changes; i++) {
                String name = "burst" + i;
                futures.add(executor.submit(() -> {
                    go.await();
                    return create(name);
                }));
            }
            go.countDown();
            for (Future<Integer> f : futures) {
                assertThat(f.get()).isEqualTo(201);
            }
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20)); // nowhere near a pool timeout

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(publishedRuleNames()).filteredOn(n -> n.startsWith("burst")).hasSize(changes));
    }
}
