package com.ratelimit.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FaultInjectorTests {

    private static MockProperties props(long latency, long jitter, double errorRate) {
        return new MockProperties("t", latency, jitter, errorRate, 500);
    }

    @Test
    void zeroErrorRateNeverFails() {
        FaultInjector faults = new FaultInjector(props(0, 0, 0.0));
        for (int i = 0; i < 10_000; i++) {
            assertThat(faults.shouldFail()).isFalse();
        }
    }

    @Test
    void fullErrorRateAlwaysFails() {
        FaultInjector faults = new FaultInjector(props(0, 0, 1.0));
        for (int i = 0; i < 10_000; i++) {
            assertThat(faults.shouldFail()).isTrue();
        }
    }

    @Test
    void partialErrorRateFailsRoughlyThatOften() {
        FaultInjector faults = new FaultInjector(props(0, 0, 0.3));
        int n = 20_000;
        int failures = 0;
        for (int i = 0; i < n; i++) {
            if (faults.shouldFail()) {
                failures++;
            }
        }
        // 0.3 +/- 0.03 is more than 9 standard deviations at this sample size.
        assertThat((double) failures / n).isBetween(0.27, 0.33);
    }

    @Test
    void delayStaysWithinLatencyPlusJitter() {
        FaultInjector faults = new FaultInjector(props(100, 40, 0.0));
        for (int i = 0; i < 5_000; i++) {
            assertThat(faults.delayMillis()).isBetween(100L, 140L);
        }
    }

    @Test
    void delayIsExactlyLatencyWithoutJitter() {
        assertThat(new FaultInjector(props(75, 0, 0.0)).delayMillis()).isEqualTo(75);
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> props(-1, 0, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props(0, -1, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props(0, 0, 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props(0, 0, -0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MockProperties("t", 0, 0, 0.0, 200))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
