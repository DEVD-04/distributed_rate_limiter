package com.ratelimit.gateway.rules;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Static rules from configuration ({@code gateway.ratelimit.*}), in the shape of the
 * {@code ratelimit_rules} table. They stand in until rules come from the policy service.
 *
 * @param rules               the rules; entries with {@code active: false} are ignored
 * @param resolutionCacheSize bound on the memoized rule lookups
 * @param maxKeys             bound on the number of limiter buckets held in memory; the oldest idle
 *                            ones go first when it is exceeded
 */
@ConfigurationProperties(prefix = "gateway.ratelimit")
public record RulesProperties(
        @DefaultValue List<RuleEntry> rules,
        @DefaultValue("10000") long resolutionCacheSize,
        @DefaultValue("100000") long maxKeys) {

    /** One configured rule. Blank {@code matchTier} / {@code matchSubject} / {@code httpMethod} mean "any". */
    public record RuleEntry(
            long id,
            String name,
            LimitBy limitBy,
            String matchTier,
            String matchSubject,
            String endpointPattern,
            String httpMethod,
            Algorithm algorithm,
            @DefaultValue("STRICT") Mode mode,
            @DefaultValue("LOCAL_FALLBACK") FailureMode failureMode,
            @DefaultValue Map<String, String> params,
            @DefaultValue("0") int priority,
            @DefaultValue("true") boolean active,
            @DefaultValue("1") long version) {

        Rule toRule() {
            return new Rule(id, name, limitBy, blankToNull(matchTier), blankToNull(matchSubject),
                    endpointPattern, blankToNull(httpMethod), algorithm, mode, failureMode,
                    ParamsFactory.parse(algorithm, params), priority, version);
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }
}
