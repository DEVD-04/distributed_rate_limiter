package com.ratelimit.gateway.rules;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RulesConfig {

    /** Builds the rule set from configuration; any invalid rule fails startup with its position named. */
    @Bean
    RuleResolver ruleResolver(RulesProperties properties) {
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
            return RuleSet.of(1, active, properties.resolutionCacheSize());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("gateway.ratelimit.rules: " + e.getMessage(), e);
        }
    }
}
