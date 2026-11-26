-- Source of truth for rate-limit configuration. There is deliberately no counter table: counters live
-- only in Redis (STRICT) or in gateway memory (LOCAL_SYNC).

CREATE TABLE tiers (
    name        text PRIMARY KEY CHECK (length(trim(name)) > 0),
    description text
);

CREATE TABLE api_keys (
    key_hash  text PRIMARY KEY CHECK (key_hash ~ '^[0-9a-f]{64}$'),  -- SHA-256 of the key; the key is never stored
    user_id   text NOT NULL CHECK (length(trim(user_id)) > 0),
    tier      text NOT NULL REFERENCES tiers (name),
    is_active boolean NOT NULL DEFAULT true
);

-- The checks below are a second line of defence. The admin API validates the same things first, with
-- the same code the gateway uses, which also covers what SQL cannot (per-algorithm params, path syntax).
CREATE TABLE ratelimit_rules (
    rule_id          bigserial PRIMARY KEY,
    name             text NOT NULL CHECK (length(trim(name)) > 0),
    limit_by         text NOT NULL CHECK (limit_by IN ('USER', 'API_KEY', 'IP')),
    match_tier       text REFERENCES tiers (name),                  -- null = any tier
    match_subject    text CHECK (length(trim(match_subject)) > 0),  -- null = any subject
    endpoint_pattern text NOT NULL CHECK (endpoint_pattern LIKE '/%'),
    http_method      text CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS')),
    algorithm        text NOT NULL CHECK (algorithm IN ('TOKEN_BUCKET', 'LEAKY_BUCKET_QUEUE', 'SLIDING_WINDOW_LOG')),
    mode             text NOT NULL DEFAULT 'STRICT' CHECK (mode IN ('STRICT', 'LOCAL_SYNC')),
    failure_mode     text NOT NULL DEFAULT 'LOCAL_FALLBACK'
                         CHECK (failure_mode IN ('FAIL_OPEN', 'FAIL_CLOSED', 'LOCAL_FALLBACK')),
    params           jsonb NOT NULL CHECK (jsonb_typeof(params) = 'object'),
    priority         int NOT NULL DEFAULT 0,
    is_active        boolean NOT NULL DEFAULT true,
    version          bigint NOT NULL DEFAULT 1,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT sliding_window_is_strict CHECK (NOT (algorithm = 'SLIDING_WINDOW_LOG' AND mode = 'LOCAL_SYNC'))
);

-- At most one active rule per scope; deactivated rules may repeat a scope.
CREATE UNIQUE INDEX uq_rule_scope ON ratelimit_rules
    (limit_by, coalesce(match_tier, ''), coalesce(match_subject, ''), endpoint_pattern, coalesce(http_method, ''))
    WHERE is_active;

-- One number for the whole configuration (rules and API keys), increased by every change. Gateways use
-- it to tell whether the snapshot they hold is current.
CREATE TABLE config_version (
    id      boolean PRIMARY KEY DEFAULT true CHECK (id),  -- allows exactly one row
    version bigint NOT NULL
);
INSERT INTO config_version (version) VALUES (1);
