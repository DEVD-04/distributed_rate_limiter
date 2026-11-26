package com.ratelimit.gateway.rules;

import static com.ratelimit.gateway.rules.TestSnapshots.images;
import static com.ratelimit.gateway.rules.TestSnapshots.localSyncLeakyBucket;
import static com.ratelimit.gateway.rules.TestSnapshots.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.gateway.rules.LiveRuleResolver.Outcome;
import com.ratelimit.gateway.rules.LiveRuleResolver.Source;
import com.ratelimit.rules.RuleContext;
import com.ratelimit.rules.RuleSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LiveRuleResolverTests {

    private static final RuleContext REQUEST = RuleContext.postAuth("free", "alice", null, "GET", "/api/images/a");

    private final LiveRuleResolver resolver =
            new LiveRuleResolver(RuleSet.of(1, List.of(images(1, "configured", 5))), 100);

    private String winner() {
        return resolver.resolve(REQUEST).rule().name();
    }

    @Test
    void startsWithTheConfiguredRules() {
        assertThat(winner()).isEqualTo("configured");
        assertThat(resolver.source()).isEqualTo(Source.CONFIGURATION);
        assertThat(resolver.current()).isSameAs(resolver.configured());
    }

    @Test
    void aSnapshotReplacesTheRulesAndNoCachedAnswerSurvives() {
        winner(); // fills the old set's lookup cache

        assertThat(resolver.apply(snapshot(7, images(10, "published", 5)), Source.REDIS)).isEqualTo(Outcome.APPLIED);

        assertThat(winner()).isEqualTo("published");
        assertThat(resolver.current().version()).isEqualTo(7);
        assertThat(resolver.source()).isEqualTo(Source.REDIS);
        assertThat(resolver.configured().version()).isEqualTo(1);
    }

    @Test
    void publishedRulesThatDoNotMatchStillLeaveNothingUnlimited() {
        resolver.apply(snapshot(2), Source.REDIS); // a snapshot with no rules at all

        assertThat(winner()).isEqualTo("default-user"); // the built-in default
    }

    @Test
    void theSameSnapshotAgainChangesNothing() {
        String text = snapshot(2, images(10, "published", 5));
        resolver.apply(text, Source.DISK);
        RuleSet inForce = resolver.current();

        assertThat(resolver.apply(text, Source.REDIS)).isEqualTo(Outcome.UNCHANGED);

        assertThat(resolver.current()).isSameAs(inForce); // keeps its cache
        assertThat(resolver.source()).isEqualTo(Source.REDIS); // but is now known to match Redis
    }

    @Test
    void aLowerVersionIsAcceptedBecauseTheDatabaseMayHaveBeenRestored() {
        resolver.apply(snapshot(50, images(10, "newer", 5)), Source.REDIS);

        assertThat(resolver.apply(snapshot(3, images(10, "restored", 5)), Source.REDIS)).isEqualTo(Outcome.APPLIED);
        assertThat(winner()).isEqualTo("restored");
    }

    @Test
    void anInvalidSnapshotChangesNothing() {
        resolver.apply(snapshot(2, images(10, "good", 5)), Source.REDIS);
        String broken = snapshot(3, images(10, "next", 5)).replace("\"capacity\":5", "\"capacity\":0");

        assertThat(resolver.apply(broken, Source.REDIS)).isEqualTo(Outcome.REJECTED);
        assertThat(resolver.apply("garbage", Source.REDIS)).isEqualTo(Outcome.REJECTED);

        assertThat(winner()).isEqualTo("good");
        assertThat(resolver.current().version()).isEqualTo(2);
    }

    @Test
    void aSnapshotThisGatewayCannotEnforceIsRefusedAsAWhole() {
        resolver.apply(snapshot(2, images(10, "good", 5)), Source.REDIS);

        Outcome outcome = resolver.apply(snapshot(3, images(10, "also-changed", 5), localSyncLeakyBucket(11)), Source.REDIS);

        assertThat(outcome).isEqualTo(Outcome.REJECTED);
        assertThat(winner()).isEqualTo("good"); // not even the enforceable part was taken
    }

    @Test
    void requestsSeeOneWholeRuleSetWhileItIsBeingSwapped() throws Exception {
        String a = snapshot(2, images(10, "set-a", 5));
        String b = snapshot(3, images(20, "set-b", 5));
        resolver.apply(a, Source.REDIS);
        AtomicBoolean done = new AtomicBoolean();
        Set<String> seen = ConcurrentHashMap.newKeySet();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> readers = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                readers.add(executor.submit(() -> {
                    while (!done.get()) {
                        var rule = resolver.resolve(REQUEST).rule();
                        seen.add(rule.ruleId() + ":" + rule.name()); // never throws, never a mixture
                    }
                }));
            }
            for (int i = 0; i < 500; i++) {
                resolver.apply(i % 2 == 0 ? b : a, Source.REDIS);
            }
            done.set(true);
            for (Future<?> reader : readers) {
                reader.get();
            }
        }

        assertThat(seen).isSubsetOf("10:set-a", "20:set-b").isNotEmpty();
    }
}
