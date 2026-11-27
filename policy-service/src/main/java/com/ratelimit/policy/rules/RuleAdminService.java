package com.ratelimit.policy.rules;

import com.ratelimit.policy.admin.NotFoundException;
import com.ratelimit.policy.config.ConfigVersion;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.RuleSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes to rules. Each change runs in one transaction: lock the configuration, write the row, build
 * the complete active rule set exactly as a gateway would, and only then bump the version. If anything
 * fails, nothing is saved. So the database never holds a set that gateways could not load.
 */
@Service
public class RuleAdminService {

    private final RuleRepository rules;
    private final TierRepository tiers;
    private final ConfigVersion version;

    public RuleAdminService(RuleRepository rules, TierRepository tiers, ConfigVersion version) {
        this.rules = rules;
        this.tiers = tiers;
        this.version = version;
    }

    public List<RuleView> list() {
        return rules.findAll();
    }

    public RuleView get(long ruleId) {
        return rules.findById(ruleId).orElseThrow(() -> new NotFoundException("No rule with id " + ruleId + "."));
    }

    @Transactional
    public RuleView create(RuleRequest request) {
        Rule rule = validated(request, 1);
        version.lockForChange();
        checkReferences(rule);
        long id = rules.insert(rule, request.activeOrDefault());
        commitChange();
        return get(id);
    }

    @Transactional
    public RuleView update(long ruleId, RuleRequest request) {
        Rule rule = validated(request, ruleId);
        version.lockForChange();
        get(ruleId);
        checkReferences(rule);
        rules.update(ruleId, rule, request.activeOrDefault());
        commitChange();
        return get(ruleId);
    }

    /** Rules are never deleted, only deactivated. Deactivating twice changes nothing. */
    @Transactional
    public void deactivate(long ruleId) {
        version.lockForChange();
        if (get(ruleId).active()) {
            rules.deactivate(ruleId);
            commitChange();
        }
    }

    /** Everything that can be checked about the rule on its own, including its path pattern. */
    private static Rule validated(RuleRequest request, long ruleId) {
        Rule rule = request.toRule(Math.max(1, ruleId));
        RuleSet.of(1, List.of(rule)); // parses the pattern, even for a rule saved as inactive
        if (rule.mode() == Mode.LOCAL_SYNC && !Rule.servableInMemory(rule.algorithm())) {
            throw new IllegalArgumentException("mode LOCAL_SYNC is not available for " + rule.algorithm()
                    + " (token bucket only); use mode STRICT");
        }
        return rule;
    }

    private void checkReferences(Rule rule) {
        if (rule.matchTier() != null) {
            tiers.requireKnown(rule.matchTier());
        }
    }

    private void commitChange() {
        RuleSet.of(1, rules.findActive()); // the whole set, as a gateway will build it
        version.bump();
    }
}
