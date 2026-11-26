package com.ratelimit.gateway.rules;

import com.ratelimit.gateway.ratelimit.RateLimiterRouter;
import com.ratelimit.rules.ResolvedRule;
import com.ratelimit.rules.RuleContext;
import com.ratelimit.rules.RuleResolver;
import com.ratelimit.rules.RuleSet;
import com.ratelimit.rules.snapshot.ConfigSnapshot;
import com.ratelimit.rules.snapshot.SnapshotCodec;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rules in force, which can be replaced while requests are being served.
 *
 * <p>A new set is decoded, validated and built completely before anything changes, and then replaces the
 * old one in a single reference write. A request therefore always sees one whole set, and a snapshot with
 * anything wrong in it changes nothing: the rules already in force stay. Each set has its own lookup
 * cache, so no answer from old rules survives a swap.
 *
 * <p>Limiter state is not touched by a swap. It is keyed by rule id, and limiters receive a rule's
 * parameters on every call, so a changed limit applies to what a user has already consumed.
 */
public class LiveRuleResolver implements RuleResolver {

    /** Where the rules in force came from. */
    public enum Source {
        /** {@code application.yml} and the built-in defaults. */
        CONFIGURATION,
        REDIS,
        /** The copy of the last snapshot saved by this gateway. */
        DISK
    }

    public enum Outcome {
        APPLIED,
        /** Identical to the rules already in force. */
        UNCHANGED,
        /** Invalid, or not something this gateway can enforce; the rules in force are unchanged. */
        REJECTED
    }

    private static final Logger log = LoggerFactory.getLogger(LiveRuleResolver.class);

    private record Active(RuleSet rules, Source source, String snapshot) {
    }

    private final RuleSet configured;
    private final long resolutionCacheSize;
    private volatile Active active;

    /** @param configured the rules from configuration, in force until a snapshot replaces them */
    public LiveRuleResolver(RuleSet configured, long resolutionCacheSize) {
        this.configured = configured;
        this.resolutionCacheSize = resolutionCacheSize;
        this.active = new Active(configured, Source.CONFIGURATION, null);
    }

    @Override
    public ResolvedRule resolve(RuleContext context) {
        return active.rules().resolve(context);
    }

    /** The rule set in force. */
    public RuleSet current() {
        return active.rules();
    }

    /** The rule set built from configuration, whether or not it is still in force. */
    public RuleSet configured() {
        return configured;
    }

    public Source source() {
        return active.source();
    }

    /**
     * Puts a published snapshot into force. Any version is accepted, including a lower one than the
     * current: the publisher's copy follows its database, which may have been restored.
     *
     * <p>Only meaningful when Redis is configured, since published rules may need it.
     *
     * @param text the snapshot as published
     */
    public synchronized Outcome apply(String text, Source source) {
        Active current = active;
        if (text.equals(current.snapshot())) {
            active = new Active(current.rules(), source, text);
            return Outcome.UNCHANGED;
        }
        RuleSet next;
        try {
            ConfigSnapshot snapshot = SnapshotCodec.decode(text);
            List<String> problems = RateLimiterRouter.unservable(snapshot.rules(), true);
            if (!problems.isEmpty()) {
                log.error("Rejected rules version {} from {}; keeping the current rules. This gateway cannot enforce: {}",
                        snapshot.version(), source, String.join("; ", problems));
                return Outcome.REJECTED;
            }
            next = RuleSet.of(snapshot.version(), snapshot.rules(), resolutionCacheSize);
        } catch (IllegalArgumentException e) {
            log.error("Rejected an invalid rules snapshot from {}; keeping the current rules: {}", source, e.getMessage());
            return Outcome.REJECTED;
        }
        active = new Active(next, source, text);
        log.info("Rules version {} from {} are in force ({} rules)", next.version(), source, next.rules().size());
        return Outcome.APPLIED;
    }
}
