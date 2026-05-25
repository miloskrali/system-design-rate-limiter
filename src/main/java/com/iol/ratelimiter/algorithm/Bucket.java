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
    private long lastRefillTime;  // nanoseconds
    private long lastAccessTime;  // nanoseconds — used for eviction

    Bucket(double initialTokens) {
        this.tokens = initialTokens;
        long now = System.nanoTime();
        this.lastRefillTime = now;
        this.lastAccessTime = now;
    }

    /**
     * Refills tokens based on elapsed time, then attempts to consume one token.
     *
     * @param capacity         max tokens the bucket can hold
     * @param refillRatePerNano tokens added per nanosecond
     */
    synchronized RateLimitDecision tryConsume(double capacity, double refillRatePerNano) {
        lastAccessTime = System.nanoTime();
        refill(capacity, refillRatePerNano);

        if (tokens >= 1.0) {
            tokens -= 1.0;
            // cast to int — fractional tokens are truncated (conservative: reports fewer than actual)
            return new RateLimitDecision(true, (int) tokens, 0L);
        }

        // Time (in seconds) until the bucket has at least 1 token
        long retryAfterSeconds = refillRatePerNano > 0
                ? (long) Math.ceil((1.0 - tokens) / (refillRatePerNano * 1_000_000_000.0))
                : Long.MAX_VALUE;

        return new RateLimitDecision(false, 0, retryAfterSeconds);
    }

    synchronized long lastAccessTime() {
        return lastAccessTime;
    }

    private void refill(double capacity, double refillRatePerNano) {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTime;
        tokens = Math.min(capacity, tokens + elapsedNanos * refillRatePerNano);
        lastRefillTime = now;
    }
}
