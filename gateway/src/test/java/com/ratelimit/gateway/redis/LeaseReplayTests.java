package com.ratelimit.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ratelimit.gateway.ratelimit.sync.TokenLeaseClient;
import com.ratelimit.rules.TokenBucketParams;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A lease call whose reply is lost, using the gateway's own Redis client settings. The call ran, so the
 * tokens it returned must count once, whether or not the client library sends it again.
 */
@Testcontainers
class LeaseReplayTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final TokenBucketParams STILL = new TokenBucketParams(1_000, 0.000001);

    private record Setup(TokenLeaseClient leases, ReplyDroppingProxy proxy) {
    }

    /**
     * Runs {@code test} with an emptied bucket, through a proxy; the second argument reads the bucket's whole
     * tokens straight from Redis.
     */
    private static void withGatewayClient(Duration commandTimeout, BiConsumer<Setup, TokensNow> test) throws Exception {
        RedisClient directClient = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        try (var proxy = new ReplyDroppingProxy(REDIS.getHost(), REDIS.getMappedPort(6379));
                var direct = directClient.connect()) {
            var config = new RedisConfig();
            var properties = new RedisProperties(proxy.url(), commandTimeout, Duration.ofSeconds(1));
            ClientResources resources = config.redisClientResources();
            RedisClient client = config.redisClient(resources, properties);
            try (var connector = config.redisConnector(client, properties)) {
                var leases = new TokenLeaseClient(connector::connection);
                String who = "s-" + UUID.randomUUID();
                assertThat(leases.lease(1, who, STILL, 1_000, 0, 0, 1).granted()).isEqualTo(1_000);
                test.accept(new Setup(leases, proxy.forSubject(who)),
                        () -> (long) Double.parseDouble(direct.sync().hget(RedisKeys.of(1, who, "tb"), "tokens")));
            } finally {
                client.shutdown();
                resources.shutdown();
            }
        } finally {
            directClient.shutdown();
        }
    }

    private interface TokensNow {
        long read();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aCallTheLibrarySendsAgainCreditsItsReturnOnlyOnce() throws Exception {
        // a timeout longer than the 1 s reconnect delay: the call is still pending when the connection returns
        withGatewayClient(Duration.ofSeconds(5), (setup, tokens) -> {
            setup.proxy().dropTheReplyToTheNextScriptCall();

            var lease = setup.leases().lease(1, setup.proxy().subject(), STILL, 0, 0, 5, 1);

            assertThat(setup.proxy().dropped).as("the first reply never arrived").hasValue(1);
            // so this reply can only have come from a second run of the same call
            assertThat(lease.sharedRemaining()).isEqualTo(5);
            assertThat(tokens.read()).isEqualTo(5);
        });
    }

    @Test
    void withTheDefaultTimeoutTheCallFailsAndTheReturnHasCountedAtMostOnce() throws Exception {
        withGatewayClient(Duration.ofMillis(15), (setup, tokens) -> {
            sleep(200); // the connection is warm, so only the dropped reply can make the call fail
            setup.proxy().dropTheReplyToTheNextScriptCall();

            assertThatThrownBy(() -> setup.leases().lease(1, setup.proxy().subject(), STILL, 0, 0, 5, 1))
                    .isInstanceOf(RedisCommandTimeoutException.class);

            sleep(2_500); // well past reconnecting
            assertThat(setup.proxy().dropped).hasValue(1);
            assertThat(tokens.read()).isEqualTo(5);
        });
    }

    @Test
    void separateCallsEachCreditTheirOwnReturn() throws Exception {
        withGatewayClient(Duration.ofSeconds(5), (setup, tokens) -> {
            for (int i = 0; i < 3; i++) {
                setup.leases().lease(1, setup.proxy().subject(), STILL, 0, 0, 5, 1);
            }

            assertThat(tokens.read()).isEqualTo(15);
        });
    }
}
