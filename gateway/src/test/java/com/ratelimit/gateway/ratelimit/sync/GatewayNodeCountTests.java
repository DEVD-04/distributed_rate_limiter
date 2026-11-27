package com.ratelimit.gateway.ratelimit.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ratelimit.gateway.GatewayApplication;
import com.ratelimit.gateway.redis.LateRedis;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Whole gateways counting each other, with the default timings (report every 1 s, dropped after 3 s). */
@Testcontainers
class GatewayNodeCountTests {

    private final List<ConfigurableApplicationContext> gateways = new ArrayList<>();

    @AfterEach
    void stopGateways() {
        gateways.forEach(ConfigurableApplicationContext::close);
    }

    private ConfigurableApplicationContext startGateway(String redisUrl) {
        ConfigurableApplicationContext gateway = new SpringApplicationBuilder(GatewayApplication.class).run(
                "--server.port=0",
                "--gateway.redis.url=" + redisUrl,
                "--gateway.redis.command-timeout=200ms");
        gateways.add(gateway);
        return gateway;
    }

    private static boolean isListed(String redisUrl, String nodeId) {
        RedisClient client = RedisClient.create(redisUrl);
        try (var connection = client.connect()) {
            return connection.sync().zscore(NodeRegistry.KEY, nodeId) != null;
        } finally {
            client.shutdown();
        }
    }

    private static int count(ConfigurableApplicationContext gateway) {
        return gateway.getBean(NodeRegistry.class).nodeCount();
    }

    private static void awaitCount(int expected, Duration within, ConfigurableApplicationContext... all) {
        await().atMost(within).until(() -> {
            for (ConfigurableApplicationContext gateway : all) {
                if (count(gateway) != expected) {
                    return false;
                }
            }
            return true;
        });
    }

    @Test
    void gatewaysCountEachOtherAsTheyJoinAndLeaveAndKeepTheCountThroughAnOutage() throws InterruptedException {
        try (var redis = new LateRedis()) {
            // Redis is not there yet: gateways start anyway, each knowing only itself
            var first = startGateway(redis.url());
            assertThat(count(first)).isEqualTo(1);

            redis.start();
            var second = startGateway(redis.url());
            var third = startGateway(redis.url());
            awaitCount(3, Duration.ofSeconds(5), first, second, third);

            String leaving = third.getBean(NodeRegistry.class).nodeId();
            assertThat(isListed(redis.url(), leaving)).isTrue();
            third.close();
            // checked at once, long before the timeout: the gateway said goodbye while it still had its connection
            assertThat(isListed(redis.url(), leaving)).isFalse();
            awaitCount(2, Duration.ofSeconds(2), first, second);

            redis.stopServer();
            Thread.sleep(4_000); // longer than the node timeout
            assertThat(count(first)).isEqualTo(2);
            assertThat(count(second)).isEqualTo(2);

            redis.startServerAgain();
            var fourth = startGateway(redis.url());
            awaitCount(3, Duration.ofSeconds(8), first, second, fourth);
        }
    }

    @Test
    void withoutRedisThereIsNothingToCount() {
        var gateway = startGateway("");

        assertThat(gateway.getBeanNamesForType(NodeRegistry.class)).isEmpty();
    }
}
