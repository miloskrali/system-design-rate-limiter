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
