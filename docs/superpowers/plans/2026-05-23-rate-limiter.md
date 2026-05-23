# Rate Limiter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a Token Bucket rate limiter as a Spring Boot application with Prometheus + Grafana observability, fulfilling the IOL system design interview challenge.

**Architecture:** Plain Java core (`algorithm/`) with zero Spring annotations — testable with `new`. Spring wires the core as a bean in `config/`. The HTTP layer (`web/`) reads `X-API-Key`, calls the rate limiter, and sets RFC 6585 response headers. Metrics are instrumented via Micrometer and scraped by Prometheus.

**Tech Stack:** Java 21, Spring Boot 3.3, Micrometer + Prometheus, Loki 2.9, Promtail 2.9, Grafana 10, Docker Compose, JUnit 5, AssertJ, MockMvc.

---

## File Map

```
pom.xml                                                     ← Task 1
Dockerfile                                                  ← Task 7
docker-compose.yml                                          ← Task 7
prometheus.yml                                              ← Task 7
DESIGN.md                                                   ← Task 8

src/main/java/com/iol/ratelimiter/
├── RateLimiterApplication.java                             ← Task 1
├── algorithm/
│   ├── RateLimiter.java                                    ← Task 2
│   ├── RateLimitDecision.java                              ← Task 2
│   ├── Bucket.java             (package-private)           ← Task 3
│   └── TokenBucketRateLimiter.java                         ← Task 3
├── config/
│   ├── RateLimitProperties.java                            ← Task 4
│   └── RateLimitConfig.java                                ← Task 4
└── web/
    ├── RateLimitFilter.java                                ← Task 5
    └── PingController.java                                 ← Task 6

src/main/resources/
└── application.yml                                         ← Task 1

src/test/java/com/iol/ratelimiter/
├── RateLimiterApplicationTests.java                        ← Task 1
├── algorithm/
│   ├── RateLimitDecisionTest.java                          ← Task 2
│   └── TokenBucketRateLimiterTest.java                     ← Task 3
├── config/
│   └── RateLimitConfigTest.java                            ← Task 4
└── web/
    └── RateLimitFilterIntegrationTest.java                 ← Task 5

grafana/provisioning/
├── datasources/prometheus.yml                              ← Task 7
├── datasources/loki.yml                                    ← Task 9
└── dashboards/
    ├── dashboard.yml                                       ← Task 7
    └── rate-limiter.json                                   ← Task 7 + Task 9

loki-config.yml                                             ← Task 9
promtail-config.yml                                         ← Task 9
```

---

## Task 1: Project Scaffold

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/iol/ratelimiter/RateLimiterApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/iol/ratelimiter/RateLimiterApplicationTests.java`

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.0</version>
        <relativePath/>
    </parent>

    <groupId>com.iol</groupId>
    <artifactId>rate-limiter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>rate-limiter</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create `src/main/java/com/iol/ratelimiter/RateLimiterApplication.java`**

```java
package com.iol.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RateLimiterApplication {
    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }
}
```

- [ ] **Step 3: Create `src/main/resources/application.yml`**

```yaml
rate-limiter:
  capacity: 10
  refill-rate: 0.1667   # tokens/second ≈ 10 tokens per 60s

management:
  endpoints:
    web:
      exposure:
        include: health, prometheus
  endpoint:
    prometheus:
      enabled: true
```

- [ ] **Step 4: Create `src/test/java/com/iol/ratelimiter/RateLimiterApplicationTests.java`**

```java
package com.iol.ratelimiter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RateLimiterApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 5: Verify build compiles**

```bash
mvn clean package -DskipTests
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/iol/ratelimiter/RateLimiterApplication.java \
        src/main/resources/application.yml \
        src/test/java/com/iol/ratelimiter/RateLimiterApplicationTests.java
git commit -m "chore: project scaffold — Spring Boot + Actuator + Prometheus"
```

---

## Task 2: RateLimitDecision Record + RateLimiter Interface

**Files:**
- Create: `src/test/java/com/iol/ratelimiter/algorithm/RateLimitDecisionTest.java`
- Create: `src/main/java/com/iol/ratelimiter/algorithm/RateLimitDecision.java`
- Create: `src/main/java/com/iol/ratelimiter/algorithm/RateLimiter.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/iol/ratelimiter/algorithm/RateLimitDecisionTest.java`:

```java
package com.iol.ratelimiter.algorithm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitDecisionTest {

    @Test
    void allowedDecisionHasCorrectFields() {
        var decision = new RateLimitDecision(true, 9, 0);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.tokensRemaining()).isEqualTo(9);
        assertThat(decision.retryAfterSeconds()).isEqualTo(0);
    }

    @Test
    void deniedDecisionHasCorrectFields() {
        var decision = new RateLimitDecision(false, 0, 6);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.tokensRemaining()).isEqualTo(0);
        assertThat(decision.retryAfterSeconds()).isEqualTo(6);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=RateLimitDecisionTest
```

Expected: `COMPILATION ERROR` — `RateLimitDecision` does not exist yet.

- [ ] **Step 3: Create `src/main/java/com/iol/ratelimiter/algorithm/RateLimitDecision.java`**

```java
package com.iol.ratelimiter.algorithm;

/**
 * Immutable result of a rate limit check.
 * Returned atomically from a single synchronized call — the filter
 * gets allowed status and all header values in one shot.
 */
public record RateLimitDecision(boolean allowed, int tokensRemaining, long retryAfterSeconds) {}
```

- [ ] **Step 4: Create `src/main/java/com/iol/ratelimiter/algorithm/RateLimiter.java`**

```java
package com.iol.ratelimiter.algorithm;

public interface RateLimiter {
    /**
     * Checks whether the client identified by {@code clientId} is allowed to proceed.
     * Consumes one token if allowed.
     */
    RateLimitDecision check(String clientId);
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -Dtest=RateLimitDecisionTest
```

Expected: `BUILD SUCCESS` — 2 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/iol/ratelimiter/algorithm/ \
        src/test/java/com/iol/ratelimiter/algorithm/RateLimitDecisionTest.java
git commit -m "feat: RateLimitDecision record and RateLimiter interface"
```

---

## Task 3: Bucket + TokenBucketRateLimiter

**Files:**
- Create: `src/test/java/com/iol/ratelimiter/algorithm/TokenBucketRateLimiterTest.java`
- Create: `src/main/java/com/iol/ratelimiter/algorithm/Bucket.java`
- Create: `src/main/java/com/iol/ratelimiter/algorithm/TokenBucketRateLimiter.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/iol/ratelimiter/algorithm/TokenBucketRateLimiterTest.java`:

```java
package com.iol.ratelimiter.algorithm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketRateLimiterTest {

    // capacity=3, refillRate=100 tokens/sec — bucket refills in ~10ms for time tests
    private TokenBucketRateLimiter limiter(double capacity, double refillRate) {
        return new TokenBucketRateLimiter(capacity, refillRate);
    }

    @Test
    void allowsRequestsWithinCapacity() {
        var rl = limiter(3, 1.0);

        for (int i = 0; i < 3; i++) {
            assertThat(rl.check("client-a").allowed())
                .as("Request %d should be allowed", i + 1)
                .isTrue();
        }
    }

    @Test
    void blocksRequestsWhenBucketEmpty() {
        var rl = limiter(3, 1.0);

        for (int i = 0; i < 3; i++) {
            rl.check("client-a");
        }

        var decision = rl.check("client-a");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.tokensRemaining()).isEqualTo(0);
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        // refillRate=100 → 1 token every 10ms
        var rl = limiter(3, 100.0);

        for (int i = 0; i < 3; i++) {
            rl.check("client-a");
        }
        assertThat(rl.check("client-a").allowed()).isFalse();

        Thread.sleep(25); // wait long enough for at least 2 tokens to refill

        assertThat(rl.check("client-a").allowed()).isTrue();
    }

    @Test
    void isolatesClientsByApiKey() {
        var rl = limiter(3, 1.0);

        for (int i = 0; i < 3; i++) {
            rl.check("client-a");
        }
        assertThat(rl.check("client-a").allowed()).isFalse();

        // client-b has its own full bucket — unaffected by client-a
        assertThat(rl.check("client-b").allowed()).isTrue();
    }

    @Test
    void allowsFullBurstAfterIdle() {
        var rl = limiter(5, 1.0);

        for (int i = 0; i < 5; i++) {
            assertThat(rl.check("client-a").allowed())
                .as("Burst request %d should be allowed", i + 1)
                .isTrue();
        }
    }

    @Test
    void returnsDeniedDecisionWithRetryAfter() {
        var rl = limiter(1, 1.0); // 1 token/sec refill

        rl.check("client-a"); // drain the single token

        var decision = rl.check("client-a");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.tokensRemaining()).isEqualTo(0);
        assertThat(decision.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void threadSafety() throws InterruptedException {
        int capacity = 10;
        // Very low refill rate so no tokens are added during the ~ms this test runs
        var rl = limiter(capacity, 0.001);

        int threadCount = 20;
        var startLatch = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(threadCount);
        var allowedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // all threads wait here until released simultaneously
                    if (rl.check("shared-client").allowed()) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown(); // release all 20 threads at once
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Exactly 'capacity' threads should have been granted — synchronized prevents overshooting
        assertThat(allowedCount.get()).isEqualTo(capacity);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=TokenBucketRateLimiterTest
```

Expected: `COMPILATION ERROR` — `TokenBucketRateLimiter` does not exist yet.

- [ ] **Step 3: Create `src/main/java/com/iol/ratelimiter/algorithm/Bucket.java`**

```java
package com.iol.ratelimiter.algorithm;

/**
 * Mutable token bucket state for a single client.
 * Package-private — only TokenBucketRateLimiter creates and uses buckets.
 *
 * Thread safety: tryConsume() is synchronized on the Bucket instance.
 * Lock granularity is per-client, so two different clients never block each other.
 */
class Bucket {

    private double tokens;
    private long lastRefillTime; // nanoseconds

    Bucket(double initialTokens) {
        this.tokens = initialTokens;
        this.lastRefillTime = System.nanoTime();
    }

    /**
     * Refills tokens based on elapsed time, then attempts to consume one token.
     *
     * @param capacity         max tokens the bucket can hold
     * @param refillRatePerNano tokens added per nanosecond
     */
    synchronized RateLimitDecision tryConsume(double capacity, double refillRatePerNano) {
        refill(capacity, refillRatePerNano);

        if (tokens >= 1.0) {
            tokens -= 1.0;
            return new RateLimitDecision(true, (int) tokens, 0L);
        }

        // Time (in seconds) until the bucket has at least 1 token
        long retryAfterSeconds = refillRatePerNano > 0
                ? (long) Math.ceil((1.0 - tokens) / (refillRatePerNano * 1_000_000_000.0))
                : Long.MAX_VALUE;

        return new RateLimitDecision(false, 0, retryAfterSeconds);
    }

    private void refill(double capacity, double refillRatePerNano) {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTime;
        tokens = Math.min(capacity, tokens + elapsedNanos * refillRatePerNano);
        lastRefillTime = now;
    }
}
```

- [ ] **Step 4: Create `src/main/java/com/iol/ratelimiter/algorithm/TokenBucketRateLimiter.java`**

```java
package com.iol.ratelimiter.algorithm;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Token Bucket rate limiter. Each client gets an independent bucket.
 * Pure Java — no framework dependencies, instantiated directly in tests.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final double capacity;
    private final double refillRatePerNano; // tokens per nanosecond
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(double capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerNano = refillRatePerSecond / 1_000_000_000.0;
    }

    @Override
    public RateLimitDecision check(String clientId) {
        // computeIfAbsent is atomic — no two threads can create a bucket for the same key
        Bucket bucket = buckets.computeIfAbsent(clientId, k -> new Bucket(capacity));
        return bucket.tryConsume(capacity, refillRatePerNano);
    }
}
```

- [ ] **Step 5: Run tests to verify they all pass**

```bash
mvn test -Dtest=TokenBucketRateLimiterTest
```

Expected: `BUILD SUCCESS` — 7 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/iol/ratelimiter/algorithm/Bucket.java \
        src/main/java/com/iol/ratelimiter/algorithm/TokenBucketRateLimiter.java \
        src/test/java/com/iol/ratelimiter/algorithm/TokenBucketRateLimiterTest.java
git commit -m "feat: Token Bucket algorithm with thread-safe per-client buckets"
```

---

## Task 4: Configuration Wiring

**Files:**
- Create: `src/test/java/com/iol/ratelimiter/config/RateLimitConfigTest.java`
- Create: `src/main/java/com/iol/ratelimiter/config/RateLimitProperties.java`
- Create: `src/main/java/com/iol/ratelimiter/config/RateLimitConfig.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/iol/ratelimiter/config/RateLimitConfigTest.java`:

```java
package com.iol.ratelimiter.config;

import com.iol.ratelimiter.algorithm.RateLimiter;
import com.iol.ratelimiter.algorithm.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "rate-limiter.capacity=5",
        "rate-limiter.refill-rate=2.0"
})
class RateLimitConfigTest {

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private RateLimitProperties properties;

    @Test
    void rateLimiterBeanIsTokenBucketImplementation() {
        assertThat(rateLimiter).isInstanceOf(TokenBucketRateLimiter.class);
    }

    @Test
    void propertiesAreBindCorrectly() {
        assertThat(properties.capacity()).isEqualTo(5.0);
        assertThat(properties.refillRate()).isEqualTo(2.0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=RateLimitConfigTest
```

Expected: `COMPILATION ERROR` — `RateLimitProperties` and `RateLimitConfig` do not exist yet.

- [ ] **Step 3: Create `src/main/java/com/iol/ratelimiter/config/RateLimitProperties.java`**

```java
package com.iol.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rate-limiter")
public record RateLimitProperties(
        @DefaultValue("10") double capacity,
        @DefaultValue("0.1667") double refillRate
) {}
```

- [ ] **Step 4: Create `src/main/java/com/iol/ratelimiter/config/RateLimitConfig.java`**

```java
package com.iol.ratelimiter.config;

import com.iol.ratelimiter.algorithm.RateLimiter;
import com.iol.ratelimiter.algorithm.TokenBucketRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public RateLimiter rateLimiter(RateLimitProperties properties) {
        return new TokenBucketRateLimiter(properties.capacity(), properties.refillRate());
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -Dtest=RateLimitConfigTest
```

Expected: `BUILD SUCCESS` — 2 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/iol/ratelimiter/config/ \
        src/test/java/com/iol/ratelimiter/config/RateLimitConfigTest.java
git commit -m "feat: configuration wiring — RateLimitProperties and RateLimitConfig"
```

---

## Task 5: RateLimitFilter

**Files:**
- Create: `src/test/java/com/iol/ratelimiter/web/RateLimitFilterIntegrationTest.java`
- Create: `src/main/java/com/iol/ratelimiter/web/RateLimitFilter.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/iol/ratelimiter/web/RateLimitFilterIntegrationTest.java`:

```java
package com.iol.ratelimiter.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "rate-limiter.capacity=3",
        "rate-limiter.refill-rate=100.0"   // fast refill so tests don't need to wait
})
class RateLimitFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returns401WhenNoApiKey() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing X-API-Key header"));
    }

    @Test
    void returns200WhenAllowed() throws Exception {
        mockMvc.perform(get("/ping").header("X-API-Key", "test-key-200"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    @Test
    void returns429WhenLimitExceeded() throws Exception {
        String apiKey = "test-key-429";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/ping").header("X-API-Key", apiKey))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/ping").header("X-API-Key", apiKey))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Rate limit exceeded"));
    }

    @Test
    void headersArePresentOn429() throws Exception {
        String apiKey = "test-key-headers";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/ping").header("X-API-Key", apiKey));
        }

        mockMvc.perform(get("/ping").header("X-API-Key", apiKey))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=RateLimitFilterIntegrationTest
```

Expected: `COMPILATION ERROR` — `RateLimitFilter` does not exist yet. After creating the filter, tests should initially fail because `PingController` (`/ping`) doesn't exist yet — that's expected, we fix it in Task 6.

- [ ] **Step 3: Create `src/main/java/com/iol/ratelimiter/web/RateLimitFilter.java`**

```java
package com.iol.ratelimiter.web;

import com.iol.ratelimiter.algorithm.RateLimitDecision;
import com.iol.ratelimiter.algorithm.RateLimiter;
import com.iol.ratelimiter.config.RateLimitProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties, MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    // Don't rate-limit actuator endpoints — Prometheus scrapes /actuator/prometheus without a key
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.isBlank()) {
            sendJson(response, HttpStatus.UNAUTHORIZED, "{\"error\": \"Missing X-API-Key header\"}");
            return;
        }

        try {
            RateLimitDecision decision = rateLimiter.check(apiKey);

            response.setHeader("X-RateLimit-Limit", String.valueOf((int) properties.capacity()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.tokensRemaining()));

            if (!decision.allowed()) {
                response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                counter("ratelimit.requests.denied", apiKey).increment();
                sendJson(response, HttpStatus.TOO_MANY_REQUESTS, "{\"error\": \"Rate limit exceeded\"}");
                return;
            }

            counter("ratelimit.requests.allowed", apiKey).increment();
            chain.doFilter(request, response);

        } catch (Exception e) {
            // Rate limiting must never take down the service
            log.warn("Unexpected error in rate limit filter, allowing request through", e);
            chain.doFilter(request, response);
        }
    }

    private void sendJson(HttpServletResponse response, HttpStatus status, String body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }

    // Counter.builder().register() is idempotent — Micrometer returns the cached counter on repeat calls
    private Counter counter(String name, String apiKey) {
        return Counter.builder(name)
                .tag("apiKey", apiKey)
                .register(meterRegistry);
    }
}
```

- [ ] **Step 4: Commit what we have (filter compiles, tests blocked on missing /ping)**

```bash
git add src/main/java/com/iol/ratelimiter/web/RateLimitFilter.java \
        src/test/java/com/iol/ratelimiter/web/RateLimitFilterIntegrationTest.java
git commit -m "feat: RateLimitFilter with X-API-Key check, RFC headers, and Micrometer counters"
```

---

## Task 6: PingController

**Files:**
- Create: `src/main/java/com/iol/ratelimiter/web/PingController.java`

- [ ] **Step 1: Run the integration tests to see current failures**

```bash
mvn test -Dtest=RateLimitFilterIntegrationTest
```

Expected: Tests fail with 404 — `/ping` endpoint doesn't exist yet.

- [ ] **Step 2: Create `src/main/java/com/iol/ratelimiter/web/PingController.java`**

```java
package com.iol.ratelimiter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class PingController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok", "timestamp", Instant.now().toString());
    }
}
```

- [ ] **Step 3: Run all tests to verify everything passes**

```bash
mvn test
```

Expected: `BUILD SUCCESS` — all tests pass (RateLimitDecisionTest, TokenBucketRateLimiterTest, RateLimitConfigTest, RateLimitFilterIntegrationTest, RateLimiterApplicationTests).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/iol/ratelimiter/web/PingController.java
git commit -m "feat: GET /ping demo endpoint for manual rate limit testing"
```

---

## Task 7: Docker Compose + Prometheus + Grafana

**Files:**
- Create: `Dockerfile`
- Create: `docker-compose.yml`
- Create: `prometheus.yml`
- Create: `grafana/provisioning/datasources/prometheus.yml`
- Create: `grafana/provisioning/dashboards/dashboard.yml`
- Create: `grafana/provisioning/dashboards/rate-limiter.json`

- [ ] **Step 1: Create `Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/rate-limiter-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Create `docker-compose.yml`**

```yaml
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - RATE_LIMITER_CAPACITY=10
      - RATE_LIMITER_REFILL_RATE=0.1667
    healthcheck:
      test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 3

  prometheus:
    image: prom/prometheus:v2.51.0
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
    depends_on:
      app:
        condition: service_healthy

  grafana:
    image: grafana/grafana:10.4.0
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
    depends_on:
      - prometheus
```

- [ ] **Step 3: Create `prometheus.yml`**

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: rate-limiter
    scrape_interval: 15s
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['app:8080']
```

- [ ] **Step 4: Create `grafana/provisioning/datasources/prometheus.yml`**

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

- [ ] **Step 5: Create `grafana/provisioning/dashboards/dashboard.yml`**

```yaml
apiVersion: 1
providers:
  - name: default
    folder: Rate Limiter
    type: file
    disableDeletion: true
    options:
      path: /etc/grafana/provisioning/dashboards
```

- [ ] **Step 6: Create `grafana/provisioning/dashboards/rate-limiter.json`**

```json
{
  "annotations": { "list": [] },
  "editable": true,
  "graphTooltip": 0,
  "links": [],
  "panels": [
    {
      "datasource": { "type": "prometheus", "uid": "${datasource}" },
      "fieldConfig": {
        "defaults": {
          "color": { "mode": "palette-classic" },
          "custom": { "lineWidth": 2 }
        },
        "overrides": []
      },
      "gridPos": { "h": 9, "w": 24, "x": 0, "y": 0 },
      "id": 1,
      "options": {
        "legend": { "calcs": ["lastNotNull"], "displayMode": "table", "placement": "bottom" },
        "tooltip": { "mode": "multi" }
      },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "expr": "sum by (apiKey) (rate(ratelimit_requests_allowed_total[1m]))",
          "legendFormat": "allowed — {{apiKey}}",
          "refId": "A"
        },
        {
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "expr": "sum by (apiKey) (rate(ratelimit_requests_denied_total[1m]))",
          "legendFormat": "denied — {{apiKey}}",
          "refId": "B"
        }
      ],
      "title": "Request Rate — Allowed vs Denied (per minute)",
      "type": "timeseries"
    },
    {
      "datasource": { "type": "prometheus", "uid": "${datasource}" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 9 },
      "id": 2,
      "options": { "footer": { "show": false }, "showHeader": true },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "expr": "sort_desc(sum by (apiKey) (ratelimit_requests_denied_total))",
          "format": "table",
          "instant": true,
          "legendFormat": "__auto",
          "refId": "A"
        }
      ],
      "title": "Total Denied Requests by Client",
      "transformations": [
        {
          "id": "organize",
          "options": {
            "excludeByName": { "Time": true, "__name__": true, "job": true, "instance": true }
          }
        }
      ],
      "type": "table"
    },
    {
      "datasource": { "type": "prometheus", "uid": "${datasource}" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 9 },
      "id": 3,
      "options": { "footer": { "show": false }, "showHeader": true },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "${datasource}" },
          "expr": "sort_desc(sum by (apiKey) (ratelimit_requests_allowed_total))",
          "format": "table",
          "instant": true,
          "legendFormat": "__auto",
          "refId": "A"
        }
      ],
      "title": "Total Allowed Requests by Client",
      "transformations": [
        {
          "id": "organize",
          "options": {
            "excludeByName": { "Time": true, "__name__": true, "job": true, "instance": true }
          }
        }
      ],
      "type": "table"
    }
  ],
  "refresh": "10s",
  "schemaVersion": 39,
  "tags": ["rate-limiter", "iol"],
  "templating": {
    "list": [
      {
        "current": {},
        "hide": 0,
        "includeAll": false,
        "name": "datasource",
        "options": [],
        "query": "prometheus",
        "refresh": 1,
        "type": "datasource"
      }
    ]
  },
  "time": { "from": "now-15m", "to": "now" },
  "timepicker": {},
  "timezone": "browser",
  "title": "Rate Limiter",
  "uid": "rate-limiter-iol",
  "version": 1
}
```

- [ ] **Step 7: Build the jar and verify the stack starts**

```bash
mvn clean package -DskipTests
docker-compose up --build
```

Expected:
- App starts on `http://localhost:8080`
- Prometheus UI at `http://localhost:9090` — verify target `rate-limiter` is `UP` under Status → Targets
- Grafana at `http://localhost:3000` (admin/admin) — "Rate Limiter" dashboard visible under Dashboards

- [ ] **Step 8: Smoke test metrics end-to-end**

```bash
# Generate some allowed + denied traffic
for i in $(seq 1 15); do curl -s -o /dev/null http://localhost:8080/ping -H "X-API-Key: trader-abc"; done

# Verify Prometheus is scraping metrics
curl -s http://localhost:8080/actuator/prometheus | grep ratelimit
```

Expected output contains:
```
ratelimit_requests_allowed_total{apiKey="trader-abc",...} 10.0
ratelimit_requests_denied_total{apiKey="trader-abc",...} 5.0
```

- [ ] **Step 9: Stop the stack and commit**

```bash
docker-compose down
git add Dockerfile docker-compose.yml prometheus.yml grafana/
git commit -m "feat: Docker Compose stack with Prometheus scraping and Grafana dashboard"
```

---

## Task 8: DESIGN.md (Challenge Deliverable)

**Files:**
- Create: `DESIGN.md`

- [ ] **Step 1: Create `DESIGN.md` at project root**

```markdown
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
```

- [ ] **Step 2: Run the full test suite one final time**

```bash
mvn verify
```

Expected: `BUILD SUCCESS` — all tests pass.

- [ ] **Step 3: Commit**

```bash
git add DESIGN.md
git commit -m "docs: add DESIGN.md — architectural choices, trade-offs, AI usage"
```

---

## Task 9: Loki + Promtail — Log Visualization in Grafana

**Files:**
- Create: `loki-config.yml`
- Create: `promtail-config.yml`
- Modify: `docker-compose.yml` — add `loki` and `promtail` services
- Create: `grafana/provisioning/datasources/loki.yml`
- Modify: `grafana/provisioning/dashboards/rate-limiter.json` — add logs panel

- [x] **Step 1: Create `loki-config.yml`**

```yaml
auth_enabled: false

server:
  http_listen_port: 3100
  grpc_listen_port: 9096

common:
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    instance_addr: 127.0.0.1
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2020-10-24
      store: tsdb
      object_store: filesystem
      schema: v12
      index:
        prefix: index_
        period: 24h

limits_config:
  reject_old_samples: true
  reject_old_samples_max_age: 168h
```

- [x] **Step 2: Create `promtail-config.yml`**

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: containers
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 5s
    relabel_configs:
      - source_labels: ['__meta_docker_container_name']
        regex: '/(.*)'
        target_label: container
      - source_labels: ['__meta_docker_container_log_stream']
        target_label: stream
    pipeline_stages:
      - docker: {}
```

- [x] **Step 3: Add Loki datasource to Grafana provisioning**

Create `grafana/provisioning/datasources/loki.yml`:
```yaml
apiVersion: 1
datasources:
  - name: Loki
    type: loki
    uid: loki
    url: http://loki:3100
    isDefault: false
    jsonData:
      maxLines: 1000
```

- [x] **Step 4: Add `loki` and `promtail` services to `docker-compose.yml`**

```yaml
  loki:
    image: grafana/loki:2.9.0
    ports:
      - "3100:3100"
    volumes:
      - ./loki-config.yml:/etc/loki/local-config.yaml:ro
    command: -config.file=/etc/loki/local-config.yaml
    depends_on:
      - app

  promtail:
    image: grafana/promtail:2.9.0
    volumes:
      - ./promtail-config.yml:/etc/promtail/config.yml:ro
      - /var/run/docker.sock:/var/run/docker.sock
    command: -config.file=/etc/promtail/config.yml
    depends_on:
      - loki
```

Also add `loki` and `promtail` to Grafana's `depends_on`.

- [x] **Step 5: Add logs panel to Grafana dashboard**

Add a fourth panel to `rate-limiter.json` (after the two table panels, `y: 17`):

```json
{
  "datasource": { "type": "loki", "uid": "loki" },
  "gridPos": { "h": 10, "w": 24, "x": 0, "y": 17 },
  "id": 4,
  "options": {
    "dedupStrategy": "none",
    "enableLogDetails": true,
    "showTime": true,
    "sortOrder": "Descending",
    "wrapLogMessage": false
  },
  "targets": [
    {
      "datasource": { "type": "loki", "uid": "loki" },
      "expr": "{container=~\".*app.*\"} |= \"\" | line_format \"{{.line}}\"",
      "refId": "A"
    }
  ],
  "title": "Application Logs",
  "type": "logs"
}
```

- [x] **Step 6: Commit**

```bash
git add loki-config.yml promtail-config.yml docker-compose.yml \
        grafana/provisioning/datasources/loki.yml \
        grafana/provisioning/dashboards/rate-limiter.json
git commit -m "feat: add Loki + Promtail for log visualization in Grafana"
```

---

## Final Verification

- [ ] `mvn verify` — all tests pass, jar builds
- [ ] `docker-compose up --build` — all five services start, Grafana dashboard visible
- [ ] `curl http://localhost:8080/ping -H "X-API-Key: test"` — returns `{"status":"ok",...}`
- [ ] After 10 requests with the same key: `curl` returns HTTP 429 with `Retry-After` header
- [ ] `http://localhost:9090` — Prometheus target `rate-limiter` shows `UP`
- [ ] `http://localhost:3000` — Grafana "Rate Limiter" dashboard shows allowed/denied counters and live logs
