package com.ratelimit.gateway.ratelimit.sync;

import com.ratelimit.rules.TokenBucketParams;

/** Where a node borrows tokens from: the shared bucket. {@link TokenLeaseClient} is the real one, in Redis. */
public interface LeaseSource {

    /**
     * The answer to one lease call.
     *
     * @param granted         whole tokens now owned by the caller
     * @param sharedRemaining whole tokens left in the shared bucket
     * @param waitMs          0 if the shared bucket can cover the same need again (at least one token),
     *                        otherwise how long until it can
     * @param fullMs          how long until the shared bucket is full if nobody takes anything
     */
    record Lease(long granted, long sharedRemaining, long waitMs, long fullMs) {
    }

    /**
     * One call to the shared bucket of a (rule, subject).
     *
     * <p>A call that fails may or may not have taken effect. The caller must therefore not send the same
     * returned tokens again: crediting them twice could admit more than the limit, while losing them only
     * admits less. For the same reason a grant whose reply was lost is simply lost.
     *
     * @param want      tokens the node would like; raised to {@code need} if lower. 0 with need 0 only returns tokens
     * @param need      tokens a waiting request is short of, or 0 for a top-up
     * @param returned  borrowed tokens the node is giving back unused
     * @param nodeCount gateway nodes currently sharing the bucket, which sets the fair share
     * @throws com.ratelimit.gateway.redis.RedisSkippedException if the call was not made at all, so nothing
     *                          took effect and the same returned tokens may safely go with a later call
     * @throws RuntimeException if the shared bucket cannot be reached or does not answer in time
     */
    Lease lease(long ruleId, String subject, TokenBucketParams params,
            long want, long need, long returned, int nodeCount);
}
