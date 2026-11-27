# Distributed Rate Limiter & API Gateway

A Spring Boot API gateway that authenticates requests (RS256 JWT or API key), rate-limits them per
user / API key / IP with per-endpoint, per-tier rules, and proxies them to downstream services. Limits
can be exact across several gateway nodes (STRICT mode, Redis + Lua) or local to a node. Rules and API
keys are managed through a policy service and reach running gateways without a restart.

The full design is in `Distributed Rate Limiter & API Gateway Design Document.docx`. This file
records what is built, how to run it, and where the implementation departs from or adds to the design.

## Status

| Phase | Scope | State |
|---|---|---|
| **1 Foundation** | Gateway skeleton, mock services, RS256 + API-key auth, routing, in-memory token bucket behind `RateLimiter`, rule matching, 429 contract | **Done** |
| **2 STRICT mode** | Lettuce, Lua scripts for all three algorithms, exact limits across nodes, router, atomicity proof | **Done** (335 tests; live 3-node run passed) |
| **3 Rules** | Shared `rules-core`, PostgreSQL schema, admin API, publishing to Redis, gateways loading and following rules and API keys | **Done** (455 tests; live 3-gateway proof with an outage passed) |
| **4 LOCAL_SYNC** | Token leasing, gateway count, refills and expiry; bound and skew tests | **Done**, without `LOCAL_SYNC` for the leaky bucket, which was skipped (545 tests; live 3-gateway proof, results below) |
| 5 Resilience | Circuit breaker, failure modes, fallback limiter | In progress: step 1 of 4 done (circuit breaker; `failure_mode` is not applied yet) |
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

| | `STRICT` (`StrictRedisLimiter`) | `LOCAL_SYNC` (`LocalSyncLimiter`) | In memory (`InMemoryRateLimiter`) |
|---|---|---|---|
| Used for | `mode: STRICT` rules (the default) with `gateway.redis.url` set | `mode: LOCAL_SYNC` rules with it set | everything without it; `LOCAL_SYNC` when no tokens can be borrowed |
| Algorithms | token bucket, sliding window log, leaky bucket (queue) | token bucket | token bucket |
| Across nodes | exact: one atomic Lua script per decision, time from Redis `TIME` | one shared bucket, lent to nodes in batches; most requests need no Redis call | each node counts alone: N nodes admit up to N × the limit |

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
Redis, give each its own `--server.port`, and, if they share a working directory, its own
`--gateway.ratelimit.snapshot-file`.

**With the policy service running** (below), its published configuration replaces the rules *and the API keys*
in a gateway's own configuration, so the `my-secret-key` above stops working. Create keys through the admin
API instead:

```
curl -H "X-Admin-Token: $POLICY_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  http://localhost:8090/admin/api-keys -d '{"userId":"demo","tier":"free"}'     # the key is shown once
```

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
`LOCAL_SYNC` is accepted only for the token bucket.

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
- `gateway.redis.breaker.*`: `window` (20), `failure-rate-threshold` (50 %), `open-for` (5 s), `probes` (3); see
  "Circuit breaker" under "Decisions".
- `gateway.cluster.*` (with Redis): each node reports itself to the sorted set `rl:nodes` every `heartbeat-interval`
  (1 s); those heard from within `node-timeout` (3 s, at least two intervals) are counted, which sets each node's share
  of a `LOCAL_SYNC` bucket. A normal shutdown removes the node at once. While reports fail, the last count is kept.
  Just after Redis restarts or an outage the count can be low for up to a heartbeat, which only affects fairness.
  Give every node the same values; gateways sharing a Redis count each other.
- `gateway.ratelimit.subscribe-to-updates` (default true) and `update-poll-interval` (default 10 s): how a running
  gateway learns of changes (with Redis).
- `gateway.ratelimit.snapshot-file` (default `data/rules-snapshot.json`, blank = off): where the last applied
  published rules are saved. Gateways on one machine need different files.
- `gateway.ratelimit.rules`: static rules in the shape of the `ratelimit_rules` table (see below). Built-in defaults
  (`default-ip`, `default-user`, negative ids) guarantee nothing is unlimited.
- `gateway.lease.*` (with Redis), for `LOCAL_SYNC`: `min-batch` (1), `horizon` (1 s: a batch is the tokens expected
  over it, and may be spent for twice as long), `refill-below` (0.3: fetch the next batch in the background below this
  fraction of the last; 0 = off). A longer horizon means fewer Redis calls and more tokens held by one node.
- `gateway.ratelimit.max-keys` (in-memory buckets and borrowed-token states held, default 100,000) and `max-waiting` (leaky-bucket requests one
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
- **Limiter state is kept** across a change, so a new limit applies to what was already used, also when a rule moves
  between `STRICT` and `LOCAL_SYNC`. When a `LOCAL_SYNC` rule's parameters change, each node **throws away** the tokens
  it borrowed under the old ones, including those of calls still in progress, and asks again at once. They are not
  given back: the shared bucket is clamped to a lowered capacity once, but nodes return at different moments, and
  every return would fill it up to the new capacity again. In a test where three gateways each held about 49 tokens
  and the limit was lowered to 5, giving them back admitted 9; throwing them away admits 5.

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
- **Token borrowing (`LOCAL_SYNC`)** uses the same Redis bucket as `STRICT`. Two changes from the design's script:
  the caller also says how many tokens a waiting request is short of (`need`), which may be granted above the fair
  share (otherwise, with capacity 10, 3 nodes and a cost of 5, a full bucket's share of 4 refuses the first request),
  and when the bucket cannot cover it nothing is taken; and the share is `ceil(whole tokens / nodes)`, so a refill
  fraction cannot raise it.
- **When a `LOCAL_SYNC` node lacks the tokens, the request waits for a lease call** (the design blocks only a key's first
  request, and would refuse others while more are fetched in the background, so a burst larger than the batch, or a user
  coming back, could be refused although the shared bucket has tokens). One call per (rule, key) is in progress at a
  time; requests arriving meanwhile share it, and the next call asks for enough for those still unserved. When a reply
  says the shared bucket cannot cover another request, such requests are refused from memory until the expected time,
  or for one lease horizon if sooner (other nodes may give tokens back); a node holding expired tokens asks at once,
  since the call gives them back. If a call fails, its waiters use the per-node fallback limiter. Each (rule, key) is
  guarded by its own lock, never held during a call (the design suggests a CAS counter), and tokens are whole numbers
  because a node never refills by itself.
- **Batches, refills and expiry (`LOCAL_SYNC`):** a batch is the tokens expected over one lease horizon (each request
  adds its cost to a total that fades with that time constant), at least the minimum batch. Below the refill fraction
  (design: 30%) the next batch is fetched in the background, but only if more than the current request is expected;
  otherwise a rarely used key would cost two calls per request and hold tokens it never spends. Unspent tokens may be
  spent for two horizons after the latest grant, and are given back with the key's next call from that node (as
  designed: if there is none, they are lost until the bucket refills). A late reply cannot renew expired tokens.
- **A returned token is counted once, even if the call runs twice.** The Redis client library sends a call again if the
  connection breaks before the reply and returns while the call is still pending. With the default 15 ms command
  timeout the call gives up first (it reconnects after 1 s), but with a 5 s timeout a test saw it run twice and credit
  5 returned tokens as 10, which would let the cluster exceed the limit. The script therefore marks each return with an
  id unique to the call (a key that lives 60 s). Borrowing twice needs no guard: the first grant is simply lost.
- **The `LOCAL_SYNC` limit guarantee is kept as designed, and is narrower than it sounds** (agreed before Phase 4).
  Counted from when the shared bucket was full, the cluster never admits more than `capacity + refill × time`. In a
  *later* window a burst can reach `capacity + tokens currently borrowed and unspent`, because the shared bucket can
  refill while nodes still hold tokens. Rules that need an exact limit should use `STRICT`.
- **If Redis is unreachable, a `LOCAL_SYNC` node** spends what it has borrowed while that is still valid, then limits
  with its own in-memory bucket (not fail-open; agreed), until Phase 5's `failure_mode`.
- **`LOCAL_SYNC` for the leaky bucket (design 6.3) is not built**, by choice. The design's version, each node releasing
  at 1/N of the rate, is only approximate. The policy service and the gateway refuse such a rule; use `STRICT`.
- **Circuit breaker** (Resilience4j, design's settings): one per gateway, around limit checks, token borrowing and
  heartbeats, which fail together. When at least half of the latest 20 calls failed it opens, and calls are skipped at
  once, with no 15 ms wait and no load on Redis; after 5 s, 3 trial calls decide. Loading rules is not behind it, so
  rules are re-read regardless. Nothing runs on a timer: a trial happens at the first call after the wait, and the
  heartbeat's call each second lets a gateway with no traffic recover. While it is open no heartbeats are sent, so a
  node whose breaker stays open past the node timeout drops out of the others' count. A skipped call certainly never
  reached Redis, so tokens it would have returned are kept for the next one (after any other failure they are dropped).
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
  **100** across the cluster. The same rule as `LOCAL_SYNC`, then still counted per node, admitted **300** (100 per node), which is why Phase 4's
  leasing exists.
- **Whole-gateway overhead, sequential requests (ms):** direct to the mock p50 0.31 / p99 1.19; gateway with in-memory
  limits p50 1.05 / p99 2.92; gateway with STRICT limits p50 2.94 / p99 4.95. So STRICT adds about 3.8 ms at p99 at low load
  (target: under 10 ms), and a request makes two Redis calls of about 1 ms each (IP stage and user stage).
- **Limiter call under offered load (token bucket):** one thread p50 0.76 / p99 1.2 ms. At 4,000 calls/s p50 1.2 / p99 9.8 ms;
  at 10,000 calls/s p50 1.4 / p99 9.3 ms. At saturation (32 threads back to back, ~12,000 calls/s) p99 reaches 16–20 ms.
  Two such calls per request mean the 10 ms p99 target is **not yet proven under sustained 2k–5k req/s**: that needs a load
  tool (Phase 7). If it fails, the cheap fixes are making the IP stage `LOCAL_SYNC` (halving Redis calls) or a small
  connection pool.
- **Rule changes under load (Phase 3), 3 gateway processes, 300 requests/s between them, two runs:** each limit change
  made through the admin API was in use on all three gateways 53–217 ms after the admin request was sent
  (target: 2 s). No gateway served the old limit after switching, and no request failed.
- **Redis and PostgreSQL stopped for about 12 s under that load:** no request failed in either run. In the second,
  all 3,171 requests sent while both were fully down succeeded under the last published rules, but unchecked
  (fail-open), with p99 5.7 ms (21.8 ms in the first run). Limits were enforced again about 1.5 s after Redis was told
  to start. A limit lowered to 100 afterwards admitted exactly **100** across the cluster, in both runs.
- **Warm gateways under that load:** p50 5.6 ms / p99 10.0 ms end to end (including the mock service), and 0 of 5,704
  requests unchecked.
- **Cold gateways are not exact** (see the limitation below).

#### `LOCAL_SYNC` against `STRICT` (Phase 4)

3 gateway processes with default settings, each request sent to one chosen at random, 300 requests/s unless stated,
after 40 s of warm-up. Each request passes an IP rule and a user rule, both in the mode being measured. Redis calls
are Redis's own `EVALSHA` count, less the gateways' heartbeats. Latency is end to end, including the mock service.

**Generous limits** (nothing refused), 6,000 requests per cell, two runs with the modes in opposite order:

| Users sharing the traffic | `STRICT` calls per request | `LOCAL_SYNC` calls per request | p50 ms `STRICT` / `LOCAL_SYNC` | p99 ms `STRICT` / `LOCAL_SYNC` |
|---|---|---|---|---|
| 1 | 2.00 | **0.028** | 3.4–4.6 / 1.6–1.8 | 5.7–9.2 / 2.5–2.8 |
| 10 (30 requests/s each) | 2.00 | **0.13** | 3.6–3.8 / 1.5–1.7 | 5.6–6.2 / 2.8–3.0 |
| 300 (1 request/s each) | 2.00 | **0.97** | 3.3–3.7 / 2.5–3.6 | 5.6–5.8 / 3.9–12.0 |

With 300 rarely seen users the user rule saves almost nothing (about one call per request); the saving left is the
IP rule, whose one key is busy. `LOCAL_SYNC` pays off for keys that are busy on each node.

**One user asking for 300/s for 20 s** (far over the limit); allowance = capacity + refill × time:

| Capacity, refill | Mode, gateways used | Admitted / allowance | Redis calls (6,000 requests) |
|---|---|---|---|
| 100, 50/s | `STRICT`, 3 | 1,099 / 1,100 | 12,000 |
| 100, 50/s | `LOCAL_SYNC`, 3 | 1,099 / 1,100 | 2,685 |
| 100, 50/s | `LOCAL_SYNC`, only 1 of the 3 | 1,099 / 1,100 | 1,028 |
| 1,000, 200/s | `STRICT`, 3 | 4,999 / 5,000 | 11,999 |
| 1,000, 200/s | `LOCAL_SYNC`, 3 | 4,999 / 5,000 | 2,649 |
| 1,000, 200/s | `LOCAL_SYNC`, only 1 of the 3 | 4,998 / 4,999 | 2,099 |

- Under steady demand the limit held and about one request's worth of allowance went unused, also with all traffic on
  one node. Allowance stranded when a key's traffic stops or moves was not measured.
- Over the limit, `LOCAL_SYNC` still made far fewer calls than `STRICT`, but with the small bucket on 3 nodes it made
  **2.4 calls per admitted request**: each node asks again as soon as one token could be there.
- **Practically no refill, capacity 600, 3,000 requests:** exactly **600** admitted in all 12 runs (`STRICT`,
  `LOCAL_SYNC` over 3 gateways and over 1).
- **Bursts** (capacity 200, refill 200/s: 150 requests/s for 5 s, 1 s of quiet, 800 requests at once; two runs of four
  bursts per mode). In the 6 `LOCAL_SYNC` bursts with no failed lease call in the logs, 239–290 were admitted, 14–52
  above capacity plus refill during the burst, as expected from tokens nodes were already holding. In the other 2, lease calls
  failed, affected nodes fell back to a full bucket of their own, and 383 and 431 were admitted (128 and 157 above).
  `STRICT` was worse, see the first limitation below.
- **Redis stopped, 3 gateways, capacity 100, practically no refill:** with `LOCAL_SYNC`, 324 of 1,500 requests were
  admitted during the outage (a fallback bucket of 100 on each node, plus 24, which fits tokens borrowed before it);
  with `STRICT`, all 1,500. After Redis came back empty, each mode admitted exactly 100 again.
- The design asks for `LOCAL_SYNC` gateway overhead under 3 ms at p99. These runs were at 300 requests/s, so that is
  **not shown for the design's 2k–5k requests/s** (Phase 7).
- Measurement note: on Windows a sleeping thread wakes on a ~15 ms timer tick, so a load generator that sleeps between
  calls inflates latency by that much. The benchmark spins instead.

## Known limitations

- **Until Phase 5 step 3, an open breaker makes some of the gaps below worse.** A skipped call is handled like a
  failed one: for at least 5 s a node lets every `STRICT` request through unchecked, and limits `LOCAL_SYNC` requests
  that need more tokens with its own full-size bucket. A burst or cold start can open the breaker although Redis is
  healthy; how often has not been measured yet. Do not deploy this step without step 3.
- **Limits resume later after an outage than before the breaker:** it may be nearly 5 s into its wait when Redis
  returns. In a test with one gateway, restarting Redis at 7 different points of that cycle, checks resumed 1.1–5.7 s
  after Redis was started (about 1.5 s before the breaker).
- **`STRICT` requests can pass unchecked even with Redis healthy: on a freshly started gateway, and in a sudden
  burst on a warm one.** A Redis call that exceeds the 15 ms command timeout fails, and a failed `STRICT` call is let
  through. So `STRICT` is exact only while every call answers in time.
  - *Bursts:* with 800 requests at once on 3 warm gateways (capacity 200, refill 200/s), 261–693 of them passed
    unchecked in the four bursts where this was counted, and 516–749 were admitted. The ones Redis did check stayed
    within the limit (56–255).
  - *Cold start:* calls are slow while the JVM warms up. At 300 requests/s over three just-started gateways, one run
    had 172 of 1,200 requests unchecked in the first 4 s (p99 959 ms); another, with the load applied steadily, had
    18 of about 1,500 in the first 5 s, and none after that at that rate.
  - Phase 5's `LOCAL_FALLBACK` would keep limiting such requests locally; warming a node before it takes traffic, or
    a longer timeout, would reduce them. A `LOCAL_SYNC` request whose call fails is not let through unchecked: the node
    limits it by itself. But that fallback bucket starts **full**, so a burst can still get up to one extra capacity
    from each affected node (measured above). The design's `LOCAL_FALLBACK`, giving each node 1/N of the limit,
    would shrink this.
- While gateways are switching to a new rule (a few milliseconds apart in the measured runs), one may still apply
  the old parameters to the same Redis bucket another is already treating with the new ones.
- The admin API has no TLS and one shared token, so changes cannot be attributed to a person. Keep it off
  untrusted networks.
- During a Redis outage a revoked API key keeps working, and a rule made invalid by editing the database directly
  blocks all publishing, revocations included (details under "API keys" and "Publishing to Redis").
- **If Redis is unreachable, a `STRICT` request is let through** (all three algorithms) and an error is logged (at most every 10 s), with decision reason
  `LIMITER_UNAVAILABLE`. This is a stop-gap: per-rule `failure_mode` (FAIL_OPEN / FAIL_CLOSED / LOCAL_FALLBACK) and the
  circuit breaker arrive in Phase 5. `failure_mode` is validated and stored but nothing acts on it yet.
- **`LOCAL_SYNC` is not exact.** A burst can reach the capacity plus the tokens nodes hold (see "Decisions"), and it can
  refuse while another node holds unspent tokens. Tokens a node borrowed for a key it never hears from again are lost
  until the shared bucket refills; so are those held by a node that shuts down. With a small limit a node's share is a
  few tokens, so it saves few Redis calls; the same goes for keys each node rarely sees (measured above). It is meant
  for busy keys with high limits.
- A key held far over a limit that refills quickly makes each node ask Redis up to about once per refilled token.
  A minimum wait before asking again would cut those calls, at the price of admitting refilled tokens later; to be
  decided with load tests (Phase 7).
- **While Redis is unreachable, `LOCAL_SYNC` rules are limited by each node alone:** after what it had borrowed, each
  starts from a full in-memory bucket, so N nodes can admit about N × the limit on top of what was lent.
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

## Next: Phase 5 (resilience)

A circuit breaker around Redis calls (design: opens at 50% failures over 20 calls, for 5 s, then 3 probes), each
rule's `failure_mode` acted on (`FAIL_OPEN`, `FAIL_CLOSED` with 503, or `LOCAL_FALLBACK`: a local bucket with 1/N of
the capacity and refill, N being the last known node count), no leasing or heartbeats while the breaker is open, and a
test that stops Redis under load. `LOCAL_FALLBACK` bears on the two largest gaps measured so far: `STRICT` requests
passing unchecked in bursts and cold starts, and `LOCAL_SYNC`'s full-size fallback buckets.
