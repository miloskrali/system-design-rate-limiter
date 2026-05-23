# Rate Limiter — Design

## Problem

Implement a working rate limiter prototype based on Chapter 4 of Alex Xu's
*System Design Interview – An Insider's Guide* (Vol 1).

## Algorithm Choice: Token Bucket

IOL is a stock broker. Traders exhibit naturally bursty behavior — during
market open or a news event, a trader may place several orders in quick
succession, then nothing for minutes. Token Bucket is the right fit because:

- **Allows controlled bursts**: up to `capacity` requests at once — legitimate for trading
- **Limits sustained abuse**: tokens refill at a fixed rate; a bot can't sustain high throughput
- **Exact `Retry-After`**: the wait time until the next token is mathematically precise
- **Industry precedent**: Stripe uses Token Bucket for their payments API

Rejected alternatives:
- **Fixed Window Counter**: double-burst bug at window boundaries — unacceptable for financial APIs
- **Leaky Bucket**: enforces constant rate, blocks legitimate bursts
- **Sliding Window Log**: most accurate but memory-intensive; overkill here

## Architecture

**Plain Java core, Spring only at the edges.**

```
algorithm/   ← pure Java, no Spring annotations, testable with `new`
config/      ← Spring wiring only (bean creation, property binding)
web/         ← HTTP concerns only (read header, write response)
```

The `RateLimiter` interface lives in `algorithm/` with no framework coupling.
`TokenBucketRateLimiter` can be instantiated and tested directly — no mocks,
no Spring context, just `new TokenBucketRateLimiter(capacity, refillRate)`.

This follows the APOSD principle of deep modules with clean interfaces.

## Key Design Decisions

**Thread safety — `synchronized` per Bucket**
Each client has its own `Bucket` instance. `tryConsume()` is synchronized on
the bucket, not globally — two different clients never block each other.
New buckets are created via `ConcurrentHashMap.computeIfAbsent()` (atomic).

**`RateLimitDecision` record**
`check()` returns a record with `allowed`, `tokensRemaining`, and
`retryAfterSeconds`. The filter gets all three values in one atomic call —
no TOCTOU gap between checking allowed status and reading token count.

**Client identification via `X-API-Key`**
IP-based identification was rejected: traders behind corporate NAT/VPNs
share IPs. In a broker, every request must be authenticated; if the key is
absent the request is rejected with 401.

## Observability

Micrometer counters `ratelimit.requests.allowed` and
`ratelimit.requests.denied` (tagged by `apiKey`) are scraped by Prometheus
and visualized in a pre-provisioned Grafana dashboard.

```bash
docker-compose up   # starts app + Prometheus (:9090) + Grafana (:3000)
```

This matches IOL's actual observability stack.

## Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| Storage | In-memory | Fast, simple. Multiple instances would have independent counters — acceptable for a prototype; Redis would solve this in production |
| Algorithms | Token Bucket only | Depth over breadth. One well-understood algorithm > three half-baked ones |
| Thread safety | `synchronized` | Simple and correct. AtomicReference+CAS would be faster under extreme contention but adds complexity without measurable benefit at typical broker traffic |

## How I Used AI

Claude Code (Anthropic) was used to:
- Assist with brainstorming algorithm selection and architecture design
- Draft the initial implementation plan with TDD task breakdown
- Generate boilerplate (pom.xml, Docker Compose, Grafana dashboard JSON)

All generated code was reviewed, understood, and in several places rewritten
by me. Every class, method, and design decision in this repository I can
explain. The algorithm implementation, thread safety reasoning, and test
design are my own work.
