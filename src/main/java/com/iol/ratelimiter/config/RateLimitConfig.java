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
