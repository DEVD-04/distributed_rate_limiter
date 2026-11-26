package com.ratelimit.rules.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.ApiKeyHash;
import com.ratelimit.rules.FailureMode;
import com.ratelimit.rules.LeakyBucketParams;
import com.ratelimit.rules.LimitBy;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.Params;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.SlidingWindowParams;
import com.ratelimit.rules.TokenBucketParams;
import com.ratelimit.rules.snapshot.ConfigSnapshot.ApiKeyEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotCodecTests {

    private static Rule rule(long id, Algorithm algorithm, Params params) {
        return new Rule(id, "rule-" + id, LimitBy.USER, null, null, "/r" + id + "/**", null, algorithm, Mode.STRICT,
                FailureMode.LOCAL_FALLBACK, params, 0, 1);
    }

    private static final Rule FULL = new Rule(1, "full", LimitBy.IP, "pro", "2001:db8::1", "/a/**", "POST",
            Algorithm.TOKEN_BUCKET, Mode.LOCAL_SYNC, FailureMode.FAIL_CLOSED, new TokenBucketParams(10, 0.5, 2), -7, 42);
    private static final Rule WINDOW = rule(2, Algorithm.SLIDING_WINDOW_LOG, new SlidingWindowParams(5, 60_000));
    private static final Rule LEAKY = rule(3, Algorithm.LEAKY_BUCKET_QUEUE, new LeakyBucketParams(2.5, 5));
    private static final ApiKeyEntry KEY_A = new ApiKeyEntry(ApiKeyHash.of("a"), "alice", "pro");
    private static final ApiKeyEntry KEY_B = new ApiKeyEntry(ApiKeyHash.of("b"), "bob", "free");

    private static ConfigSnapshot sample() {
        return new ConfigSnapshot(9, List.of(FULL, WINDOW, LEAKY), List.of(KEY_A, KEY_B));
    }

    @Test
    void everyFieldOfEveryAlgorithmSurvivesARoundTrip() {
        ConfigSnapshot back = SnapshotCodec.decode(SnapshotCodec.encode(sample()));

        assertThat(back.version()).isEqualTo(9);
        assertThat(back.rules()).containsExactly(FULL, WINDOW, LEAKY);
        assertThat(back.apiKeys()).containsExactlyInAnyOrder(KEY_A, KEY_B);
    }

    @Test
    void anEmptyConfigurationIsValid() {
        ConfigSnapshot back = SnapshotCodec.decode(SnapshotCodec.encode(new ConfigSnapshot(1, List.of(), List.of())));

        assertThat(back.rules()).isEmpty();
        assertThat(back.apiKeys()).isEmpty();
    }

    @Test
    void theSameConfigurationAlwaysEncodesToTheSameTextWhateverTheOrder() {
        String a = SnapshotCodec.encode(new ConfigSnapshot(9, List.of(FULL, WINDOW, LEAKY), List.of(KEY_A, KEY_B)));
        String b = SnapshotCodec.encode(new ConfigSnapshot(9, List.of(LEAKY, FULL, WINDOW), List.of(KEY_B, KEY_A)));

        assertThat(a).isEqualTo(b);
        assertThat(SnapshotCodec.encode(SnapshotCodec.decode(a))).isEqualTo(a);
    }

    private static void assertRejected(String text, String messagePart) {
        assertThatThrownBy(() -> SnapshotCodec.decode(text))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(messagePart);
    }

    @Test
    void textThatIsNotASnapshotIsRejected() {
        assertRejected("not json", "not valid");
        assertRejected("", "format");
        assertRejected("{}", "format");
        assertRejected(SnapshotCodec.encode(sample()) + "{}", "not valid");
    }

    @Test
    void anUnknownFormatIsRejected() {
        assertRejected(SnapshotCodec.encode(sample()).replace("\"format\":1", "\"format\":2"), "unsupported snapshot format");
    }

    @Test
    void missingPartsAreRejected() {
        assertRejected("{\"format\":1,\"version\":1,\"rules\":[]}", "apiKeys");
        assertRejected("{\"format\":1,\"rules\":[],\"apiKeys\":[]}", "version");
        assertRejected("{\"format\":1,\"version\":1,\"rules\":null,\"apiKeys\":[]}", "missing");
    }

    @Test
    void unknownFieldsAreRejected() {
        assertRejected(SnapshotCodec.encode(sample()).replace("\"priority\":-7", "\"priority\":-7,\"surprise\":1"), "surprise");
    }

    @Test
    void oneBadRuleRejectsTheWholeSnapshot() {
        String text = SnapshotCodec.encode(sample());

        assertRejected(text.replace("\"window_ms\":60000", "\"window_ms\":0"), "window_ms");
        assertRejected(text.replace("\"limit\":5", "\"limt\":5"), "limt");
        assertRejected(text.replace("\"/r2/**\"", "\"/r2/**/x\""), "pattern");
        assertRejected(text.replace("\"SLIDING_WINDOW_LOG\"", "\"FIXED_WINDOW\""), "not valid");
    }

    @Test
    void rulesThatCannotCoexistAreRejected() {
        Rule sameScope = new Rule(4, "clash", LimitBy.USER, null, null, "/r2/**", null, Algorithm.TOKEN_BUCKET, Mode.STRICT,
                FailureMode.LOCAL_FALLBACK, new TokenBucketParams(1, 1), 0, 1);
        String text = SnapshotCodec.encode(new ConfigSnapshot(2, List.of(WINDOW, sameScope), List.of()));

        assertRejected(text, "same scope");
    }

    @Test
    void aMalformedApiKeyEntryRejectsTheSnapshot() {
        assertRejected(SnapshotCodec.encode(sample()).replace(KEY_A.keyHash(), "raw-key"), "SHA-256");
    }
}
