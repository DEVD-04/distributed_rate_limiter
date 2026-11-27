package com.ratelimit.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisCommandInterruptedException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RedisBreakerTests {

    private static final Duration SHORT = Duration.ofMillis(200);
    private static final Duration LONG = Duration.ofMinutes(1);

    private final AtomicInteger made = new AtomicInteger();

    /** The design's settings, but for how long it stays open. */
    private static RedisBreaker breaker(Duration openFor) {
        return new RedisBreaker(new RedisBreakerProperties(20, 50, openFor, 3));
    }

    private void succeed(RedisBreaker breaker, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(breaker.call(() -> made.incrementAndGet() > 0 ? "answer" : null)).isEqualTo("answer");
        }
    }

    private void fail(RedisBreaker breaker, int times) {
        for (int i = 0; i < times; i++) {
            var failure = new IllegalStateException("timed out");
            assertThatThrownBy(() -> breaker.call(() -> {
                made.incrementAndGet();
                throw failure;
            })).as("the caller sees the failure itself").isSameAs(failure);
        }
    }

    private void assertSkipped(RedisBreaker breaker) {
        int before = made.get();
        assertThatThrownBy(() -> breaker.call(made::incrementAndGet)).isInstanceOf(RedisSkippedException.class);
        assertThat(made).as("the call was not made").hasValue(before);
    }

    private static void waitOut(Duration openFor) throws InterruptedException {
        Thread.sleep(openFor.toMillis() + 100);
    }

    @Test
    void itOpensOnceHalfOfTheLatestTwentyCallsHaveFailed() {
        var breaker = breaker(LONG);

        succeed(breaker, 11);
        fail(breaker, 9);
        assertThat(breaker.isClosed()).as("9 of 20 failed").isTrue();

        fail(breaker, 1);

        assertThat(breaker.isClosed()).isFalse();
        assertSkipped(breaker);
    }

    @Test
    void onlyTheLatestCallsCount() {
        var breaker = breaker(LONG);
        succeed(breaker, 500);

        fail(breaker, 9);
        assertThat(breaker.isClosed()).isTrue();
        fail(breaker, 1);

        assertThat(breaker.isClosed()).isFalse();
    }

    @Test
    void nothingOpensBeforeThereAreTwentyCalls() {
        var breaker = breaker(LONG);

        fail(breaker, 19);
        assertThat(breaker.isClosed()).isTrue();
        fail(breaker, 1);

        assertThat(breaker.isClosed()).isFalse();
    }

    @Test
    void afterTheWaitThreeSuccessfulTrialCallsCloseIt() throws InterruptedException {
        var breaker = breaker(SHORT);
        fail(breaker, 20);
        assertSkipped(breaker);

        waitOut(SHORT);
        succeed(breaker, 2);
        assertThat(breaker.isClosed()).as("still on trial").isFalse();
        succeed(breaker, 1);

        assertThat(breaker.isClosed()).isTrue();
        succeed(breaker, 50);
    }

    @Test
    void failingTrialCallsOpenItAgain() throws InterruptedException {
        var breaker = breaker(SHORT);
        fail(breaker, 20);
        waitOut(SHORT);

        fail(breaker, 3);

        assertSkipped(breaker);
        waitOut(SHORT);
        succeed(breaker, 3);
        assertThat(breaker.isClosed()).isTrue();
    }

    @Test
    void anInterruptedCallSaysNothingAboutRedis() {
        var breaker = breaker(LONG);

        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> breaker.call(() -> {
                throw new RedisCommandInterruptedException(new InterruptedException());
            })).isInstanceOf(RedisCommandInterruptedException.class);
        }

        assertThat(breaker.isClosed()).isTrue();
    }

    @Test
    void aDisabledBreakerAlwaysCalls() {
        var breaker = RedisBreaker.disabled();

        fail(breaker, 100);

        assertThat(breaker.isClosed()).isTrue();
        succeed(breaker, 1);
    }

    @Test
    void impossibleSettingsAreRefusedByName() {
        assertThatThrownBy(() -> new RedisBreakerProperties(0, 50, SHORT, 3)).hasMessageContaining("breaker.window");
        assertThatThrownBy(() -> new RedisBreakerProperties(20, 0, SHORT, 3)).hasMessageContaining("failure-rate");
        assertThatThrownBy(() -> new RedisBreakerProperties(20, 101, SHORT, 3)).hasMessageContaining("failure-rate");
        assertThatThrownBy(() -> new RedisBreakerProperties(20, 50, Duration.ZERO, 3)).hasMessageContaining("open-for");
        assertThatThrownBy(() -> new RedisBreakerProperties(20, 50, SHORT, 0)).hasMessageContaining("probes");
    }
}
