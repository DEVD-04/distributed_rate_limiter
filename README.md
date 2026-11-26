# Distributed Rate Limiter & API Gateway

A Spring Boot API gateway that authenticates requests (RS256 JWT or API key), rate-limits them per
user / API key / IP with per-endpoint, per-tier rules, and proxies them to downstream services. Limits
can be exact across several gateway nodes (STRICT mode, Redis + Lua) or local to a node.

The full design is in `Distributed Rate Limiter & API Gateway Design Document.docx`. This file
records what is built, how to run it, and where the implementation departs from or adds to the design.

## Status

| Phase | Scope | State |
|---|---|---|
| **1 Foundation** | Gateway skeleton, mock services, RS256 + API-key auth, routing, in-memory token bucket behind `RateLimiter`, rule matching, 429 contract | **Done** |
| **2 STRICT mode** | Lettuce, Lua scripts for all three algorithms, exact limits across nodes, router, atomicity proof | **Done** (335 tests; live 3-node run passed) |
| 3 Rules | Flyway schema, policy service, Redis snapshot, pub/sub, hot reload | In progress: steps 1-7 of 8 done (`rules-core`, database, admin API, publishing to Redis, gateways load rules and API keys and follow changes) |
| 4 LOCAL_SYNC | Token leasing, membership, bound and skew tests | Not started |
| 5 Resilience | Circuit breaker, failure modes, fallback limiter | Not started |
| 6 Observability | Micrometer, `/metrics`, Prometheus, Grafana | Not started |
| 7 Hardening | Load tests, tuning, docs | Not started |

## Layout

| Module | Port | Purpose |
|---|---|---|
| `rules-core` | library | Rule model, parameter validation and precedence matching (`com.ratelimit.rules`), shared so the policy service validates exactly what the gateway applies |
| `gateway` | 8080 | Filters, auth, rate limiting, proxy |
| `mock-service` | 8081… | Downstream stand-in with configurable latency / error rate |
| `policy-service` | 8090 | Admin API for rules and API keys, backed by PostgreSQL (Flyway); publishes the configuration to Redis |

Gateway request pipeline (servlet filters, in order):
`RequestIdFilter` → `RequestGuardFilter` → `ClientIpFilter` → `PreAuthRateLimitFilter` (by IP) →
`AuthFilter` → `PostAuthRateLimitFilter` (by user / key) → `ProxyController`.
(`RequestGuardFilter` runs right after the request id and before rate limiting, so unsafe paths cost no tokens.)

Rate limiting inside the gateway: `RateLimiterRouter` picks a limiter per rule.

| | In memory (`InMemoryRateLimiter`) | Redis (`StrictRedisLimiter`) |
|---|---|---|
| Used for | rules with `mode: LOCAL_SYNC`, and everything when no Redis is configured | rules with `mode: STRICT` (the default) when `gateway.redis.url` is set |
| Algorithms | token bucket | token bucket, sliding window log, leaky bucket (queue) |
| Across nodes | each node counts alone: N nodes admit up to N × the limit | exact: one atomic Lua script per decision, time from Redis `TIME` |

## Build and test

Gradle 9.9 (wrapper included), Java 21 via a Gradle toolchain (downloaded on first build; the machine's own JDK is only used to run Gradle).
**Docker must be running**: tests start `redis:7-alpine` and `postgres:17-alpine` containers through Testcontainers.

```
./gradlew build            # compile + all tests (needs Docker)
./gradlew :gateway:test
```

Latency benchmarks are opt-in, and write their results to `gateway/build/benchmarks/`:

```
RUN_BENCHMARKS=1 ./gradlew :gateway:test --tests '*LimiterLatencyBenchmark'
```

## Run locally

```
./gradlew :gateway:bootJar :mock-service:bootJar
# three mock instances: two "image", one "text" (ports match gateway.proxy.routes in application.yml)
java -jar mock-service/build/libs/mock-service-0.1.0-SNAPSHOT.jar --server.port=8081
java -jar mock-service/build/libs/mock-service-0.1.0-SNAPSHOT.jar --server.port=8082
java -jar mock-service/build/libs/mock-service-0.1.0-SNAPSHOT.jar --server.port=8083
# optional: Redis, so STRICT rules are exact across several gateways (no persistence, as in the design)
docker run -d --name rl-redis -p 6379:6379 redis:7-alpine redis-server --save "" --appendonly no --maxmemory-policy volatile-ttl
# gateway with one API key (config holds only the SHA-256 of the key)
KEY_HASH=$(printf 'my-secret-key' | sha256sum | cut -d' ' -f1)
java -jar gateway/build/libs/gateway-0.1.0-SNAPSHOT.jar \
  --gateway.redis.url=redis://localhost:6379 \
  --gateway.auth.api-keys[0].key-hash=$KEY_HASH \
  --gateway.auth.api-keys[0].user-id=demo --gateway.auth.api-keys[0].tier=free
curl -i -H 'X-API-Key: my-secret-key' http://localhost:8080/api/images/cat.png
```

Leave out `--gateway.redis.url` to run everything in memory. To run several gateways against the same
Redis, give each its own `--server.port`.

Mock-service knobs (environment variables): `MOCK_LATENCY_MS`, `MOCK_LATENCY_JITTER_MS`, `MOCK_ERROR_RATE`, `MOCK_ERROR_STATUS`, `MOCK_INSTANCE_ID`.

JWT auth needs a public key in `gateway.auth.jwt.public-keys.<kid>` plus `issuer` and `audience`. There is
no runnable token issuer yet: tokens are minted only by the test helper `TokenFactory`. Add a small
dev issuer before load testing.

### Policy service and its database

The policy service needs PostgreSQL. Flyway creates the schema and seeds it at startup (three tiers, the
two per-stage default rules and the three sample rules). It publishes to Redis, and a gateway with
`gateway.redis.url` set loads the published rules when it starts and follows later changes (see below).

```
docker run -d --name rl-postgres -p 5432:5432 -e POSTGRES_DB=ratelimit -e POSTGRES_USER=ratelimit \
  -e POSTGRES_PASSWORD=local-dev-only postgres:17-alpine
./gradlew :policy-service:bootJar
export POLICY_DB_PASSWORD=local-dev-only
export POLICY_ADMIN_TOKEN=$(openssl rand -hex 24)    # required, 16+ characters
java -jar policy-service/build/libs/policy-service-0.1.0-SNAPSHOT.jar
```

Settings: `POLICY_DB_URL` (default `jdbc:postgresql://localhost:5432/ratelimit`), `POLICY_DB_USER` (default
`ratelimit`), `POLICY_DB_PASSWORD` and `POLICY_ADMIN_TOKEN` (no defaults; the service will not start without a token),
`POLICY_REDIS_URL` (default `redis://localhost:6379`; empty turns publishing off).

#### Admin API

Every request needs `X-Admin-Token: <token>`, whatever the path. There is no TLS yet, so keep port 8090 off
untrusted networks.

| Request | Effect |
|---|---|
| `GET /admin/rules`, `GET /admin/rules/{id}` | All rules, including deactivated ones |
| `POST /admin/rules` | Create (201). Body: `name`, `limitBy`, `endpointPattern`, `algorithm`, `params`; optional `matchTier`, `matchSubject`, `httpMethod`, `mode`, `failureMode`, `priority`, `active` |
| `PUT /admin/rules/{id}` | **Replace** the whole rule: omitted optional fields return to their defaults |
| `DELETE /admin/rules/{id}` | Deactivate (rules are never deleted) |
| `POST /admin/api-keys` | Body `userId`, `tier`. The key is generated and shown **once**; only its SHA-256 is stored |
| `GET /admin/api-keys`, `DELETE /admin/api-keys/{keyHash}` | List (hashes only), revoke |
| `GET /admin/version` | `version` (every change that saves anything raises it by one) and `publishedVersion` (what Redis holds; null if it cannot be reached) |

```
curl -H "X-Admin-Token: $POLICY_ADMIN_TOKEN" -H 'Content-Type: application/json' http://localhost:8090/admin/rules \
  -d '{"name":"search","limitBy":"USER","endpointPattern":"/api/search/**","algorithm":"TOKEN_BUCKET","params":{"capacity":7,"refill_per_sec":0.5}}'
```

A change is applied in one transaction: it locks the configuration, writes the row, builds the complete active
rule set with the same code a gateway uses, and only then raises the version. Errors: 400 invalid (with the
reason; unknown fields and parameters are rejected), 404 unknown id, 409 another active rule has the same scope.
`LOCAL_SYNC` is accepted only for the token bucket until the leasing phase.

#### Publishing to Redis

Gateways load the configuration from Redis, never from PostgreSQL.

- **Where:** the hash `rl:rules` holds `version` and `snapshot`; each publish sends the version on `rl:rules:updates`.
  The design had two keys; one hash means a version can never be read next to the wrong snapshot. It has no TTL.
- **What:** one JSON document with all active rules and all active API keys (hashes only). Its format is defined
  in `rules-core` (`SnapshotCodec`), which also decodes it: any invalid rule rejects the whole snapshot.
- **When:** right after a change commits, and every 5 s the service compares Redis with the database by content
  and rewrites it if they differ. That covers a failed publish, Redis restarting empty, a damaged or hand-edited
  value, and a restored database.
- **Safety:** the database is read at one instant (`REPEATABLE READ`). The write is one Lua script that refuses to
  replace a newer version with an older one. The admin API works while Redis is down.
- **Threading:** publishing runs on its own thread. Done on the request thread just after commit, each request
  would hold one database connection while waiting for a second; more simultaneous changes than the pool size
  deadlocked until timeout (found by a test with concurrent changes).
- **Measured** (one laptop): a change reached Redis within 0.2 s; after a Redis restart the copy was back in 3.3 s;
  after a Redis outage the pending change was published 5.7 s after Redis started.
- **Limitation:** if a stored rule is made invalid by editing the database directly, nothing new is published
  (including key revocations) until it is fixed or deactivated. The service logs an error and
  `publishedVersion` falls behind. The admin API itself cannot create such a rule.

## Configuration (`gateway/src/main/resources/application.yml`)

- `gateway.trusted-proxies`: IPs / CIDRs whose `X-Forwarded-For` is believed. **Empty by default = trust nobody.**
- `gateway.auth.*`: JWT keys (by `kid`), issuer, audience, leeway; static API keys (used only while no published
  configuration is in force, see below); `public-paths` that skip auth.
- `gateway.redis.*`: `url` (empty = no Redis), `command-timeout` (15 ms), `connect-timeout` (1 s).
- `gateway.ratelimit.subscribe-to-updates` (default true) and `update-poll-interval` (default 10 s): how a running
  gateway learns of changes (with Redis).
- `gateway.ratelimit.snapshot-file` (default `data/rules-snapshot.json`, blank = off): where the last applied
  published rules are saved. Gateways on one machine need different files.
- `gateway.ratelimit.rules`: static rules in the shape of the `ratelimit_rules` table (see below). Built-in defaults
  (`default-ip`, `default-user`, negative ids) guarantee nothing is unlimited.
- `gateway.ratelimit.max-keys` (in-memory buckets held, default 100,000) and `max-waiting` (leaky-bucket requests one
  node may hold waiting at once, default 10,000).
- `gateway.proxy.*`: routes (path pattern → service → instances), timeouts, ejection threshold and cooldown.

### Where a gateway's rules come from

Without `gateway.redis.url`, from `application.yml` and the built-in defaults. With it, at startup and before
accepting requests, the gateway uses the first of these that gives usable rules:

1. the snapshot published in Redis (it **replaces** the YAML rules, it is not merged with them);
2. its own saved copy of the last snapshot it applied;
3. `application.yml` and the built-in defaults.

- A new set is decoded, validated and built completely, then swapped in with one reference write. A request
  always sees one whole set. A snapshot that is invalid, or has a rule this gateway cannot enforce, is refused as
  a whole and the rules in force stay.
- A swap does not touch limiter state: changing a limit applies to what a user has already used.
- The YAML rules must still be valid, even when published rules replace them.
- **A gateway starts even if Redis is down.** It connects in the background; until then limiter calls fail at
  once (requests are let through). After any outage it reconnects within about a second: the client library's
  default back-off, which grows to 30 s, is replaced. While disconnected, commands are rejected rather than
  queued, so they cannot spend tokens later for requests that were already let through.

#### API keys

API keys travel in the same snapshot as the rules and are swapped with them in one step, so creating,
revoking or re-tiering a key takes effect without a restart.

- **Published keys replace the ones in `gateway.auth.api-keys`; the two are never merged.** Otherwise a key
  revoked through the admin API would keep working for as long as a gateway's YAML listed it. The YAML keys
  apply only while no published configuration is in force: without Redis, or when Redis has nothing usable and
  there is no saved copy. Once keys come from the policy service, create them there.
- **Measured** with the real services: a new key was accepted 0.7 s after the admin API created it, and a revoked
  key was refused within 0.05 s of the admin API's response.
- **What this trades for availability.** A gateway that cannot reach Redis keeps the keys it has, so a key revoked
  during the outage keeps working until Redis is back; a gateway that *restarts* during an outage accepts the
  keys in its saved copy, or, with no copy, the YAML ones. A snapshot is applied whole or not at all, so one a
  gateway refuses (for example from a newer policy service) also holds back the revocations in it.

#### Following changes while running

- **Announcements:** the gateway subscribes to `rl:rules:updates` and reloads on each message. Subscribing, including
  again after a lost connection, also reloads, to catch what it could not hear.
- **Polling:** pub/sub delivers nothing to a subscriber that is away, so the published snapshot is also re-read every
  10 s. The whole snapshot is compared, not only the version (a change from the design): the policy service can
  repair a damaged copy without changing the version. The cost is one small read per gateway per interval.
- All reloads run on one thread, never on the Redis client's network thread. A burst of messages collapses into
  few reloads. A refused snapshot is logged once, not at every poll. An emptied Redis leaves the rules in force.
- **Tested:** an announced change reaches 3 gateways within 2 s; with announcements off it applies within 15 s at
  the default interval; announcements are heard again after a Redis restart; a gateway that started without Redis
  catches up as soon as Redis appears.

### Rule parameters per algorithm

| Algorithm | `params` | Notes |
|---|---|---|
| `TOKEN_BUCKET` | `capacity`, `refill_per_sec`, `cost` (default 1) | `STRICT` or `LOCAL_SYNC` |
| `SLIDING_WINDOW_LOG` | `limit` (≤ 100,000), `window_ms` (≤ 24 h) | `STRICT` only; Redis keeps one entry per request, so memory is O(limit) |
| `LEAKY_BUCKET_QUEUE` | `drain_per_sec`, `queue_capacity` | Max wait is derived (`queue_capacity / drain_per_sec`, ≤ 60 s). An instant burst of N admits N+1: one at once, N queued |

Keys may be written `refill_per_sec` or `refill-per-sec`; unknown keys are rejected. At startup the gateway
checks every rule against what it can serve and refuses to start on a mismatch, listing all of them, for example a
sliding-window or leaky-bucket rule with no `gateway.redis.url`, or `LOCAL_SYNC` on a leaky bucket.

Redis keys look like `rl:{ruleId:subject}:tb|sw|lb`, with the hash tag shared per rule and subject (braces in a
subject are escaped), and every key has a TTL.

## Decisions and additions beyond the design document

- **JWT:** `kid` is required (no default key). RS256 is pinned before any key lookup; `none`, HS256 and
  other algorithms are rejected. Clients get one generic 401 body; the precise reason is only logged.
- **Credentials are not forwarded.** `Authorization` and `X-API-Key` are stripped; downstream sees only
  `X-User-Id`, `X-Tier`, `X-Request-Id`, `X-Forwarded-For`, which the gateway always sets itself.
- **Rule precedence:** subject > tier > path specificity (Spring `PathPattern` ordering) > method > priority >
  lowest rule id. API-key callers match both `USER` and `API_KEY` rules; JWT callers match `USER` rules only.
  Candidate lists are cached by (stage, tier, method, path, available subject kinds), not by user, so
  many users or spoofed IPs cannot grow the cache.
- **Token bucket (in memory):** exact integer micro-token math with the sub-token remainder carried (no rounding drift).
  The clock is read inside the bucket's lock and a bucket never moves its timestamp backwards. This fixed a real
  over-admission bug that only the real-clock concurrency test exposed; frozen-clock tests could not.
- **Token bucket (Redis):** tokens are stored as a fractional number so no refill is rounded away; the stored timestamp
  never moves backwards (the same double-credit guard); a 1e-9 tolerance stops floating-point rounding from refusing a
  request at the exact moment `Retry-After` promised; key lifetime is computed in Java and capped at 30 days.
- **Sliding window:** an entry counts for exactly `window_ms` (dropped once `score <= now - window`); entries use short
  random member ids so requests in the same millisecond never collapse; rejected requests are not recorded.
- **Leaky bucket:** virtual scheduling, no real queue. Redis stores only the time the next request may be released;
  each request reserves a slot and the gateway sleeps the returned wait on its virtual thread. A refused request reserves
  nothing. The waiter cap is taken before asking Redis, so an overloaded node does not reserve slots it would never use.
- **Request guard:** non-canonical paths (`..`, `//`, `%2e`, `%2f`, `;` …) get 400 so the path rules match is
  the path that is forwarded. TRACE / CONNECT get 405.
- **Passive health:** connection errors, timeouts and 502/503/504 count against an instance; a plain 500 does not.
  Bodyless requests are retried once on another instance after a connection failure; requests with a body are never retried.
- **Rate-limit headers** come from the last limiter stage a request passes; upstream cannot override
  `X-RateLimit-*` or `X-Request-Id`.

## What was measured

All on one Windows laptop with Redis in Docker Desktop's VM, so the numbers are indicative, not a production benchmark.

- **Exactness across nodes (automated):** 3 limiter instances (own Redis connections) × 200 threads, one key: the token
  bucket admits exactly its capacity, the sliding window exactly its limit, 20 keys each exactly their own limit, and
  leaky-bucket requests from all three nodes are released on one shared schedule.
- **Live, 3 gateway processes sharing one Redis, 450 requests per path:** a `STRICT` rule of capacity 100 admitted exactly
  **100** across the cluster. The same rule as `LOCAL_SYNC` admitted **300** (100 per node), which is why Phase 4's
  leasing exists.
- **Whole-gateway overhead, sequential requests (ms):** direct to the mock p50 0.31 / p99 1.19; gateway with in-memory
  limits p50 1.05 / p99 2.92; gateway with STRICT limits p50 2.94 / p99 4.95. So STRICT adds about 3.8 ms at p99 at low load
  (target: under 10 ms), and a request makes two Redis calls of about 1 ms each (IP stage and user stage).
- **Limiter call under offered load (token bucket):** one thread p50 0.76 / p99 1.2 ms. At 4,000 calls/s p50 1.2 / p99 9.8 ms;
  at 10,000 calls/s p50 1.4 / p99 9.3 ms. At saturation (32 threads back to back, ~12,000 calls/s) p99 reaches 16–20 ms.
  Two such calls per request mean the 10 ms p99 target is **not yet proven under sustained 2k–5k req/s**: that needs a load
  tool (Phase 7). If it fails, the cheap fixes are making the IP stage `LOCAL_SYNC` (halving Redis calls) or a small
  connection pool.
- Measurement note: on Windows a sleeping thread wakes on a ~15 ms timer tick, so a load generator that sleeps between
  calls inflates latency by that much. The benchmark spins instead.

## Known limitations

- **If Redis is unreachable the request is let through** and an error is logged (at most every 10 s), with decision reason
  `LIMITER_UNAVAILABLE`. This is a stop-gap: per-rule `failure_mode` (FAIL_OPEN / FAIL_CLOSED / LOCAL_FALLBACK) and the
  circuit breaker arrive in Phase 5. `failure_mode` is validated and stored but nothing acts on it yet.
- `LOCAL_SYNC` is plain per-node memory until Phase 4: a cluster of N nodes admits up to N × the limit for those rules.
- Sliding-window and leaky-bucket rules need Redis (checked at startup). Their `cost` must be 1.
- A request held by a leaky bucket is lost if its node crashes. The proxy read timeout (10 s by default) and any load
  balancer timeout must exceed the longest wait of any leaky rule (up to 60 s).
- The Lua scripts take time from Redis, so their tests use short real waits and assert bounds; they are timing-based.
- If the limiter throws something other than a Redis connection error, the request gets a 500.
- No metrics yet; failures are only logged.
- The proxy read timeout covers waiting for response headers, not a stall mid-body. `Location` headers are not rewritten.
- The path where a client's upload fails mid-request (400, not counted against the upstream) is implemented but untested.
- Real Tomcat answers TRACE with an empty 405 before the guard filter sees it; the guard's JSON 405 is covered by MockMvc tests only.
- No `docker-compose.yml` yet; it arrives with the later phases (Postgres, policy service, Prometheus, Grafana).

## Next: Phase 3 (rules)

PostgreSQL schema (Flyway), the policy service (admin API with per-algorithm validation), the active rule snapshot written
to Redis with a version, pub/sub plus version polling so every gateway reloads and swaps its rule set atomically, a local
snapshot file for startup without Redis, and the live-state behavior on rule changes. Needs PostgreSQL (Docker).
