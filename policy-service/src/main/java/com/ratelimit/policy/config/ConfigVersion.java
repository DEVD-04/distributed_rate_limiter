package com.ratelimit.policy.config;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The single version number of the whole configuration. Every change locks it first and bumps it last,
 * inside one transaction, which makes changes happen one at a time: a change is validated against a
 * rule set nobody else is altering, and versions are handed out in commit order.
 */
@Repository
public class ConfigVersion {

    private final JdbcClient jdbc;

    public ConfigVersion(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long current() {
        return jdbc.sql("SELECT version FROM config_version").query(Long.class).single();
    }

    /** Waits for any other change in progress; the lock is held until the transaction ends. */
    public void lockForChange() {
        jdbc.sql("SELECT version FROM config_version FOR UPDATE").query(Long.class).single();
    }

    public long bump() {
        return jdbc.sql("UPDATE config_version SET version = version + 1 RETURNING version").query(Long.class).single();
    }
}
