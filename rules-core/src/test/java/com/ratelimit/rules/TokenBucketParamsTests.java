package com.ratelimit.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TokenBucketParamsTests {

    @Test
    void costDefaultsToOne() {
        assertThat(new TokenBucketParams(10, 2).cost()).isEqualTo(1);
    }

    @Test
    void convertsToMicroTokens() {
        TokenBucketParams p = new TokenBucketParams(10, 2.5, 1);
        assertThat(p.capacityMicro()).isEqualTo(10_000_000L);
        assertThat(p.refillMicroPerSecond()).isEqualTo(2_500_000L);
    }

    @Test
    void smallestAllowedRefillRateIsOneMicroTokenPerSecond() {
        assertThat(new TokenBucketParams(1, 0.000001).refillMicroPerSecond()).isEqualTo(1);
    }

    @Test
    void acceptsTheLargestSupportedCapacity() {
        TokenBucketParams p = new TokenBucketParams(TokenBucketParams.MAX_CAPACITY, 1);
        assertThat(p.capacityMicro()).isEqualTo(1_000_000_000_000_000L);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> new TokenBucketParams(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketParams(-5, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketParams(TokenBucketParams.MAX_CAPACITY + 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketParams(10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketParams(10, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketParams(10, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketParams(10, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketParams(10, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketParams(10, 1, 11)).isInstanceOf(IllegalArgumentException.class);
    }
}
