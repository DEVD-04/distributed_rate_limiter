package com.ratelimit.gateway.ratelimit.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.ratelimit.strict.StrictRedisLimiter;
import com.ratelimit.gateway.ratelimit.sync.TokenLeaseClient.Lease;
import com.ratelimit.gateway.redis.RedisKeys;
import com.ratelimit.rules.TokenBucketParams;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The lease script against a real Redis. Most tests use a refill so slow that no token arrives while
 * they run, so the amounts are exact.
 */
@Testcontainers
class TokenLeaseClientTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final double NO_REFILL = 0.000001;
    private static final long RULE = 7;

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;
    static TokenLeaseClient leases;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
        leases = new TokenLeaseClient(() -> connection);
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

    private static TokenBucketParams still(long capacity) {
        return new TokenBucketParams(capacity, NO_REFILL);
    }

    private static Lease lease(String who, TokenBucketParams params, long want, long need, long returned, int nodes) {
        return leases.lease(RULE, who, params, want, need, returned, nodes);
    }

    /** A top-up: nobody is waiting and nothing comes back. */
    private static long take(String who, TokenBucketParams params, long want, int nodes) {
        return lease(who, params, want, 0, 0, nodes).granted();
    }

    // ---- fair share ----

    @Test
    void aNewBucketIsFullAndOneCallTakesAtMostAFairShare() {
        String who = subject();

        Lease first = lease(who, still(90), 90, 0, 0, 3);

        assertThat(first.granted()).isEqualTo(30);
        assertThat(first.sharedRemaining()).isEqualTo(60);
        // the share is of what is left, rounded up
        assertThat(take(who, still(90), 90, 3)).isEqualTo(20);
        assertThat(take(who, still(90), 90, 3)).isEqualTo(14);
    }

    @Test
    void aNodeGetsNoMoreThanItAskedFor() {
        Lease lease = lease(subject(), still(90), 5, 0, 0, 3);

        assertThat(lease.granted()).isEqualTo(5);
        assertThat(lease.sharedRemaining()).isEqualTo(85);
    }

    @Test
    void aLoneNodeMayTakeEverything() {
        assertThat(take(subject(), still(50), 1_000, 1)).isEqualTo(50);
    }

    @Test
    void theLastTokenCanStillBeLentWhenThereAreMoreNodesThanTokens() {
        String who = subject();

        for (int i = 0; i < 3; i++) {
            assertThat(take(who, still(3), 3, 10)).as("call %d", i + 1).isEqualTo(1);
        }
        assertThat(take(who, still(3), 3, 10)).isZero();
    }

    // ---- a waiting request ----

    @Test
    void aRequestCostingMoreThanAShareIsStillServedWhenTheBucketCanPay() {
        // the share of a full bucket of 10 over 3 nodes is 4, but the request costs 5
        Lease lease = lease(subject(), still(10), 5, 5, 0, 3);

        assertThat(lease.granted()).isEqualTo(5);
        assertThat(lease.sharedRemaining()).isEqualTo(5);
    }

    @Test
    void aWantBelowTheNeedIsRaisedToIt() {
        assertThat(lease(subject(), still(10), 0, 3, 0, 3).granted()).isEqualTo(3);
    }

    @Test
    void whenTheBucketCannotCoverTheNeedNothingIsTakenFromIt() {
        String who = subject();
        take(who, still(10), 7, 1);

        Lease refused = lease(who, still(10), 5, 5, 0, 3);

        assertThat(refused.granted()).isZero();
        assertThat(refused.sharedRemaining()).as("left to collect, not split into useless parts").isEqualTo(3);
    }

    @Test
    void aTopUpTakesWhateverPartOfTheShareThereIs() {
        String who = subject();
        take(who, still(10), 8, 1);

        assertThat(take(who, still(10), 5, 1)).isEqualTo(2);
    }

    @Test
    void anEmptyBucketSaysHowLongUntilTheNeedCanBeMet() {
        String who = subject();
        var params = new TokenBucketParams(10, 0.5); // one token every 2 s
        take(who, params, 10, 1);

        Lease forOne = lease(who, params, 1, 1, 0, 1);
        Lease forFour = lease(who, params, 4, 4, 0, 1);

        assertThat(forOne.granted()).isZero();
        assertThat(forOne.waitMs()).isBetween(1_500L, 2_000L);
        assertThat(forFour.waitMs()).isBetween(7_500L, 8_000L);
        assertThat(forFour.fullMs()).isBetween(19_500L, 20_000L);
    }

    @Test
    void noWaitIsReportedWhileTheBucketCanCoverTheSameNeedAgain() {
        Lease lease = lease(subject(), still(10), 2, 2, 0, 1);

        assertThat(lease.waitMs()).isZero();
    }

    // ---- giving tokens back ----

    @Test
    void returnedTokensGoBackIntoTheBucket() {
        String who = subject();
        take(who, still(10), 10, 1);

        Lease lease = lease(who, still(10), 0, 0, 4, 3);

        assertThat(lease.granted()).as("want 0 only returns").isZero();
        assertThat(lease.sharedRemaining()).isEqualTo(4);
    }

    @Test
    void returnedTokensCanBeLentAgainByTheSameCall() {
        String who = subject();
        take(who, still(10), 10, 1);

        Lease lease = lease(who, still(10), 3, 3, 4, 1);

        assertThat(lease.granted()).isEqualTo(3);
        assertThat(lease.sharedRemaining()).isEqualTo(1);
    }

    @Test
    void returnsNeverRaiseTheBucketAboveItsCapacity() {
        String who = subject();
        take(who, still(10), 2, 1);

        assertThat(lease(who, still(10), 0, 0, 500, 1).sharedRemaining()).isEqualTo(10);
        assertThat(take(who, still(10), 500, 1)).isEqualTo(10);
    }

    // ---- time and rule changes ----

    @Test
    void theBucketRefillsWithTime() throws InterruptedException {
        String who = subject();
        var params = new TokenBucketParams(1_000, 100);
        take(who, params, 1_000, 1);

        Thread.sleep(300);

        assertThat(take(who, params, 1_000, 1)).isBetween(25L, 150L);
    }

    @Test
    void aTimestampAheadOfRedisTimeCreditsNothingAndIsNotMovedBack() {
        String who = subject();
        String key = RedisKeys.of(RULE, who, "tb");
        long ahead = Long.parseLong(connection.sync().time().get(0)) * 1000 + 60_000;
        connection.sync().hset(key, "tokens", "0");
        connection.sync().hset(key, "ts", Long.toString(ahead));

        assertThat(take(who, new TokenBucketParams(10, 1_000), 10, 1)).isZero();
        assertThat(Double.parseDouble(connection.sync().hget(key, "ts"))).isEqualTo(ahead);
    }

    @Test
    void aLoweredCapacityClampsTheBucket() {
        String who = subject();
        take(who, still(100), 1, 1);

        assertThat(take(who, still(10), 100, 1)).isEqualTo(10);
    }

    // ---- keys ----

    @Test
    void itIsTheSameBucketAsStrictModeUses() {
        String who = subject();
        var strict = new StrictRedisLimiter(connection);
        var rule = TestRules.tokenBucket(RULE, 10, NO_REFILL);
        for (int i = 0; i < 4; i++) {
            strict.acquire(rule, who, 1);
        }

        assertThat(take(who, still(10), 3, 1)).isEqualTo(3);
        assertThat(strict.acquire(rule, who, 1).remaining()).isEqualTo(2);
    }

    @Test
    void rulesAndSubjectsHaveSeparateBuckets() {
        String who = subject();
        take(who, still(10), 10, 1);

        assertThat(take(subject(), still(10), 10, 1)).isEqualTo(10);
        assertThat(leases.lease(RULE + 1, who, still(10), 10, 0, 0, 1).granted()).isEqualTo(10);
    }

    @Test
    void theBucketExpiresWhenIdle() {
        String who = subject();
        var params = new TokenBucketParams(10, 1); // full again after 10 s

        take(who, params, 1, 1);

        assertThat(connection.sync().pttl(RedisKeys.of(RULE, who, "tb"))).isBetween(20_000L, 21_000L);
    }

    // ---- bad input ----

    @Test
    void impossibleArgumentsAreRefused() {
        String who = subject();

        assertThatThrownBy(() -> lease(who, still(10), -1, 0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lease(who, still(10), 1, -1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lease(who, still(10), 1, 0, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lease(who, still(10), 11, 11, 0, 1)).hasMessageContaining("exceeds capacity");
        assertThatThrownBy(() -> lease(who, still(10), 1, 0, 0, 0)).hasMessageContaining("node count");
        assertThat(connection.sync().exists(RedisKeys.of(RULE, who, "tb"))).isZero();
    }

    // ---- many callers at once ----

    @Test
    void concurrentBorrowingAndReturningNeverCreatesTokens() throws Exception {
        String who = subject();
        var params = still(500);
        var held = new AtomicLong(); // tokens borrowed and not given back
        var start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < 100; w++) {
                workers.add(pool.submit(() -> {
                    start.await();
                    long mine = 0;
                    for (int i = 0; i < 20; i++) {
                        var random = ThreadLocalRandom.current();
                        long back = mine == 0 ? 0 : random.nextLong(mine + 1);
                        mine -= back;
                        held.addAndGet(-back); // given up before the call, as a real node must
                        long got = lease(who, params, random.nextInt(1, 40), 0, back, 3).granted();
                        mine += got;
                        assertThat(held.addAndGet(got)).isLessThanOrEqualTo(500);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                try {
                    worker.get();
                } catch (ExecutionException e) {
                    throw new AssertionError(e.getCause());
                }
            }
        }

        long left = lease(who, params, 0, 0, 0, 1).sharedRemaining();
        assertThat(held.get() + left).as("every token is either lent out or still in the bucket").isEqualTo(500);
    }
}
