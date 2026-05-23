package com.iol.ratelimiter.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "rate-limiter.capacity=3",
        "rate-limiter.refill-rate=0.001"   // very slow refill — bucket stays empty between MockMvc requests
})
class RateLimitFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returns401WhenNoApiKey() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing X-API-Key header"));
    }

    @Test
    void returns200WhenAllowed() throws Exception {
        mockMvc.perform(get("/ping").header("X-API-Key", "test-key-200"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    @Test
    void returns429WhenLimitExceeded() throws Exception {
        String apiKey = "test-key-429";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/ping").header("X-API-Key", apiKey))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/ping").header("X-API-Key", apiKey))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Rate limit exceeded"));
    }

    @Test
    void headersArePresentOn429() throws Exception {
        String apiKey = "test-key-headers";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/ping").header("X-API-Key", apiKey));
        }

        mockMvc.perform(get("/ping").header("X-API-Key", apiKey))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"));
    }
}
