package com.ratelimit.gateway.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ParamsFactoryTests {

    private static Params parse(Map<String, String> raw) {
        return ParamsFactory.parse(Algorithm.TOKEN_BUCKET, raw);
    }

    @Test
    void parsesTokenBucketParams() {
        assertThat(parse(Map.of("capacity", "10", "refill_per_sec", "2.5", "cost", "2")))
                .isEqualTo(new TokenBucketParams(10, 2.5, 2));
    }

    @Test
    void costDefaultsToOne() {
        assertThat(parse(Map.of("capacity", "10", "refill_per_sec", "2"))).isEqualTo(new TokenBucketParams(10, 2, 1));
    }

    @Test
    void acceptsKebabCaseAndMixedCaseKeys() {
        assertThat(parse(Map.of("Capacity", "10", "refill-per-sec", "2")))
                .isEqualTo(new TokenBucketParams(10, 2, 1));
    }

    @Test
    void rejectsUnknownKeysSoTyposFailLoudly() {
        assertThatThrownBy(() -> parse(Map.of("capcity", "10", "refill_per_sec", "2")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capcity");
    }

    @Test
    void rejectsMissingRequiredKeys() {
        assertThatThrownBy(() -> parse(Map.of("refill_per_sec", "2")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capacity");
        assertThatThrownBy(() -> parse(Map.of("capacity", "10")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refill_per_sec");
        assertThatThrownBy(() -> parse(Map.of("capacity", "", "refill_per_sec", "2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonNumericValues() {
        assertThatThrownBy(() -> parse(Map.of("capacity", "ten", "refill_per_sec", "2")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capacity");
        assertThatThrownBy(() -> parse(Map.of("capacity", "10", "refill_per_sec", "fast")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refill_per_sec");
        assertThatThrownBy(() -> parse(Map.of("capacity", "10.5", "refill_per_sec", "2")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole number");
    }

    @Test
    void valuesOutsideTheAllowedRangeAreRejected() {
        assertThatThrownBy(() -> parse(Map.of("capacity", "0", "refill_per_sec", "2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(Map.of("capacity", "10", "refill_per_sec", "-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(Map.of("capacity", "10", "refill_per_sec", "2", "cost", "11")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateKeysDifferingOnlyInSpellingAreRejected() {
        assertThatThrownBy(() -> parse(Map.of("refill_per_sec", "1", "refill-per-sec", "2", "capacity", "3")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
    }

    @Test
    void parsesSlidingWindowParams() {
        assertThat(ParamsFactory.parse(Algorithm.SLIDING_WINDOW_LOG, Map.of("limit", "5", "window_ms", "1000")))
                .isEqualTo(new SlidingWindowParams(5, 1000));
        assertThat(ParamsFactory.parse(Algorithm.SLIDING_WINDOW_LOG, Map.of("limit", "5", "window-ms", "1000")))
                .isEqualTo(new SlidingWindowParams(5, 1000));
    }

    @Test
    void slidingWindowRejectsTyposMissingKeysAndBadValues() {
        assertThatThrownBy(() -> ParamsFactory.parse(Algorithm.SLIDING_WINDOW_LOG, Map.of("limit", "5", "windo_ms", "1000")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("windo_ms");
        assertThatThrownBy(() -> ParamsFactory.parse(Algorithm.SLIDING_WINDOW_LOG, Map.of("limit", "5")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("window_ms");
        assertThatThrownBy(() -> ParamsFactory.parse(Algorithm.SLIDING_WINDOW_LOG, Map.of("limit", "0", "window_ms", "1000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesLeakyBucketParams() {
        assertThat(ParamsFactory.parse(Algorithm.LEAKY_BUCKET_QUEUE, Map.of("drain_per_sec", "10", "queue_capacity", "20")))
                .isEqualTo(new LeakyBucketParams(10, 20));
        assertThat(ParamsFactory.parse(Algorithm.LEAKY_BUCKET_QUEUE, Map.of("drain-per-sec", "2.5", "queue-capacity", "5")))
                .isEqualTo(new LeakyBucketParams(2.5, 5));
    }

    @Test
    void leakyBucketRejectsTyposMissingKeysAndBadValues() {
        assertThatThrownBy(() -> ParamsFactory.parse(Algorithm.LEAKY_BUCKET_QUEUE, Map.of("drain_per_sec", "10", "queue_size", "20")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("queue_size");
        assertThatThrownBy(() -> ParamsFactory.parse(Algorithm.LEAKY_BUCKET_QUEUE, Map.of("drain_per_sec", "10")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("queue_capacity");
        assertThatThrownBy(() -> ParamsFactory.parse(Algorithm.LEAKY_BUCKET_QUEUE, Map.of("drain_per_sec", "0", "queue_capacity", "5")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
