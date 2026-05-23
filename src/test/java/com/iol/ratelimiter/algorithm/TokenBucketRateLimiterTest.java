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
