# Distributed Rate Limiter & API Gateway

A Spring Boot API gateway that authenticates requests (RS256 JWT or API key), rate-limits them per
user / API key / IP with per-endpoint, per-tier rules, and proxies them to downstream services.

The full design is in `Distributed Rate Limiter & API Gateway Design Document.docx`. This file
records what is built, how to run it, and where the implementation departs from or adds to the design.

## Status

| Phase | Scope | State |
|---|---|---|
| **1 Foundation** | Gateway skeleton, mock services, RS256 + API-key auth, routing, in-memory token bucket behind `RateLimiter`, rule matching, 429 contract | **Done** (250 tests passing; live smoke test passed) |
| 2 STRICT mode | Lettuce, the three Lua scripts, Testcontainers tests | Not started |
| 3 Rules | Flyway schema, policy service, Redis snapshot, pub/sub, hot reload | Not started |
| 4 LOCAL_SYNC | Token leasing, membership, bound and skew tests | Not started |
| 5 Resilience | Circuit breaker, failure modes, fallback limiter | Not started |
| 6 Observability | Micrometer, `/metrics`, Prometheus, Grafana | Not started |
| 7 Hardening | Load tests, tuning, docs | Not started |

Docker is not installed on the development machine, so no `docker-compose.yml` exists yet; Phase 2
needs Redis, so Docker (or a native Redis) is required before then.

## Layout

| Module | Port | Purpose |
|---|---|---|
| `gateway` | 8080 | Filters, auth, rate limiting, proxy |
| `mock-service` | 8081… | Downstream stand-in with configurable latency / error rate |
| `policy-service` | 8090 | Empty shell; admin API arrives in Phase 3 |

Gateway request pipeline (servlet filters, in order):
`RequestIdFilter` → `RequestGuardFilter` → `ClientIpFilter` → `PreAuthRateLimitFilter` (by IP) →
`AuthFilter` → `PostAuthRateLimitFilter` (by user / key) → `ProxyController`.
(`RequestGuardFilter` runs right after the request id and before rate limiting, so unsafe paths cost no tokens.)

## Build and test

Gradle 9.9 (wrapper included), Java 21 via a Gradle toolchain (downloaded on first build; the machine's own JDK is only used to run Gradle).

```
./gradlew build            # compile + all tests
./gradlew :gateway:test
```

## Run locally

```
./gradlew :gateway:bootJar :mock-service:bootJar
# three mock instances: two "image", one "text" (ports match gateway.proxy.routes in application.yml)
java -jar mock-service/build/libs/mock-service-0.1.0-SNAPSHOT.jar --server.port=8081
java -jar mock-service/build/libs/mock-service-0.1.0-SNAPSHOT.jar --server.port=8082
java -jar mock-service/build/libs/mock-service-0.1.0-SNAPSHOT.jar --server.port=8083
# gateway with one API key (config holds only the SHA-256 of the key)
KEY_HASH=$(printf 'my-secret-key' | sha256sum | cut -d' ' -f1)
java -jar gateway/build/libs/gateway-0.1.0-SNAPSHOT.jar \
  --gateway.auth.api-keys[0].key-hash=$KEY_HASH \
  --gateway.auth.api-keys[0].user-id=demo --gateway.auth.api-keys[0].tier=free
curl -i -H 'X-API-Key: my-secret-key' http://localhost:8080/api/images/cat.png
```

Mock-service knobs (environment variables): `MOCK_LATENCY_MS`, `MOCK_LATENCY_JITTER_MS`, `MOCK_ERROR_RATE`, `MOCK_ERROR_STATUS`, `MOCK_INSTANCE_ID`.

JWT auth needs a public key in `gateway.auth.jwt.public-keys.<kid>` plus `issuer` and `audience`. There is
no runnable token issuer yet: tokens are minted only by the test helper `TokenFactory`. Add a small
dev issuer before load testing.

## Configuration (`gateway/src/main/resources/application.yml`)

- `gateway.trusted-proxies`: IPs / CIDRs whose `X-Forwarded-For` is believed. **Empty by default = trust nobody.**
- `gateway.auth.*`: JWT keys (by `kid`), issuer, audience, leeway; static API keys; `public-paths` that skip auth.
- `gateway.ratelimit.rules`: static rules in the shape of the `ratelimit_rules` table. Built-in defaults
  (`default-ip`, `default-user`, negative ids) guarantee nothing is unlimited.
- `gateway.proxy.*`: routes (path pattern → service → instances), timeouts, ejection threshold and cooldown.

## Decisions and additions beyond the design document

- **JWT:** `kid` is required (no default key). RS256 is pinned before any key lookup; `none`, HS256 and
  other algorithms are rejected. Clients get one generic 401 body; the precise reason is only logged.
- **Credentials are not forwarded.** `Authorization` and `X-API-Key` are stripped; downstream sees only
  `X-User-Id`, `X-Tier`, `X-Request-Id`, `X-Forwarded-For`, which the gateway always sets itself.
- **Rule precedence:** subject > tier > path specificity (Spring `PathPattern` ordering) > method > priority >
  lowest rule id. API-key callers match both `USER` and `API_KEY` rules; JWT callers match `USER` rules only.
  Candidate lists are cached by (stage, tier, method, path, available subject kinds), not by user, so
  many users or spoofed IPs cannot grow the cache.
- **Token bucket:** exact integer micro-token math with the sub-token remainder carried (no rounding drift).
  The clock is read inside the bucket's lock and a bucket never moves its timestamp backwards. This fixed a real
  over-admission bug that only the real-clock concurrency test exposed; frozen-clock tests could not.
- **Request guard:** non-canonical paths (`..`, `//`, `%2e`, `%2f`, `;` …) get 400 so the path rules match is
  the path that is forwarded. TRACE / CONNECT get 405.
- **Passive health:** connection errors, timeouts and 502/503/504 count against an instance; a plain 500 does not.
  Bodyless requests are retried once on another instance after a connection failure; requests with a body are never retried.
- **Rate-limit headers** come from the last limiter stage a request passes; upstream cannot override
  `X-RateLimit-*` or `X-Request-Id`.

## Known limitations

- Only the token bucket exists; leaky bucket and sliding window arrive with Phase 2. `mode` and `failure_mode` are
  validated and stored but nothing acts on them yet.
- If the limiter throws, the request gets a 500 (failure modes arrive in Phase 5).
- No metrics yet; failures are only logged.
- The read timeout covers waiting for response headers, not a stall mid-body. `Location` headers are not rewritten.
- The path where a client's upload fails mid-request (400, not counted against the upstream) is implemented but untested.
- Real Tomcat answers TRACE with an empty 405 before the guard filter sees it; the guard's JSON 405 is covered by MockMvc tests only.

## Next: Phase 2 (STRICT mode)

Lettuce client, the three Lua scripts (token bucket, sliding window log, leaky bucket by virtual scheduling),
`EVALSHA` with `NOSCRIPT` reload, Redis `TIME` for all script clocks, Testcontainers Lua tests, and the
200-thread atomicity test (exactly `limit` admitted). Requires Redis (Docker).
