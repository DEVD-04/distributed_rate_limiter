package com.ratelimit.gateway.ratelimit.api;

import com.ratelimit.gateway.rules.Rule;

public interface RateLimiter {

    /**
     * Charges one request against the limit {@code rule} defines for {@code key}.
     *
     * <p>May block (leaky bucket queue) for up to the rule's maximum wait; callers run on virtual
     * threads, so that is cheap.
     *
     * @param key  the subject being limited (user id, API key hash or IP): never logged or used as
     *             a metric label, since it is unbounded
     * @param cost how many units this request counts as (1 for an ordinary request)
     */
    Decision acquire(Rule rule, String key, int cost);
}
