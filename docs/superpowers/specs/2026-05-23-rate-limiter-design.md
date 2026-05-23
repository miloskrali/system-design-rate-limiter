# Rate Limiter — Design Spec

**Date:** 2026-05-23  
**Challenge:** IOL System Design Implementation Challenge  
**Problem:** Chapter 4 — Rate Limiter (Alex Xu, *System Design Interview Vol 1*)  
**Language:** Java 21, Spring Boot 3.3

---

## Context

IOL (Invertir Online) is an Argentine stock broker. Traders exhibit naturally bursty behavior — during market open or a news event, a trader may place several orders in quick succession, then nothing for minutes. The rate limiter must allow controlled bursts while preventing abuse.

---

## Algorithm: Token Bucket

Token Bucket is the right fit for a broker because:

- **Allows bursts**: up to `capacity` requests in rapid succession — legitimate for trading
- **Limits sustained abuse**: tokens refill at a fixed rate; abusers can't sustain high throughput
- **Exact `Retry-After`**: `(1 - tokens) / refillRate` gives a precise wait time
- **Industry precedent**: used by Stripe for their payments API — same domain as financial services

Rejected alternatives:
- **Fixed Window Counter**: double-burst bug at window boundaries — unacceptable for financial APIs
- **Leaky Bucket**: enforces constant rate, blocks legitimate bursts — bad UX for traders
- **Sliding Window Log**: most accurate but memory-intensive at scale; overkill for a prototype

---

## Architecture

Approach: **Plain Java core, Spring only at the edges.**

The `algorithm/` package has zero Spring annotations and is testable with `new`. Spring wires it as a bean in `config/`. This follows APOSD principles: deep modules with clean interfaces, minimal coupling to the framework.

```
src/main/java/com/iol/ratelimiter/
├── algorithm/
│   ├── RateLimiter.java              # Interface: RateLimitDecision check(String clientId)
│   ├── RateLimitDecision.java        # Record: allowed, tokensRemaining, retryAfterSeconds
│   ├── TokenBucketRateLimiter.java   # Implementation — pure Java, no Spring
│   └── Bucket.java                   # Per-client state (tokens, lastRefillTime) — package-private
├── config/
│   ├── RateLimitProperties.java      # @ConfigurationProperties: capacity, refillRate
│   └── RateLimitConfig.java          # @Configuration: wires TokenBucketRateLimiter bean
├── web/
│   ├── RateLimitFilter.java          # OncePerRequestFilter: reads X-API-Key, calls allowRequest()
│   └── PingController.java           # GET /ping — demo endpoint
└── RateLimiterApplication.java
```

### Request flow

```
HTTP request
  → RateLimitFilter
      → reads X-API-Key header (401 if missing)
      → calls rateLimiter.check(apiKey) → RateLimitDecision(allowed, tokensRemaining, retryAfterSeconds)
          → TokenBucketRateLimiter looks up / creates Bucket for that client
          → Bucket.tryConsume() — synchronized on bucket instance
      → sets X-RateLimit-Limit and X-RateLimit-Remaining on all responses
      → 429 + Retry-After header if denied
      → passes through if allowed
  → PingController → 200 OK
```

---

## Token Bucket: Internal Design

Each client has its own `Bucket` instance with two fields:
- `double tokens` — current token count (starts at `capacity`)
- `long lastRefillTime` — nanosecond timestamp of last refill

On each `tryConsume()` call:
1. Compute `elapsed = now - lastRefillTime`
2. Add `elapsed × refillRate` tokens, clamped to `capacity`
3. Update `lastRefillTime = now`
4. If `tokens >= 1.0`: subtract 1.0, return `RateLimitDecision(allowed=true, remaining=floor(tokens), retryAfterSeconds=0)`
5. Otherwise: return `RateLimitDecision(allowed=false, remaining=0, retryAfterSeconds=ceil((1-tokens)/refillRate))`

`RateLimitDecision` is a Java record — immutable, returned atomically from a single `synchronized` call. The filter gets all header values in one shot with no TOCTOU gap.

### Thread safety

`tryConsume()` is `synchronized` on the `Bucket` instance. Lock granularity is per-client — two different clients never block each other.

New bucket creation uses `ConcurrentHashMap.computeIfAbsent()` — atomic, no race condition.

`AtomicReference` + CAS was considered but rejected: more complex to implement and reason about, no meaningful throughput gain for a broker's request volume.

### Configuration (`application.yml`)

```yaml
rate-limiter:
  capacity: 10       # max tokens (burst size)
  refill-rate: 0.1667  # tokens/second ≈ 10 tokens per 60s
```

---

## HTTP Layer

### Client identification

Clients are identified by the `X-API-Key` header. IP-based identification was rejected: traders at IOL operate behind corporate networks and VPNs where many users share one IP, creating false positives. In a broker, all requests must be authenticated — if the key is absent, the request is rejected with 401 before reaching the rate limiter.

### Response headers (RFC 6585 / common practice)

All responses include:
```
X-RateLimit-Limit: 10
X-RateLimit-Remaining: <tokens>
```

On 429 responses, additionally:
```
Retry-After: <seconds until next token available>
```

### Demo endpoint

```
GET /ping
→ 200 OK  {"status": "ok", "timestamp": "<ISO-8601>"}
```

Allows evaluators to test the rate limiter end-to-end with a simple `curl` command.

---

## Error Handling

| Condition | Response | Body |
|-----------|----------|------|
| Missing `X-API-Key` | 401 Unauthorized | `{"error": "Missing X-API-Key header"}` |
| Rate limit exceeded | 429 Too Many Requests | `{"error": "Rate limit exceeded"}` |
| Unexpected filter exception | Pass through + log WARNING | — |

The filter never takes down the service. If `allowRequest()` throws unexpectedly, the request is allowed through and the error is logged — rate limiting is middleware, not a critical failure point.

Out of scope (YAGNI): API key whitelist/blacklist, state persistence across restarts, Redis/distributed storage.

---

## Testing Strategy

### Unit tests — `algorithm/` (core coverage)

Instantiate `TokenBucketRateLimiter` directly. No Spring context.

| Test | Validates |
|------|-----------|
| `allowsRequestsWithinCapacity` | First N requests return true |
| `blocksRequestsWhenBucketEmpty` | N+1 request returns false |
| `refillsTokensOverTime` | After waiting X ms, tokens are available again |
| `isolatesClientsByApiKey` | Exhausted clientA does not affect clientB |
| `allowsFullBurstAfterIdle` | Bucket starts full, full burst is permitted |
| `threadSafety` | 20 concurrent threads on same client — exactly `capacity` successes, no more |

The concurrency test uses `CountDownLatch` to release all threads simultaneously and `AtomicInteger` to count granted permits.

### Integration tests — `web/` (Spring MockMvc)

| Test | Validates |
|------|-----------|
| `returns401WhenNoApiKey` | Request without header → 401 |
| `returns200WhenAllowed` | Request within limit → 200 |
| `returns429WhenLimitExceeded` | Exceeding limit → 429 |
| `headersArePresentOn429` | All three RFC headers present with correct values |

Test config: `capacity: 3, refill-rate: 100.0` — low limits for fast, deterministic tests.

---

## Trade-offs & Decisions Summary

| Decision | Choice | Reason |
|----------|--------|--------|
| Algorithm | Token Bucket | Allows bursts; used by Stripe; fits broker behavior |
| Algorithms implemented | One (Token Bucket) | Depth over breadth; avoids overengineering |
| Storage | In-memory (ConcurrentHashMap) | Sufficient for prototype; no Redis complexity |
| Thread safety | `synchronized` per Bucket | Simple, correct, easy to explain in interview |
| Client ID | `X-API-Key` header | IP unreliable behind NAT/VPN in broker context |
| Framework coupling | Core is plain Java | Testable without Spring; follows APOSD principles |
