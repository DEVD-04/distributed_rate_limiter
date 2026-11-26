package com.ratelimit.policy.publish;

import com.ratelimit.policy.config.ConfigVersion;
import com.ratelimit.policy.rules.RuleRepository;
import com.ratelimit.rules.snapshot.ConfigSnapshot;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads the whole active configuration from the database as of one instant. */
@Component
public class SnapshotReader {

    private final ConfigVersion version;
    private final RuleRepository rules;
    private final JdbcClient jdbc;

    public SnapshotReader(ConfigVersion version, RuleRepository rules, JdbcClient jdbc) {
        this.version = version;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    /**
     * Version, rules and keys are read in one REPEATABLE READ transaction, so they all describe the same
     * state: with the default isolation a change committing between the queries could give a version
     * that does not match the rules. Always a new transaction, because publishing runs just after a
     * change has committed.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public ConfigSnapshot read() {
        long current = version.current();
        List<ConfigSnapshot.ApiKeyEntry> keys = jdbc
                .sql("SELECT key_hash, user_id, tier FROM api_keys WHERE is_active ORDER BY key_hash")
                .query((row, n) -> new ConfigSnapshot.ApiKeyEntry(
                        row.getString("key_hash"), row.getString("user_id"), row.getString("tier")))
                .list();
        return new ConfigSnapshot(current, rules.findActive(), keys);
    }
}
