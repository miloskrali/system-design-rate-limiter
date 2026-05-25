package com.iol.ratelimiter.algorithm;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Token Bucket rate limiter. Each client gets an independent bucket.
 * Pure Java — no framework dependencies, instantiated directly in tests.
 *
 * Inactive buckets are evicted by a daemon thread every 5 minutes.
 * A bucket is considered stale if it has not been accessed for 1 hour.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    static final long EVICTION_TTL_NANOS     = TimeUnit.HOURS.toNanos(1);
    static final long EVICTION_INTERVAL_SECS = 300; // 5 minutes

    private final double capacity;
    private final double refillRatePerNano; // tokens per nanosecond
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(double capacity, double refillRatePerSecond) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0, got: " + capacity);
        if (refillRatePerSecond < 0) throw new IllegalArgumentException("refillRatePerSecond must be >= 0, got: " + refillRatePerSecond);
        this.capacity = capacity;
        this.refillRatePerNano = refillRatePerSecond / 1_000_000_000.0;
        startEvictionDaemon();
    }

    @Override
    public RateLimitDecision check(String clientId) {
        // computeIfAbsent is atomic — no two threads can create a bucket for the same key
        Bucket bucket = buckets.computeIfAbsent(clientId, k -> new Bucket(capacity));
        return bucket.tryConsume(capacity, refillRatePerNano);
    }

    /** Visible for testing. */
    int bucketCount() {
        return buckets.size();
    }

    private void startEvictionDaemon() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bucket-eviction-daemon");
            t.setDaemon(true); // does not block JVM shutdown
            return t;
        });
        scheduler.scheduleAtFixedRate(
                this::evictStaleBuckets,
                EVICTION_INTERVAL_SECS,
                EVICTION_INTERVAL_SECS,
                TimeUnit.SECONDS
        );
    }

    private void evictStaleBuckets() {
        long cutoff = System.nanoTime() - EVICTION_TTL_NANOS;
        buckets.entrySet().removeIf(e -> e.getValue().lastAccessTime() < cutoff);
    }
}
