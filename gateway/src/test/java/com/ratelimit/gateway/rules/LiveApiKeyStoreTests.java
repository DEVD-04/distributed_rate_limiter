package com.ratelimit.gateway.rules;

import static com.ratelimit.gateway.rules.TestSnapshots.images;
import static com.ratelimit.gateway.rules.TestSnapshots.key;
import static com.ratelimit.gateway.rules.TestSnapshots.localSyncLeakyBucket;
import static com.ratelimit.gateway.rules.TestSnapshots.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.gateway.auth.ApiKeyStore;
import com.ratelimit.gateway.auth.Identity;
import com.ratelimit.gateway.auth.LiveApiKeyStore;
import com.ratelimit.rules.ApiKeyHash;
import com.ratelimit.rules.RuleSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LiveApiKeyStoreTests {

    private static final String CONFIGURED = "configured-key";
    private static final String PUBLISHED = "published-key";

    private final LiveRuleResolver live = new LiveRuleResolver(RuleSet.of(1, List.of()), 100);

    /** Stands for the keys listed in application.yml. */
    private final ApiKeyStore yaml = hash -> Optional.ofNullable(Map.of(
            ApiKeyHash.of(CONFIGURED), Identity.apiKey("config-user", "free", ApiKeyHash.of(CONFIGURED))).get(hash));

    private final LiveApiKeyStore store = new LiveApiKeyStore(yaml, live);

    private Optional<Identity> find(String apiKey) {
        return store.findByHash(ApiKeyHash.of(apiKey));
    }

    private void publish(long version, String... users) {
        var keys = java.util.Arrays.stream(users).map(u -> key(PUBLISHED, u, "pro")).toList();
        live.apply(snapshot(version, keys, images(1, "r", 5)), LiveRuleResolver.Source.REDIS);
    }

    @Test
    void beforeAnythingIsPublishedTheConfiguredKeysApply() {
        assertThat(find(CONFIGURED)).get().extracting(Identity::userId).isEqualTo("config-user");
        assertThat(find(PUBLISHED)).isEmpty();
    }

    @Test
    void publishedKeysReplaceTheConfiguredOnes() {
        publish(2, "alice");

        assertThat(find(PUBLISHED)).contains(Identity.apiKey("alice", "pro", ApiKeyHash.of(PUBLISHED)));
        assertThat(find(CONFIGURED)).as("not merged: otherwise a revoked key could live on in the YAML").isEmpty();
    }

    @Test
    void publishedConfigurationWithNoKeysMeansNoKeys() {
        publish(2);

        assertThat(find(CONFIGURED)).isEmpty();
        assertThat(find(PUBLISHED)).isEmpty();
    }

    @Test
    void aKeyMissingFromTheNextSnapshotIsRevoked() {
        publish(2, "alice");
        publish(3);

        assertThat(find(PUBLISHED)).isEmpty();
    }

    @Test
    void aRefusedSnapshotLeavesTheKeysAsTheyWere() {
        publish(2, "alice");

        live.apply("garbage", LiveRuleResolver.Source.REDIS);
        // a refused snapshot is refused whole, even though the revocation in it was fine
        live.apply(snapshot(3, localSyncLeakyBucket(9)), LiveRuleResolver.Source.REDIS);

        assertThat(find(PUBLISHED)).isPresent();
    }

    @Test
    void theSameSnapshotAgainKeepsTheKeys() {
        publish(2, "alice");
        publish(2, "alice");

        assertThat(find(PUBLISHED)).isPresent();
    }
}
