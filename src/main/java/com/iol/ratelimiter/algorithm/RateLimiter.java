package com.iol.ratelimiter.algorithm;

public interface RateLimiter {
    /**
     * Checks whether the client identified by {@code clientId} is allowed to proceed.
     * Consumes one token if allowed.
     */
    RateLimitDecision check(String clientId);
}
