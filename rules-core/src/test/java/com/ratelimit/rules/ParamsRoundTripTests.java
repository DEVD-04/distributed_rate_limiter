package com.ratelimit.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What is written to the database (and later published) must parse back to exactly the same params. */
class ParamsRoundTripTests {

    private static Params roundTrip(Algorithm algorithm, Params params) {
        Map<String, String> asText = new LinkedHashMap<>();
        ParamsFactory.toMap(params).forEach((key, value) -> asText.put(key, value.toString()));
        return ParamsFactory.parse(algorithm, asText);
    }

    @Test
    void everyAlgorithmSurvivesARoundTrip() {
        for (Params p : List.of(new TokenBucketParams(10, 1), new TokenBucketParams(5, 0.5, 2),
                new TokenBucketParams(TokenBucketParams.MAX_CAPACITY, 0.000001), new TokenBucketParams(7, 1.0 / 3))) {
            assertThat(roundTrip(Algorithm.TOKEN_BUCKET, p)).isEqualTo(p);
        }
        assertThat(roundTrip(Algorithm.SLIDING_WINDOW_LOG, new SlidingWindowParams(100, 60_000)))
                .isEqualTo(new SlidingWindowParams(100, 60_000));
        for (Params p : List.of(new LeakyBucketParams(10, 20), new LeakyBucketParams(2.5, 5))) {
            assertThat(roundTrip(Algorithm.LEAKY_BUCKET_QUEUE, p)).isEqualTo(p);
        }
    }

    @Test
    void keysAreCanonicalAndWholeRatesHaveNoDecimalPoint() {
        assertThat(ParamsFactory.toMap(new TokenBucketParams(10, 5)))
                .containsExactly(Map.entry("capacity", 10L), Map.entry("refill_per_sec", 5L), Map.entry("cost", 1));
        assertThat(ParamsFactory.toMap(new LeakyBucketParams(2.5, 5)))
                .containsExactly(Map.entry("drain_per_sec", 2.5), Map.entry("queue_capacity", 5L));
    }

    @Test
    void onlyTheTokenBucketCanBeLimitedInMemory() {
        assertThat(Rule.servableInMemory(Algorithm.TOKEN_BUCKET)).isTrue();
        assertThat(Rule.servableInMemory(Algorithm.SLIDING_WINDOW_LOG)).isFalse();
        assertThat(Rule.servableInMemory(Algorithm.LEAKY_BUCKET_QUEUE)).isFalse();
    }
}
