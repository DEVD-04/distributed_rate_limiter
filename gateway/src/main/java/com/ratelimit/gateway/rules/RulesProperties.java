package com.ratelimit.gateway.rules;

import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.FailureMode;
import com.ratelimit.rules.LimitBy;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.ParamsFactory;
import com.ratelimit.rules.Rule;
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
 * @param maxWaiting           how many leaky-bucket requests one node may hold waiting at once; more are
 *                            turned away immediately
 * @param snapshotFile         where the gateway saves the last published rules it applied, used at startup
 *                             if Redis is unavailable; blank turns it off. Only used with Redis.
 */
@ConfigurationProperties(prefix = "gateway.ratelimit")
public record RulesProperties(
        @DefaultValue List<RuleEntry> rules,
        @DefaultValue("10000") long resolutionCacheSize,
        @DefaultValue("100000") long maxKeys,
        @DefaultValue("10000") int maxWaiting,
        @DefaultValue("data/rules-snapshot.json") String snapshotFile) {

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
