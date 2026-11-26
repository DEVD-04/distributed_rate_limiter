package com.ratelimit.policy.rules;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TierRepository {

    private final JdbcClient jdbc;

    public TierRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws IllegalArgumentException naming the known tiers if {@code tier} is not one of them */
    public void requireKnown(String tier) {
        List<String> known = jdbc.sql("SELECT name FROM tiers ORDER BY name").query(String.class).list();
        if (!known.contains(tier)) {
            throw new IllegalArgumentException("unknown tier '" + tier + "' (known: " + String.join(", ", known) + ")");
        }
    }
}
