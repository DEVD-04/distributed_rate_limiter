package com.ratelimit.rules;

/**
 * Chooses which single rule applies to a request at a pipeline stage. The static, configuration
 * backed {@link RuleSet} is the first implementation; a Redis-fed, hot-swappable one replaces it
 * when rules become dynamic.
 */
public interface RuleResolver {

    /**
     * @return the winning rule and limiter key; never {@code null}, because every stage has a
     *         built-in default rule that matches any request
     */
    ResolvedRule resolve(RuleContext context);
}
