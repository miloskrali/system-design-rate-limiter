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
