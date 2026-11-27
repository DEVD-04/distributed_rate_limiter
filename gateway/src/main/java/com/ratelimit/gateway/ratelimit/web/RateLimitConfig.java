package com.ratelimit.gateway.ratelimit.web;

import com.ratelimit.gateway.ratelimit.FailureModeLimiter;
import com.ratelimit.gateway.ratelimit.RateLimiterRouter;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.local.InMemoryRateLimiter;
import com.ratelimit.gateway.ratelimit.local.LocalShareLimiter;
import com.ratelimit.gateway.ratelimit.strict.StrictRedisLimiter;
import com.ratelimit.gateway.ratelimit.sync.LeaseProperties;
import com.ratelimit.gateway.ratelimit.sync.LeaseSettings;
import com.ratelimit.gateway.ratelimit.sync.LocalSyncLimiter;
import com.ratelimit.gateway.ratelimit.sync.NodeRegistry;
import com.ratelimit.gateway.ratelimit.sync.TokenLeaseClient;
import com.ratelimit.gateway.redis.RedisBreaker;
import com.ratelimit.gateway.redis.RedisConnector;
import com.ratelimit.gateway.rules.LiveRuleResolver;
import com.ratelimit.gateway.rules.RulesProperties;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    /**
     * The one {@link RateLimiter} the filters use: a router over the in-memory limiter or, when
     * {@code gateway.redis.url} is set, over the two Redis-backed ones. Fails startup if any configured
     * rule cannot be served by what is available. (Published rules are checked as they are loaded, and
     * refused rather than failing the gateway.)
     */
    @Bean
    RateLimiter rateLimiter(RulesProperties properties, LiveRuleResolver rules, ObjectProvider<RedisConnector> redis,
            ObjectProvider<NodeRegistry> nodes, ObjectProvider<RedisBreaker> breakers,
            LeaseProperties leaseProperties) {
        RedisConnector connector = redis.getIfAvailable();

        List<String> problems = RateLimiterRouter.unservable(rules.configured().rules(), connector != null);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Some rate-limit rules cannot be served:\n - " + String.join("\n - ", problems));
        }

        if (connector == null) {
            log.info("Rate limiting: everything in memory (no gateway.redis.url set)");
            return new RateLimiterRouter(new InMemoryRateLimiter(properties.maxKeys()));
        }
        LeaseSettings lease = leaseProperties.settings();
        RedisBreaker breaker = breakers.getObject(); // one for every call limiting depends on
        NodeRegistry registry = nodes.getObject();
        var waiting = new Semaphore(properties.maxWaiting()); // leaky-bucket requests held, by whichever limiter
        // decides what Redis cannot be used for, for both modes, by the rule's failure_mode
        var onFailure = new FailureModeLimiter(
                new LocalShareLimiter(registry::nodeCount, properties.maxKeys(), waiting), breaker::retryAfter);
        var localSync = new LocalSyncLimiter(
                new TokenLeaseClient(connector::connection, breaker),
                registry::nodeCount,
                onFailure,
                lease,
                properties.maxKeys(),
                task -> Thread.ofVirtual().name("token-refill").start(task),
                System::nanoTime);
        log.info("Rate limiting: STRICT rules in Redis; LOCAL_SYNC rules from tokens borrowed from it "
                        + "(minimum batch {}, horizon {} ms, refill below {})",
                lease.minBatch(), lease.horizon().toMillis(), lease.refillBelow());
        return new RateLimiterRouter(
                new StrictRedisLimiter(connector::connection, waiting, breaker, onFailure), localSync);
    }
}
