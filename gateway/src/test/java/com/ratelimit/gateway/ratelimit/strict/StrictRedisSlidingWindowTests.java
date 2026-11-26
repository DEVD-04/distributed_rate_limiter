package com.ratelimit.gateway.ratelimit.strict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.Reason;
import com.ratelimit.gateway.redis.RedisKeys;
import com.ratelimit.rules.Rule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Lua sliding window log against a real Redis. Time lives inside Redis, so these tests use short
 * real waits and leave generous margins around them.
 */
@Testcontainers
class StrictRedisSlidingWindowTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;
    static StrictRedisLimiter limiter;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
        limiter = new StrictRedisLimiter(connection);
    }

    @AfterAll
    static void disconnect() {
        connection.close();
        client.shutdown();
    }

    private static String subject() {
        return "s-" + UUID.randomUUID();
    }

    private static boolean allowed(Rule rule, String who) {
        return limiter.acquire(rule, who, 1).allowed();
    }

    // ---- basics ----

    @Test
    void exactlyLimitRequestsPassPerWindowAndRemainingCountsDown() {
        Rule rule = TestRules.slidingWindow(1, 5, 60_000);
        String who = subject();

        for (int expected = 4; expected >= 0; expected--) {
            Decision d = limiter.acquire(rule, who, 1);
            assertThat(d.allowed()).isTrue();
            assertThat(d.reason()).isEqualTo(Reason.ALLOWED);
            assertThat(d.limit()).isEqualTo(5);
            assertThat(d.remaining()).isEqualTo(expected);
        }
        Decision rejected = limiter.acquire(rule, who, 1);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.reason()).isEqualTo(Reason.LIMIT_EXCEEDED);
        assertThat(rejected.remaining()).isZero();
    }

    @Test
    void manyRequestsInTheSameMillisecondAreAllCounted() {
        Rule rule = TestRules.slidingWindow(1, 50, 60_000);
        String who = subject();

        int admitted = 0;
        for (int i = 0; i < 80; i++) {
            if (allowed(rule, who)) {
                admitted++;
            }
        }

        assertThat(admitted).isEqualTo(50); // entries with the same timestamp must not collapse into one
    }

    @Test
    void rejectedRequestsAreNotRecordedSoTheLogStaysAtTheLimit() {
        Rule rule = TestRules.slidingWindow(1, 5, 60_000);
        String who = subject();
        for (int i = 0; i < 100; i++) {
            limiter.acquire(rule, who, 1);
        }

        assertThat(connection.sync().zcard(RedisKeys.of(1, who, "sw"))).isEqualTo(5);
    }

    // ---- the window slides ----

    @Test
    void retryAfterIsWhenTheOldestRequestLeavesTheWindowAndThenARequestSucceeds() throws Exception {
        Rule rule = TestRules.slidingWindow(1, 3, 1_000);
        String who = subject();
        for (int i = 0; i < 3; i++) {
            limiter.acquire(rule, who, 1);
        }

        Decision rejected = limiter.acquire(rule, who, 1);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterMs()).isBetween(1L, 1_000L);
        assertThat(rejected.retryAfterMs()).isGreaterThan(800L); // sent only moments ago

        Thread.sleep(rejected.retryAfterMs() + 30);
        assertThat(allowed(rule, who)).isTrue();
    }

    @Test
    void theWindowRollsInsteadOfResettingAllAtOnce() throws Exception {
        Rule rule = TestRules.slidingWindow(1, 3, 1_000);
        String who = subject();
        assertThat(allowed(rule, who)).isTrue();   // t = 0: one request
        Thread.sleep(600);
        assertThat(allowed(rule, who)).isTrue();   // t = 600: two more
        assertThat(allowed(rule, who)).isTrue();
        assertThat(allowed(rule, who)).isFalse();  // three in the window: full

        Thread.sleep(550);                         // t = 1150: the first request has left, the other two have not

        assertThat(allowed(rule, who)).isTrue();   // exactly one slot has opened
        assertThat(allowed(rule, who)).isFalse();  // a fixed window that reset would admit more
    }

    @Test
    void aFullWindowOfSilenceRestoresTheWholeLimit() throws Exception {
        Rule rule = TestRules.slidingWindow(1, 4, 400);
        String who = subject();
        for (int i = 0; i < 4; i++) {
            limiter.acquire(rule, who, 1);
        }
        assertThat(allowed(rule, who)).isFalse();

        Thread.sleep(500);

        int admitted = 0;
        for (int i = 0; i < 6; i++) {
            if (allowed(rule, who)) {
                admitted++;
            }
        }
        assertThat(admitted).isEqualTo(4);
    }

    @Test
    void resetIsWhenTheNewestRequestLeavesTheWindow() {
        Rule rule = TestRules.slidingWindow(1, 5, 10_000);
        Decision d = limiter.acquire(rule, subject(), 1);

        assertThat(d.resetMs()).isBetween(9_000L, 10_000L);
    }

    // ---- isolation, storage, failure ----

    @Test
    void subjectsAndRulesHaveSeparateWindows() {
        Rule a = TestRules.slidingWindow(1, 1, 60_000);
        Rule b = TestRules.slidingWindow(2, 1, 60_000);
        String alice = subject();
        String bob = subject();

        assertThat(allowed(a, alice)).isTrue();
        assertThat(allowed(a, alice)).isFalse();
        assertThat(allowed(a, bob)).isTrue();
        assertThat(allowed(b, alice)).isTrue();
    }

    @Test
    void theKeyExpiresWithTheWindow() throws Exception {
        Rule rule = TestRules.slidingWindow(3, 5, 800);
        String who = subject();
        limiter.acquire(rule, who, 1);
        String key = RedisKeys.of(3, who, "sw");

        assertThat(connection.sync().pttl(key)).isBetween(1L, 800L);
        Thread.sleep(1_200);
        assertThat(connection.sync().exists(key)).isZero();
    }

    @Test
    void aSlidingWindowCountsRequestsSoOnlyACostOfOneIsAccepted() {
        Rule rule = TestRules.slidingWindow(1, 5, 1_000);
        assertThatThrownBy(() -> limiter.acquire(rule, subject(), 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whenRedisCannotBeReachedTheRequestIsLetThrough() {
        StatefulRedisConnection<String, String> broken = client.connect();
        broken.close();

        Decision d = new StrictRedisLimiter(broken).acquire(TestRules.slidingWindow(1, 5, 1_000), subject(), 1);

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo(Reason.LIMITER_UNAVAILABLE);
        assertThat(d.limit()).isEqualTo(5);
    }
}
