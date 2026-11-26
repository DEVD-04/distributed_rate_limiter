package com.ratelimit.gateway.rules;

import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.ApiKeyHash;
import com.ratelimit.rules.FailureMode;
import com.ratelimit.rules.LeakyBucketParams;
import com.ratelimit.rules.LimitBy;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.TokenBucketParams;
import com.ratelimit.rules.snapshot.ConfigSnapshot;
import com.ratelimit.rules.snapshot.SnapshotCodec;
import java.util.Arrays;
import java.util.List;

/** Published snapshots, as the policy service would produce them. */
final class TestSnapshots {

    private TestSnapshots() {
    }

    /** A token bucket rule for {@code /api/images/**}, for users of any tier. */
    static Rule images(long ruleId, String name, long capacity) {
        return new Rule(ruleId, name, LimitBy.USER, null, null, "/api/images/**", null, Algorithm.TOKEN_BUCKET,
                Mode.STRICT, FailureMode.LOCAL_FALLBACK, new TokenBucketParams(capacity, 0.000001), 0, 1);
    }

    /** Valid as a rule, but no gateway can enforce it yet. */
    static Rule localSyncLeakyBucket(long ruleId) {
        return new Rule(ruleId, "local-leaky", LimitBy.USER, null, null, "/api/video/**", null,
                Algorithm.LEAKY_BUCKET_QUEUE, Mode.LOCAL_SYNC, FailureMode.LOCAL_FALLBACK,
                new LeakyBucketParams(5, 5), 0, 1);
    }

    static String snapshot(long version, Rule... rules) {
        return snapshot(version, List.of(), rules);
    }

    static String snapshot(long version, List<ConfigSnapshot.ApiKeyEntry> apiKeys, Rule... rules) {
        return SnapshotCodec.encode(new ConfigSnapshot(version, Arrays.asList(rules), apiKeys));
    }

    static ConfigSnapshot.ApiKeyEntry key(String apiKey, String userId, String tier) {
        return new ConfigSnapshot.ApiKeyEntry(ApiKeyHash.of(apiKey), userId, tier);
    }
}
