package com.ratelimit.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimit.policy.publish.SnapshotPublisher;
import com.ratelimit.policy.publish.SnapshotStore;
import com.ratelimit.rules.ApiKeyHash;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.snapshot.ConfigSnapshot;
import com.ratelimit.rules.snapshot.SnapshotCodec;
import com.ratelimit.rules.snapshot.SnapshotKeys;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The published copy against a real PostgreSQL and a real Redis: what it contains and how it is repaired.
 * The periodic reconcile runs every 500 ms here. That publishing is triggered by the change itself, not
 * only by that timer, is proven in {@link PublishAfterChangeTests}.
 */
@Testcontainers
@SpringBootTest(properties = {
        "policy.admin-token=" + SnapshotPublishingTests.TOKEN,
        "policy.publish.reconcile-interval=500ms",
        "spring.flyway.clean-disabled=false"})
@AutoConfigureMockMvc
@Import(PostgresTestSupport.class)
class SnapshotPublishingTests {

    static final String TOKEN = "publishing-test-token-0123456789";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedisClient redisClient;
    static StatefulRedisConnection<String, String> redis;
    static StatefulRedisPubSubConnection<String, String> subscriber;
    static final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

    @DynamicPropertySource
    static void redisUrl(DynamicPropertyRegistry registry) {
        registry.add("policy.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @BeforeAll
    static void connect() {
        redisClient = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redis = redisClient.connect();
        subscriber = redisClient.connectPubSub();
        subscriber.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                messages.add(message);
            }
        });
        subscriber.sync().subscribe(SnapshotKeys.CHANNEL);
    }

    @AfterAll
    static void disconnect() {
        subscriber.close();
        redis.close();
        redisClient.shutdown();
    }

    @Autowired MockMvc mvc;
    @Autowired Flyway flyway;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired SnapshotPublisher publisher;
    @Autowired SnapshotStore store;

    @BeforeEach
    void freshDatabaseAndRedis() throws Exception {
        // The background publisher may be reading the tables just as they are dropped, and PostgreSQL then
        // breaks the deadlock by failing one side, sometimes the clean. A failed clean changes nothing.
        await().atMost(Duration.ofSeconds(10)).ignoreExceptions().until(() -> {
            flyway.clean();
            return true;
        });
        flyway.migrate();
        redis.sync().flushall();
        publisher.sync();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(published().rules()).hasSize(5));
        Thread.sleep(100); // let any message from setting up arrive before clearing them
        messages.clear();
    }

    // ---- helpers ----

    /** What a gateway would load right now. */
    private static ConfigSnapshot published() {
        String text = redis.sync().hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT);
        assertThat(text).as("a snapshot is published").isNotNull();
        return SnapshotCodec.decode(text);
    }

    private static String publishedVersionField() {
        return redis.sync().hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_VERSION);
    }

    /** Publishing happens just after the response, on its own thread. */
    private static void awaitPublished(long version) {
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(publishedVersionField()).isEqualTo(Long.toString(version)));
    }

    private org.springframework.mock.web.MockHttpServletResponse admin(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Admin-Token", TOKEN).contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
    }

    private static String tokenBucket(String name, String path) {
        return """
                {"name": "%s", "limitBy": "USER", "endpointPattern": "%s", "algorithm": "TOKEN_BUCKET",
                 "params": {"capacity": 5, "refill_per_sec": 1}}""".formatted(name, path);
    }

    private long idOf(String ruleName) {
        return jdbc.sql("SELECT rule_id FROM ratelimit_rules WHERE name = :n AND is_active").param("n", ruleName)
                .query(Long.class).single();
    }

    // ---- publishing changes ----

    @Test
    void theStartingConfigurationIsPublishedWithItsVersion() {
        assertThat(publishedVersionField()).isEqualTo("1");
        assertThat(published().version()).isEqualTo(1);
        assertThat(published().rules()).extracting(Rule::name)
                .containsExactly("default-ip", "default-user", "free-images", "pro-images", "text-generation");
        assertThat(published().apiKeys()).isEmpty();
        assertThat(redis.sync().pttl(SnapshotKeys.KEY)).isEqualTo(-1); // no TTL, so never evicted
    }

    @Test
    void aChangeIsPublishedAndAnnounced() throws Exception {
        assertThat(admin(post("/admin/rules").content(tokenBucket("search", "/search/**"))).getStatus()).isEqualTo(201);

        awaitPublished(2);
        assertThat(published().rules()).extracting(Rule::name).contains("search");
        assertThat(messages.poll(2, TimeUnit.SECONDS)).isEqualTo("2");
    }

    @Test
    void aDeactivatedRuleDisappearsFromThePublishedCopy() throws Exception {
        admin(delete("/admin/rules/" + idOf("free-images")));

        awaitPublished(2);
        assertThat(published().rules()).extracting(Rule::name).doesNotContain("free-images").hasSize(4);
    }

    @Test
    void apiKeysArePublishedAsHashesAndDisappearWhenRevoked() throws Exception {
        var created = json.readTree(admin(post("/admin/api-keys").content("{\"userId\": \"alice\", \"tier\": \"pro\"}"))
                .getContentAsString());
        String apiKey = created.get("apiKey").asText();

        awaitPublished(2);
        assertThat(published().apiKeys()).containsExactly(
                new ConfigSnapshot.ApiKeyEntry(ApiKeyHash.of(apiKey), "alice", "pro"));
        assertThat(redis.sync().hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT)).doesNotContain(apiKey);

        admin(delete("/admin/api-keys/" + ApiKeyHash.of(apiKey)));

        awaitPublished(3);
        assertThat(published().apiKeys()).isEmpty();
    }

    @Test
    void aRefusedChangePublishesNothing() throws Exception {
        assertThat(admin(post("/admin/rules").content(tokenBucket("clash", "/api/text/**"))).getStatus()).isEqualTo(409);

        assertThat(publishedVersionField()).isEqualTo("1");
        assertThat(messages.poll(1200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void anUnchangedConfigurationIsNotPublishedAgain() throws Exception {
        assertThat(publisher.sync()).isEqualTo(SnapshotPublisher.Outcome.UP_TO_DATE);

        assertThat(messages.poll(1500, TimeUnit.MILLISECONDS)).as("no repeated announcements").isNull();
    }

    @Test
    void theVersionEndpointShowsWhatHasBeenPublished() throws Exception {
        admin(post("/admin/rules").content(tokenBucket("search", "/search/**")));
        awaitPublished(2);

        assertThat(admin(get("/admin/version")).getContentAsString()).isEqualTo("{\"version\":2,\"publishedVersion\":2}");
    }

    // ---- repairing ----

    @Test
    void ifRedisLosesEverythingTheConfigurationIsRestoredAndAnnounced() throws Exception {
        redis.sync().flushall(); // what a restart does to a Redis that does not persist

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(redis.sync().exists(SnapshotKeys.KEY)).isEqualTo(1);
            assertThat(published().rules()).hasSize(5);
        });
        assertThat(messages.poll(2, TimeUnit.SECONDS)).isEqualTo("1");
    }

    @Test
    void aDamagedCopyWithTheRightVersionIsStillRepaired() {
        redis.sync().hset(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT, "garbage");

        await().atMost(Duration.ofSeconds(3)).ignoreExceptions()
                .untilAsserted(() -> assertThat(published().rules()).hasSize(5));
    }

    @Test
    void aHandEditedCopyIsOverwrittenByTheDatabase() {
        String looser = redis.sync().hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT)
                .replaceFirst("\"capacity\":100", "\"capacity\":999999");
        redis.sync().hset(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT, looser);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(redis.sync().hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT)).doesNotContain("999999"));
    }

    @Test
    void anOlderPublishCanNeverOverwriteANewerOne() throws Exception {
        admin(post("/admin/rules").content(tokenBucket("search", "/search/**")));
        awaitPublished(2);
        String current = redis.sync().hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT);

        boolean written = store.write(1, "an older snapshot arriving late", false);

        assertThat(written).isFalse();
        assertThat(redis.sync().hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT)).isEqualTo(current);
    }

    @Test
    void ifRedisIsAheadBecauseTheDatabaseWasRestoredTheDatabaseWins() {
        store.write(99, "left over from before the database was restored", false);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(publishedVersionField()).isEqualTo("1");
            assertThat(published().rules()).hasSize(5);
        });
    }

    @Test
    void changesSucceedWhileRedisIsDownAndArePublishedWhenItReturns() throws Exception {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            var response = admin(post("/admin/rules").content(tokenBucket("during-outage", "/outage/**")));

            assertThat(response.getStatus()).isEqualTo(201); // saved: the database is the source of truth
            assertThat(admin(get("/admin/version")).getContentAsString())
                    .isEqualTo("{\"version\":2,\"publishedVersion\":null}");
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(published().rules()).extracting(Rule::name).contains("during-outage"));
        assertThat(published().version()).isEqualTo(2);
    }

    @Test
    void anInvalidStoredRuleKeepsTheLastGoodCopyInPlace() {
        String good = redis.sync().hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT);
        jdbc.sql("UPDATE ratelimit_rules SET params = '{\"capcity\": 1}'::jsonb WHERE name = 'pro-images'").update();

        assertThat(publisher.sync()).isEqualTo(SnapshotPublisher.Outcome.FAILED);
        assertThat(redis.sync().hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT)).isEqualTo(good);

        jdbc.sql("UPDATE ratelimit_rules SET params = '{\"capacity\": 100, \"refill_per_sec\": 20}'::jsonb WHERE name = 'pro-images'")
                .update();
        assertThat(publisher.sync()).isIn(SnapshotPublisher.Outcome.UP_TO_DATE, SnapshotPublisher.Outcome.PUBLISHED);
    }
}
