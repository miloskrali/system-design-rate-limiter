# Rate Limiter

Token Bucket rate limiter — IOL System Design Implementation Challenge.  
Based on Chapter 4 of Alex Xu's *System Design Interview – An Insider's Guide* (Vol 1).

**Stack:** Java 21 · Spring Boot 3.3 · Micrometer · Prometheus · Grafana

---

## Quick start

```bash
# 1. Build the jar
mvn package -q

# 2. Start app + Prometheus + Grafana
docker-compose up --build
```

| Service    | URL                                 |
|------------|-------------------------------------|
| App        | http://localhost:8080               |
| Prometheus | http://localhost:9090               |
| Loki       | http://localhost:3100               |
| Grafana    | http://localhost:3000 (admin/admin) |

---

## Manual testing

```bash
# Allowed request
curl -i http://localhost:8080/ping -H "X-API-Key: trader-abc"

# Exhaust the bucket (run 10 times)
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/ping -H "X-API-Key: trader-abc"
done

# Missing key → 401
curl -i http://localhost:8080/ping
```

**Response headers on every request:**
```
X-RateLimit-Limit:     10
X-RateLimit-Remaining: 9
```

**On 429:**
```
Retry-After: 6
```

---

## Running tests

```bash
mvn test
```

17 tests — unit (algorithm) + integration (Spring MockMvc).

---

## Architecture

```
algorithm/   ← pure Java, no Spring — testable with new
config/      ← Spring wiring only (bean + property binding)
web/         ← HTTP concerns only (filter + demo endpoint)
```

See [DESIGN.md](DESIGN.md) for algorithm choice, design decisions, and trade-offs.
