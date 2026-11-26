-- Starting configuration. Rule ids are left to the sequence so later inserts cannot collide.

INSERT INTO tiers (name, description) VALUES
    ('free', 'Free plan'),
    ('pro', 'Paid plan'),
    ('enterprise', 'Enterprise plan');

-- A default rule per pipeline stage, so unmatched traffic is limited and the limits are visible and
-- editable. (If one is deactivated, the gateway's built-in default with the same values applies.)
INSERT INTO ratelimit_rules (name, limit_by, endpoint_pattern, algorithm, params, priority) VALUES
    ('default-ip',   'IP',   '/**', 'TOKEN_BUCKET', '{"capacity": 100, "refill_per_sec": 50}', -1000),
    ('default-user', 'USER', '/**', 'TOKEN_BUCKET', '{"capacity": 100, "refill_per_sec": 20}', -1000);

INSERT INTO ratelimit_rules (name, limit_by, match_tier, endpoint_pattern, algorithm, params) VALUES
    ('free-images',     'USER', 'free', '/api/images/**', 'TOKEN_BUCKET', '{"capacity": 10, "refill_per_sec": 1}'),
    ('pro-images',      'USER', 'pro',  '/api/images/**', 'TOKEN_BUCKET', '{"capacity": 100, "refill_per_sec": 20}'),
    ('text-generation', 'USER', NULL,   '/api/text/**',   'TOKEN_BUCKET', '{"capacity": 30, "refill_per_sec": 5}');
