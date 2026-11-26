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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Lua leaky bucket (virtual scheduling) against a real Redis. These tests really wait, because
 * the gateway really sleeps.
 */
@Testcontainers
class StrictRedisLeakyBucketTests {

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

    // ---- one request at a time ----

    @Test
    void anIdleBucketLetsTheFirstRequestStraightThrough() {
        long start = System.nanoTime();
        Decision d = limiter.acquire(TestRules.leakyBucket(1, 10, 20), subject(), 1);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(d.allowed()).isTrue();
        assertThat(d.waitedMs()).isZero();
        assertThat(d.reason()).isEqualTo(Reason.ALLOWED);
        assertThat(d.limit()).isEqualTo(20);
        assertThat(d.remaining()).isEqualTo(20);
        assertThat(elapsedMs).isLessThan(90); // nothing was held back (a 10/s rule would hold the next one 100 ms)
    }

    @Test
    void backToBackRequestsAreReleasedAtTheDrainRate() {
        Rule rule = TestRules.leakyBucket(1, 20, 10); // one every 50 ms
        String who = subject();
        List<Long> releasedAtMs = new ArrayList<>();
        long start = System.nanoTime();

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.acquire(rule, who, 1).allowed()).isTrue();
            releasedAtMs.add((System.nanoTime() - start) / 1_000_000);
        }

        // 6 releases take 5 intervals = 250 ms, each about 50 ms after the one before
        assertThat(releasedAtMs.get(5)).isBetween(230L, 400L);
        for (int i = 1; i < releasedAtMs.size(); i++) {
            assertThat(releasedAtMs.get(i) - releasedAtMs.get(i - 1)).isBetween(30L, 120L);
        }
    }

    @Test
    void aWaitingRequestReportsHowLongItWaitedAndHowManyAreAheadOfIt() {
        Rule rule = TestRules.leakyBucket(1, 10, 10); // 100 ms apart, 10 deep
        String who = subject();
        limiter.acquire(rule, who, 1);

        Decision second = limiter.acquire(rule, who, 1);

        assertThat(second.waitedMs()).isBetween(50L, 100L); // reserved just after the first
        assertThat(second.remaining()).isBetween(9L, 10L);   // one request ahead (or none, if it has just been released)
    }

    @Test
    void anIdleGapEmptiesTheQueueSoTheNextRequestIsImmediateAgain() throws Exception {
        Rule rule = TestRules.leakyBucket(1, 100, 3); // 10 ms apart
        String who = subject();
        limiter.acquire(rule, who, 1);
        Thread.sleep(150);

        assertThat(limiter.acquire(rule, who, 1).waitedMs()).isZero();
    }

    // ---- bursts ----

    @Test
    void aBurstOfOneHundredIsReleasedSmoothlyAndTheOverflowIsRefused() throws Exception {
        Rule rule = TestRules.leakyBucket(1, 10, 20); // 10 per second, 20 may queue: longest wait 2 s
        String who = subject();
        List<Decision> decisions = burst(rule, who, 100);

        List<Decision> admitted = decisions.stream().filter(Decision::allowed).toList();
        List<Decision> refused = decisions.stream().filter(d -> !d.allowed()).toList();

        // One goes straight through, 20 may queue behind it; a few more fit if the burst takes a few ms.
        assertThat(admitted.size()).isBetween(21, 24);
        assertThat(refused.size()).isEqualTo(100 - admitted.size());
        assertThat(refused).allSatisfy(d -> {
            assertThat(d.reason()).isEqualTo(Reason.QUEUE_FULL);
            assertThat(d.remaining()).isZero();
            assertThat(d.retryAfterMs()).isPositive();
            assertThat(d.waitedMs()).isZero();
        });
        // Nobody was held longer than the queue allows.
        assertThat(admitted).allSatisfy(d -> assertThat(d.waitedMs()).isLessThanOrEqualTo(2_000L));
        // Waits are spread 100 ms apart: no two admitted requests share a slot.
        List<Long> waits = admitted.stream().map(Decision::waitedMs).sorted().toList();
        for (int i = 1; i < waits.size(); i++) {
            assertThat(waits.get(i) - waits.get(i - 1)).isBetween(90L, 110L);
        }
    }

    @Test
    void theOverflowIsToldWhenToComeBack() throws Exception {
        Rule rule = TestRules.leakyBucket(1, 10, 5);
        String who = subject();
        List<Decision> decisions = burst(rule, who, 30);

        Decision refused = decisions.stream().filter(d -> !d.allowed()).findFirst().orElseThrow();

        // The queue is 0.5 s deep at most; a refusal is told to wait until it has drained back to that.
        assertThat(refused.retryAfterMs()).isBetween(1L, 600L);
    }

    /** Fires {@code count} requests at the same instant, each on its own virtual thread, and collects the decisions. */
    private static List<Decision> burst(Rule rule, String who, int count) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Decision>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    go.await();
                    return limiter.acquire(rule, who, 1);
                }));
            }
            go.countDown();
            List<Decision> decisions = new ArrayList<>();
            for (Future<Decision> f : futures) {
                decisions.add(f.get());
            }
            return decisions;
        }
    }

    // ---- limits on this node ----

    @Test
    void aNodeHoldingTooManyWaitersTurnsFurtherRequestsAwayAtOnce() throws Exception {
        StrictRedisLimiter small = new StrictRedisLimiter(connection, 1);
        Rule rule = TestRules.leakyBucket(1, 1, 10); // one per second
        String who = subject();
        small.acquire(rule, who, 1); // immediate, and done

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Decision> holdsTheOnlyPermit = executor.submit(() -> small.acquire(rule, who, 1)); // waits about 1 s
            Thread.sleep(300);

            long start = System.nanoTime();
            Decision turnedAway = small.acquire(rule, who, 1);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(turnedAway.allowed()).isFalse();
            assertThat(turnedAway.reason()).isEqualTo(Reason.QUEUE_FULL);
            assertThat(elapsedMs).isLessThan(100); // refused without waiting
            assertThat(holdsTheOnlyPermit.get().allowed()).isTrue();
        }
        // The permit comes back once the waiter is released.
        assertThat(small.acquire(rule, who, 1).allowed()).isTrue();
    }

    // ---- storage, validation, failure ----

    @Test
    void theKeyHasAnExpiryAndSurvivesOnlyAsLongAsTheQueueCouldBeBusy() throws Exception {
        Rule rule = TestRules.leakyBucket(5, 100, 10); // 10 ms apart, max wait 100 ms: key lives about 1.1 s
        String who = subject();
        limiter.acquire(rule, who, 1);
        String key = RedisKeys.of(5, who, "lb");

        assertThat(connection.sync().pttl(key)).isBetween(1L, 1_200L);
        Thread.sleep(1_500);
        assertThat(connection.sync().exists(key)).isZero();
    }

    @Test
    void aRefusedRequestReservesNothing() throws Exception {
        Rule rule = TestRules.leakyBucket(1, 10, 2); // longest wait 200 ms
        String who = subject();
        burst(rule, who, 20); // fills the queue; the rest are refused

        Thread.sleep(350);    // the queue drains completely; refusals did not push the schedule out

        Decision d = limiter.acquire(rule, who, 1);
        assertThat(d.allowed()).isTrue();
        assertThat(d.waitedMs()).isZero();
    }

    @Test
    void onlyACostOfOneIsAccepted() {
        assertThatThrownBy(() -> limiter.acquire(TestRules.leakyBucket(1, 10, 5), subject(), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whenRedisCannotBeReachedTheRequestIsLetThroughWithoutWaiting() {
        StatefulRedisConnection<String, String> broken = client.connect();
        broken.close();

        long start = System.nanoTime();
        Decision d = new StrictRedisLimiter(broken).acquire(TestRules.leakyBucket(1, 1, 10), subject(), 1);

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo(Reason.LIMITER_UNAVAILABLE);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
    }
}
