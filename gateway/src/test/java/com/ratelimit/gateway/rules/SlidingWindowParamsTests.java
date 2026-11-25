package com.ratelimit.gateway.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SlidingWindowParamsTests {

    @Test
    void acceptsValuesInRange() {
        SlidingWindowParams p = new SlidingWindowParams(100, 60_000);
        assertThat(p.limit()).isEqualTo(100);
        assertThat(p.windowMs()).isEqualTo(60_000);
        new SlidingWindowParams(SlidingWindowParams.MAX_LIMIT, SlidingWindowParams.MAX_WINDOW_MS);
        new SlidingWindowParams(1, 1);
    }

    @Test
    void rejectsValuesOutOfRange() {
        assertThatThrownBy(() -> new SlidingWindowParams(0, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowParams(-1, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowParams(SlidingWindowParams.MAX_LIMIT + 1, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowParams(5, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowParams(5, SlidingWindowParams.MAX_WINDOW_MS + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
