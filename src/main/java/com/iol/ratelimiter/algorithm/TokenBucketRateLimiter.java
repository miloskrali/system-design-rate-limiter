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
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0, got: " + capacity);
        if (refillRatePerSecond < 0) throw new IllegalArgumentException("refillRatePerSecond must be >= 0, got: " + refillRatePerSecond);
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
