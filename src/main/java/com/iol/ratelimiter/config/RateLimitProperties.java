package com.iol.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rate-limiter")
public record RateLimitProperties(
        @DefaultValue("10") double capacity,
        @DefaultValue("0.1667") double refillRate
) {}
