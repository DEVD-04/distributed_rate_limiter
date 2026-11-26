package com.ratelimit.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LeakyBucketParamsTests {

    @Test
    void intervalAndMaxWaitAreDerived() {
        LeakyBucketParams p = new LeakyBucketParams(10, 20);
        assertThat(p.intervalMs()).isEqualTo(100.0);
        assertThat(p.maxWaitMs()).isEqualTo(2000.0); // 20 queued requests at 100 ms each
    }

    @Test
    void fractionalRatesWork() {
        LeakyBucketParams p = new LeakyBucketParams(0.5, 10);
        assertThat(p.intervalMs()).isEqualTo(2000.0);
        assertThat(p.maxWaitMs()).isEqualTo(20_000.0);
    }

    @Test
    void aQueueThatWouldHoldRequestsLongerThanAMinuteIsRejected() {
        new LeakyBucketParams(1, 60);                      // exactly 60 s is allowed
        assertThatThrownBy(() -> new LeakyBucketParams(1, 61))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("60 s");
    }

    @Test
    void rejectsValuesOutOfRange() {
        assertThatThrownBy(() -> new LeakyBucketParams(0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeakyBucketParams(-1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeakyBucketParams(Double.NaN, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeakyBucketParams(LeakyBucketParams.MAX_DRAIN_PER_SEC + 1, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeakyBucketParams(10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeakyBucketParams(100_000, LeakyBucketParams.MAX_QUEUE_CAPACITY + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
