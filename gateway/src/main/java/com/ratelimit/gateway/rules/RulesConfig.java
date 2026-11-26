package com.ratelimit.gateway.rules;

import com.ratelimit.gateway.redis.RedisConnector;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.RuleSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RulesConfig {

    /**
     * The rules in force. They start as the ones in configuration, which must be valid even if published
     * rules will replace them; any invalid one fails startup with its position named.
     */
    @Bean
    LiveRuleResolver ruleResolver(RulesProperties properties) {
        List<Rule> active = new ArrayList<>();
        List<RulesProperties.RuleEntry> entries = properties.rules();
        for (int i = 0; i < entries.size(); i++) {
            RulesProperties.RuleEntry entry = entries.get(i);
            if (!entry.active()) {
                continue;
            }
            try {
                active.add(entry.toRule());
            } catch (RuntimeException e) {
                throw new IllegalStateException("gateway.ratelimit.rules[" + i + "] (" + entry.name() + "): " + e.getMessage(), e);
            }
        }
        try {
            RuleSet configured = RuleSet.of(1, active, properties.resolutionCacheSize());
            return new LiveRuleResolver(configured, properties.resolutionCacheSize());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("gateway.ratelimit.rules: " + e.getMessage(), e);
        }
    }

    /** Loads published rules while the application is starting, before it accepts requests. */
    @Bean(initMethod = "loadAtStartup")
    RuleSnapshotLoader ruleSnapshotLoader(ObjectProvider<RedisConnector> redis, LiveRuleResolver resolver,
            RulesProperties properties) {
        return new RuleSnapshotLoader(Optional.ofNullable(redis.getIfAvailable()), resolver,
                new RuleSnapshotFile(properties.snapshotFile()));
    }
}
