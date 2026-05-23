package com.iol.ratelimiter.algorithm;

/**
 * Immutable result of a rate limit check.
 * Returned atomically from a single synchronized call — the filter
 * gets allowed status and all header values in one shot.
 */
public record RateLimitDecision(boolean allowed, int tokensRemaining, long retryAfterSeconds) {}
