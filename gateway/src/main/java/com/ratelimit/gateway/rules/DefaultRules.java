package com.ratelimit.gateway.rules;

import java.util.List;

/**
 * Built-in catch-all rules, one per stage, so unmatched traffic is never unlimited. They use
 * reserved negative ids and lose to any rule an operator defines for the same scope.
 */
final class DefaultRules {

    static final long IP_RULE_ID = -1;
    static final long USER_RULE_ID = -2;

    private DefaultRules() {
    }

    static List<Rule> all() {
        return List.of(
                new Rule(IP_RULE_ID, "default-ip", LimitBy.IP, null, null, "/**", null,
                        Algorithm.TOKEN_BUCKET, Mode.STRICT, FailureMode.LOCAL_FALLBACK,
                        new TokenBucketParams(100, 50), Integer.MIN_VALUE, 1),
                new Rule(USER_RULE_ID, "default-user", LimitBy.USER, null, null, "/**", null,
                        Algorithm.TOKEN_BUCKET, Mode.STRICT, FailureMode.LOCAL_FALLBACK,
                        new TokenBucketParams(100, 20), Integer.MIN_VALUE, 1));
    }
}
