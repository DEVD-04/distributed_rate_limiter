package com.ratelimit.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ratelimit.gateway.proxy.UpstreamPool.Upstream;
import com.ratelimit.gateway.ratelimit.FakeNanoClock;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UpstreamPoolTests {

    private static final Duration COOLDOWN = Duration.ofSeconds(10);
    private static final Set<Upstream> NONE = Set.of();

    private final FakeNanoClock clock = new FakeNanoClock();
    private final UpstreamPool pool = new UpstreamPool("svc",
            List.of(URI.create("http://a:1"), URI.create("http://b:1"), URI.create("http://c:1")),
            3, COOLDOWN.toNanos(), clock);
    private final Upstream a = pool.instances().get(0);
    private final Upstream b = pool.instances().get(1);
    private final Upstream c = pool.instances().get(2);

    private void fail(Upstream u, int times) {
        for (int i = 0; i < times; i++) {
            pool.recordFailure(u);
        }
    }

    private List<Upstream> take(int n) {
        List<Upstream> picked = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            picked.add(pool.next(NONE));
        }
        return picked;
    }

    @Test
    void healthyInstancesAreServedRoundRobin() {
        assertThat(take(6)).containsExactly(a, b, c, a, b, c);
    }

    @Test
    void anInstanceIsEjectedAfterTheThresholdOfConsecutiveFailures() {
        fail(a, 2);
        assertThat(pool.isEjected(a)).isFalse();

        pool.recordFailure(a);

        assertThat(pool.isEjected(a)).isTrue();
        assertThat(take(6)).containsOnly(b, c).doesNotContain(a);
    }

    @Test
    void aSuccessClearsTheFailureCount() {
        fail(a, 2);
        pool.recordSuccess(a);
        fail(a, 2);

        assertThat(pool.isEjected(a)).isFalse();
    }

    @Test
    void anEjectedInstanceReturnsExactlyWhenTheCooldownEnds() {
        fail(a, 3);

        clock.advance(COOLDOWN.minusNanos(1));
        assertThat(pool.isEjected(a)).isTrue();
        clock.advanceNanos(1);
        assertThat(pool.isEjected(a)).isFalse();
        assertThat(take(3)).contains(a);
    }

    @Test
    void oneFailureAfterTheCooldownEjectsAgainImmediately() {
        fail(a, 3);
        clock.advance(COOLDOWN);

        pool.recordFailure(a);

        assertThat(pool.isEjected(a)).isTrue();
    }

    @Test
    void oneSuccessAfterTheCooldownClearsTheInstanceCompletely() {
        fail(a, 3);
        clock.advance(COOLDOWN);
        pool.recordSuccess(a);

        fail(a, 2);

        assertThat(pool.isEjected(a)).isFalse(); // needs the full threshold again
    }

    @Test
    void whenEverythingIsEjectedTheOneRecoveringSoonestIsProbed() {
        fail(a, 3);
        clock.advance(Duration.ofSeconds(5));
        fail(b, 3);
        clock.advance(Duration.ofSeconds(1));
        fail(c, 3);

        assertThat(pool.next(NONE)).isSameAs(a);
        assertThat(pool.next(NONE)).isSameAs(a);
    }

    @Test
    void excludedInstancesAreSkipped() {
        assertThat(take(1)).containsExactly(a);

        for (int i = 0; i < 6; i++) {
            assertThat(pool.next(Set.of(a, b))).isSameAs(c);
        }
    }

    @Test
    void ifEveryInstanceIsExcludedTheExclusionIsIgnoredRatherThanReturningNothing() {
        assertThat(pool.next(Set.of(a, b, c))).isNotNull();
    }

    @Test
    void aSingleInstanceIsStillReturnedWhileEjected() {
        UpstreamPool single = new UpstreamPool("solo", List.of(URI.create("http://solo:1")), 1, COOLDOWN.toNanos(), clock);
        Upstream only = single.instances().get(0);
        single.recordFailure(only);

        assertThat(single.isEjected(only)).isTrue();
        assertThat(single.next(NONE)).isSameAs(only);
    }

    @Test
    void manyFailuresDoNotOverflowTheCounter() {
        fail(a, 100_000);
        clock.advance(COOLDOWN);
        pool.recordSuccess(a);

        fail(a, 2);
        assertThat(pool.isEjected(a)).isFalse();
    }

    @Test
    void aPoolNeedsAtLeastOneInstance() {
        assertThatThrownBy(() -> new UpstreamPool("empty", List.of(), 3, 1, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
