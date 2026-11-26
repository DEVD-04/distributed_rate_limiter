package com.ratelimit.gateway.ratelimit.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.gateway.ratelimit.FakeNanoClock;
import com.ratelimit.rules.TokenBucketParams;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Tests of the bucket on its own, without the cache around it. */
class LocalTokenBucketTests {

    private static final long ONE_TOKEN = TokenBucketParams.MICRO;

    private final FakeNanoClock clock = new FakeNanoClock();
    private final TokenBucketParams params = new TokenBucketParams(2, 1); // capacity 2, 1 token/s
    private final LocalTokenBucket bucket = new LocalTokenBucket(params, Long.MAX_VALUE);

    private boolean take() {
        return bucket.tryAcquire(params, ONE_TOKEN, clock).allowed();
    }

    @Test
    void aClockStepBackwardsNeverCreditsTheSameTimeTwice() {
        clock.set(Duration.ofSeconds(100).toNanos());
        assertThat(take()).isTrue();
        assertThat(take()).isTrue(); // empty at t = 100 s

        clock.set(Duration.ofSeconds(50).toNanos());      // the clock goes backwards
        assertThat(take()).isFalse();
        clock.set(Duration.ofMillis(100_500).toNanos());  // forward again: only 0.5 s past the high-water mark
        assertThat(take()).isFalse();
        clock.set(Duration.ofSeconds(101).toNanos());     // 1 s past it: exactly one token
        assertThat(take()).isTrue();
        assertThat(take()).isFalse();
    }

    @Test
    void timeBetweenTwoCallsWithTheSameTimestampIsNotLost() {
        clock.set(Duration.ofSeconds(10).toNanos());
        take();
        take(); // empty
        clock.advanceMillis(600);
        assertThat(take()).isFalse();   // 0.6 token
        clock.advanceMillis(399);
        assertThat(take()).isFalse();   // 0.999 token
        clock.advanceMillis(1);
        assertThat(take()).isTrue();    // 1.000 token
    }

    @Test
    void theClockIsReadExactlyOncePerCall() {
        int[] reads = {0};
        bucket.tryAcquire(params, ONE_TOKEN, () -> {
            reads[0]++;
            return 0L;
        });
        assertThat(reads[0]).isEqualTo(1);
    }
}
