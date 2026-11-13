package com.ratelimit.gateway.rules;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * An immutable, validated collection of active rules that picks the winner for a request.
 *
 * <p>Among rules that apply to the request (right stage, a subject of the right kind, matching
 * tier, method and path) the winner is the first by this order:
 * <ol>
 *   <li>subject-specific over generic,
 *   <li>tier-specific over generic,
 *   <li>more specific path (exact over {@code /a/*} over {@code /a/**} over {@code /**}, using
 *       Spring's {@link PathPattern#SPECIFICITY_COMPARATOR}),
 *   <li>method-specific over any method,
 *   <li>higher {@code priority},
 *   <li>lower rule id, so the choice is always deterministic.
 * </ol>
 *
 * <p>That order does not depend on <em>who</em> is calling, so the sorted list of candidates is
 * cached by (stage, tier, method, path, available subject kinds) and shared by all users; the
 * per-request work is a scan of that short list for the first rule whose subject matches. An
 * attacker spraying distinct IPs or users therefore cannot grow the cache; only distinct paths
 * can, and the cache is size-bounded.
 *
 * <p>Built-in default rules guarantee that every stage has a match. A set is never modified: rule
 * changes are applied by swapping in a new {@code RuleSet}, which also carries a fresh cache.
 */
public final class RuleSet implements RuleResolver {

    private record Compiled(Rule rule, PathPattern pattern) {
    }

    private record CandidateKey(Stage stage, String tier, String method, String path, int availableSubjects) {
    }

    private record Scope(LimitBy limitBy, String tier, String subject, String pattern, String method) {
        static Scope of(Rule r) {
            return new Scope(r.limitBy(), r.matchTier(), r.matchSubject(), r.endpointPattern(), r.httpMethod());
        }
    }

    private static final Comparator<Compiled> PRECEDENCE = Comparator
            .comparing((Compiled c) -> c.rule().matchSubject() == null)   // false (specific) sorts first
            .thenComparing(c -> c.rule().matchTier() == null)
            .thenComparing(Compiled::pattern, PathPattern.SPECIFICITY_COMPARATOR)
            .thenComparing(c -> c.rule().httpMethod() == null)
            .thenComparing(Comparator.comparingInt((Compiled c) -> c.rule().priority()).reversed())
            .thenComparingLong(c -> c.rule().ruleId());

    public static final long DEFAULT_RESOLUTION_CACHE_SIZE = 10_000;

    private final long version;
    private final List<Rule> rules;
    private final Map<Stage, List<Compiled>> byStage = new EnumMap<>(Stage.class);
    private final Cache<CandidateKey, List<Compiled>> candidates;

    private RuleSet(long version, List<Rule> rules, long cacheSize) {
        this.version = version;
        this.rules = List.copyOf(rules);
        this.candidates = Caffeine.newBuilder().maximumSize(cacheSize).build();

        PathPatternParser parser = new PathPatternParser();
        for (Stage stage : Stage.values()) {
            byStage.put(stage, new ArrayList<>());
        }
        for (Rule rule : rules) {
            PathPattern pattern;
            try {
                pattern = parser.parse(rule.endpointPattern());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("rule " + rule.ruleId() + " (" + rule.name()
                        + "): invalid endpoint pattern '" + rule.endpointPattern() + "': " + e.getMessage(), e);
            }
            byStage.get(rule.limitBy().stage()).add(new Compiled(rule, pattern));
        }
    }

    /** Creates a set from operator-defined rules plus the built-in defaults. */
    public static RuleSet of(long version, List<Rule> configured) {
        return of(version, configured, DEFAULT_RESOLUTION_CACHE_SIZE);
    }

    /**
     * @param configured the active, operator-defined rules; ids must be positive and unique, and no
     *                   two rules may share a scope (same subject kind, tier, subject, path and method)
     * @throws IllegalArgumentException if the rules are inconsistent
     */
    public static RuleSet of(long version, List<Rule> configured, long resolutionCacheSize) {
        Set<Long> ids = new HashSet<>();
        Set<Scope> scopes = new HashSet<>();
        for (Rule rule : configured) {
            if (rule.ruleId() <= 0) {
                throw new IllegalArgumentException(
                        "rule '" + rule.name() + "': ids must be positive (negative ids are reserved), was " + rule.ruleId());
            }
            if (!ids.add(rule.ruleId())) {
                throw new IllegalArgumentException("duplicate rule id " + rule.ruleId());
            }
            if (!scopes.add(Scope.of(rule))) {
                throw new IllegalArgumentException("rule " + rule.ruleId() + " (" + rule.name()
                        + ") has the same scope as another active rule: " + Scope.of(rule));
            }
        }
        List<Rule> all = new ArrayList<>(configured);
        for (Rule builtIn : DefaultRules.all()) {
            if (!scopes.contains(Scope.of(builtIn))) { // an operator-defined catch-all replaces the built-in
                all.add(builtIn);
            }
        }
        return new RuleSet(version, all, resolutionCacheSize);
    }

    public long version() {
        return version;
    }

    /** All rules in this set, including any built-in defaults in force. */
    public List<Rule> rules() {
        return rules;
    }

    @Override
    public ResolvedRule resolve(RuleContext context) {
        CandidateKey key = new CandidateKey(context.stage(), context.tier(), context.method(),
                context.path(), context.availableSubjects());
        List<Compiled> ordered = candidates.get(key, k -> candidatesFor(context));

        for (Compiled candidate : ordered) {
            Rule rule = candidate.rule();
            String subject = context.subjectFor(rule.limitBy());
            if (rule.matchSubject() == null || rule.matchSubject().equals(subject)) {
                return new ResolvedRule(rule, subject);
            }
        }
        // Unreachable while the built-in defaults are present; fail loudly rather than skip limiting.
        throw new IllegalStateException("no rule matched " + context.stage() + " " + context.method() + " " + context.path());
    }

    /** Rules that could apply to requests like this one, ignoring the subject value, best first. */
    private List<Compiled> candidatesFor(RuleContext context) {
        PathContainer path = PathContainer.parsePath(context.path());
        return byStage.get(context.stage()).stream()
                .filter(c -> context.subjectFor(c.rule().limitBy()) != null)
                .filter(c -> c.rule().matchTier() == null || c.rule().matchTier().equals(context.tier()))
                .filter(c -> c.rule().httpMethod() == null || c.rule().httpMethod().equals(context.method()))
                .filter(c -> c.pattern().matches(path))
                .sorted(PRECEDENCE)
                .toList();
    }

    /** Number of cached candidate lists (approximate). Mainly for tests. */
    long cachedCandidateLists() {
        candidates.cleanUp();
        return candidates.estimatedSize();
    }
}
