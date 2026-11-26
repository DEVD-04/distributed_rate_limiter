package com.ratelimit.rules;

import com.google.common.net.InetAddresses;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One rate-limit rule, mirroring a row of the {@code ratelimit_rules} table. Construction
 * validates everything that can be checked in isolation, so an invalid rule cannot exist.
 *
 * @param ruleId          stable identifier; limiter state is keyed by it
 * @param name            human-readable policy name (shown in the {@code X-RateLimit-Policy} header)
 * @param limitBy         what requests are counted by
 * @param matchTier       only applies to this tier, or {@code null} for any tier
 * @param matchSubject    only applies to this user / key / IP, or {@code null} for any; IP subjects
 *                        must be written in canonical form
 * @param endpointPattern Ant-style path pattern, e.g. {@code /api/images/**}
 * @param httpMethod      only applies to this method, or {@code null} for any
 * @param algorithm       which limiting algorithm to use
 * @param mode            how state is shared between nodes
 * @param failureMode     behaviour when the shared store is unavailable
 * @param params          algorithm parameters; must match {@code algorithm}
 * @param priority        tie-breaker when several rules are equally specific (higher wins)
 * @param version         bumped on every change of the rule
 */
public record Rule(
        long ruleId,
        String name,
        LimitBy limitBy,
        String matchTier,
        String matchSubject,
        String endpointPattern,
        String httpMethod,
        Algorithm algorithm,
        Mode mode,
        FailureMode failureMode,
        Params params,
        int priority,
        long version) {

    private static final Set<String> HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    public Rule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(limitBy, "limitBy");
        Objects.requireNonNull(endpointPattern, "endpointPattern");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(failureMode, "failureMode");
        Objects.requireNonNull(params, "params");

        if (name.isBlank()) {
            throw new IllegalArgumentException("rule name must not be blank");
        }
        if (!endpointPattern.startsWith("/")) {
            throw new IllegalArgumentException("endpoint pattern must start with '/', was '" + endpointPattern + "'");
        }
        if (matchTier != null && matchTier.isBlank()) {
            throw new IllegalArgumentException("match tier must be null or non-blank");
        }
        if (matchSubject != null && matchSubject.isBlank()) {
            throw new IllegalArgumentException("match subject must be null or non-blank");
        }
        if (limitBy == LimitBy.IP && matchSubject != null) {
            if (!InetAddresses.isInetAddress(matchSubject)
                    || !InetAddresses.toAddrString(InetAddresses.forString(matchSubject)).equals(matchSubject)) {
                throw new IllegalArgumentException(
                        "IP subject must be a canonical IP address (e.g. 2001:db8::1), was '" + matchSubject + "'");
            }
        }
        if (httpMethod != null) {
            httpMethod = httpMethod.toUpperCase(Locale.ROOT);
            if (!HTTP_METHODS.contains(httpMethod)) {
                throw new IllegalArgumentException("unsupported HTTP method '" + httpMethod + "'");
            }
        }

        validateCombination(algorithm, mode);
        boolean paramsFit = switch (algorithm) {
            case TOKEN_BUCKET -> params instanceof TokenBucketParams;
            case SLIDING_WINDOW_LOG -> params instanceof SlidingWindowParams;
            case LEAKY_BUCKET_QUEUE -> params instanceof LeakyBucketParams;
        };
        if (!paramsFit) {
            throw new IllegalArgumentException(
                    "params " + params.getClass().getSimpleName() + " do not fit algorithm " + algorithm);
        }
    }

    /**
     * Which algorithm / mode pairs are allowed. A sliding window log keeps one entry per request,
     * which cannot be split into local leases, so it must be STRICT.
     */
    public static void validateCombination(Algorithm algorithm, Mode mode) {
        if (algorithm == Algorithm.SLIDING_WINDOW_LOG && mode == Mode.LOCAL_SYNC) {
            throw new IllegalArgumentException("SLIDING_WINDOW_LOG supports only mode STRICT");
        }
    }
}
