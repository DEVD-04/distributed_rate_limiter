package com.ratelimit.gateway.ratelimit.strict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.Reason;
import com.ratelimit.gateway.ratelimit.local.InMemoryRateLimiter;
import com.ratelimit.gateway.redis.RedisKeys;
import com.ratelimit.gateway.rules.Rule;
import com.ratelimit.gateway.rules.TokenBucketParams;
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
 * The Lua token bucket against a real Redis. Time lives inside Redis, so these tests cannot use a
 * fake clock: they use short real waits and assert bounds rather than exact instants.
 */
@Testcontainers
class StrictRedisLimiterTests {

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

    /** A subject nobody else uses, so tests never share a bucket. */
    private static String subject() {
        return "s-" + UUID.randomUUID();
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    // ---- basics ----

    @Test
    void newBucketStartsFullAndRemainingCountsDown() {
        Rule rule = TestRules.tokenBucket(1, 5, 0.001);
        String who = subject();

        for (int expected = 4; expected >= 0; expected--) {
            Decision d = limiter.acquire(rule, who, 1);
            assertThat(d.allowed()).isTrue();
            assertThat(d.reason()).isEqualTo(Reason.ALLOWED);
            assertThat(d.limit()).isEqualTo(5);
            assertThat(d.remaining()).isEqualTo(expected);
            assertThat(d.retryAfterMs()).isZero();
        }
    }

    @Test
    void exactlyCapacityRequestsPassThenTheNextIsRejected() {
        Rule rule = TestRules.tokenBucket(1, 10, 0.001);
        String who = subject();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.acquire(rule, who, 1).allowed()).as("request %d", i + 1).isTrue();
        }

        Decision rejected = limiter.acquire(rule, who, 1);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.reason()).isEqualTo(Reason.LIMIT_EXCEEDED);
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.limit()).isEqualTo(10);
    }

    @Test
    void retryAfterAndResetDescribeTheRefillSchedule() {
        Rule rule = TestRules.tokenBucket(1, 10, 2); // one token every 500 ms; 5 s to fill from empty
        String who = subject();
        for (int i = 0; i < 10; i++) {
            limiter.acquire(rule, who, 1);
        }

        Decision rejected = limiter.acquire(rule, who, 1);

        assertThat(rejected.retryAfterMs()).isBetween(400L, 500L); // a little refill may already have happened
        assertThat(rejected.resetMs()).isBetween(4900L, 5000L);
    }

    @Test
    void theBucketRefillsAsRedisTimePasses() throws Exception {
        Rule rule = TestRules.tokenBucket(1, 5, 20); // one token per 50 ms
        String who = subject();
        for (int i = 0; i < 5; i++) {
            limiter.acquire(rule, who, 1);
        }
        assertThat(limiter.acquire(rule, who, 1).allowed()).isFalse();

        sleep(220); // about 4 tokens' worth (and certainly at least 2, at most 5)
        int admitted = 0;
        while (limiter.acquire(rule, who, 1).allowed() && admitted < 10) {
            admitted++;
        }

        assertThat(admitted).isBetween(2, 5);
    }

    @Test
    void refillNeverExceedsCapacity() throws Exception {
        Rule rule = TestRules.tokenBucket(1, 3, 50); // full again after 60 ms
        String who = subject();
        for (int i = 0; i < 3; i++) {
            limiter.acquire(rule, who, 1);
        }

        sleep(300); // five times longer than needed to fill
        int admitted = 0;
        while (limiter.acquire(rule, who, 1).allowed() && admitted < 10) {
            admitted++;
        }

        assertThat(admitted).isEqualTo(3);
    }

    // ---- isolation and cost ----

    @Test
    void subjectsAndRulesHaveSeparateBuckets() {
        Rule images = TestRules.tokenBucket(1, 1, 0.001);
        Rule text = TestRules.tokenBucket(2, 1, 0.001);
        String alice = subject();
        String bob = subject();

        assertThat(limiter.acquire(images, alice, 1).allowed()).isTrue();
        assertThat(limiter.acquire(images, alice, 1).allowed()).isFalse();
        assertThat(limiter.acquire(images, bob, 1).allowed()).isTrue();
        assertThat(limiter.acquire(text, alice, 1).allowed()).isTrue();
    }

    @Test
    void costsAreChargedAndAnImpossibleCostIsAnError() {
        Rule perRequest2 = TestRules.tokenBucket(1, 10, 0.001, 2);
        String who = subject();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.acquire(perRequest2, who, 1).allowed()).isTrue();
        }
        assertThat(limiter.acquire(perRequest2, who, 1).allowed()).isFalse();

        Rule plain = TestRules.tokenBucket(2, 10, 0.001);
        String other = subject();
        assertThat(limiter.acquire(plain, other, 3).remaining()).isEqualTo(7);
        assertThatThrownBy(() -> limiter.acquire(plain, other, 11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.acquire(plain, other, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void liveRuleChangesApplyToTheExistingBucket() {
        String who = subject();
        limiter.acquire(TestRules.tokenBucket(1, 10, 0.001), who, 1); // 9 left

        Rule smaller = TestRules.tokenBucket(1, 3, 0.001);              // capacity shrinks
        assertThat(limiter.acquire(smaller, who, 1).remaining()).isEqualTo(2);
    }

    // ---- what is stored in Redis ----

    @Test
    void everyKeyHasAnExpiryAndTheDocumentedName() {
        Rule rule = TestRules.tokenBucket(7, 10, 2);
        String who = subject();
        limiter.acquire(rule, who, 1);

        String key = RedisKeys.of(7, who, "tb");
        long ttl = connection.sync().pttl(key);

        assertThat(key).startsWith("rl:{7:");
        assertThat(connection.sync().hkeys(key)).containsExactlyInAnyOrder("tokens", "ts");
        // idle lifetime = twice the 5 s full-refill time + 1 s
        assertThat(ttl).isBetween(10_000L, 11_000L);
    }

    @Test
    void idleKeysDisappearByThemselves() throws Exception {
        Rule rule = TestRules.tokenBucket(8, 1, 1000); // refills in 1 ms, so the key lives 1002 ms
        String who = subject();
        limiter.acquire(rule, who, 1);
        String key = RedisKeys.of(8, who, "tb");
        assertThat(connection.sync().exists(key)).isEqualTo(1);

        sleep(1500);

        assertThat(connection.sync().exists(key)).isZero();
    }

    @Test
    void keyLifetimeIsBoundedForHugeCapacitiesAndTinyRates() {
        assertThat(StrictRedisLimiter.keyTtlMillis(new TokenBucketParams(10, 2))).isEqualTo(11_000);
        assertThat(StrictRedisLimiter.keyTtlMillis(new TokenBucketParams(1_000_000_000L, 0.000001)))
                .isEqualTo(30L * 24 * 3600 * 1000);
    }

    // ---- agreement with the in-memory limiter ----

    @Test
    void decisionsMatchTheInMemoryLimiterForTheSameRule() {
        Rule rule = TestRules.tokenBucket(9, 10, 0.001, 2); // nothing refills during the test
        InMemoryRateLimiter local = new InMemoryRateLimiter(1_000);
        String who = subject();

        for (int i = 0; i < 8; i++) {
            Decision redis = limiter.acquire(rule, who, 1);
            Decision memory = local.acquire(rule, who, 1);
            assertThat(redis.allowed()).as("request %d", i + 1).isEqualTo(memory.allowed());
            assertThat(redis.remaining()).as("request %d", i + 1).isEqualTo(memory.remaining());
            assertThat(redis.limit()).isEqualTo(memory.limit());
        }
    }

    // ---- failure handling ----

    @Test
    void theScriptIsReloadedIfRedisForgetsIt() {
        Rule rule = TestRules.tokenBucket(1, 5, 0.001);
        String who = subject();
        limiter.acquire(rule, who, 1);

        connection.sync().scriptFlush();

        Decision d = limiter.acquire(rule, who, 1);
        assertThat(d.allowed()).isTrue();
        assertThat(d.remaining()).isEqualTo(3);
    }

    @Test
    void whenRedisCannotBeReachedTheRequestIsLetThrough() {
        StatefulRedisConnection<String, String> broken = client.connect();
        broken.close();
        StrictRedisLimiter unreachable = new StrictRedisLimiter(broken);

        Decision d = unreachable.acquire(TestRules.tokenBucket(1, 5, 1), subject(), 1);

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo(Reason.LIMITER_UNAVAILABLE);
        assertThat(d.limit()).isEqualTo(5);
    }
}
