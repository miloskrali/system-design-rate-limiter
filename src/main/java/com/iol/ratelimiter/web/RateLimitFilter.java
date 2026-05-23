package com.iol.ratelimiter.web;

import com.iol.ratelimiter.algorithm.RateLimitDecision;
import com.iol.ratelimiter.algorithm.RateLimiter;
import com.iol.ratelimiter.config.RateLimitProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties, MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    // Don't rate-limit actuator endpoints — Prometheus scrapes /actuator/prometheus without a key
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.isBlank()) {
            sendJson(response, HttpStatus.UNAUTHORIZED, "{\"error\": \"Missing X-API-Key header\"}");
            return;
        }

        try {
            RateLimitDecision decision = rateLimiter.check(apiKey);

            response.setHeader("X-RateLimit-Limit", String.valueOf((int) properties.capacity()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.tokensRemaining()));

            if (!decision.allowed()) {
                response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                counter("ratelimit.requests.denied", apiKey).increment();
                sendJson(response, HttpStatus.TOO_MANY_REQUESTS, "{\"error\": \"Rate limit exceeded\"}");
                return;
            }

            counter("ratelimit.requests.allowed", apiKey).increment();
            chain.doFilter(request, response);

        } catch (Exception e) {
            // Rate limiting must never take down the service
            log.warn("Unexpected error in rate limit filter, allowing request through", e);
            chain.doFilter(request, response);
        }
    }

    private void sendJson(HttpServletResponse response, HttpStatus status, String body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }

    // Counter.builder().register() is idempotent — Micrometer returns the cached counter on repeat calls
    private Counter counter(String name, String apiKey) {
        return Counter.builder(name)
                .tag("apiKey", apiKey)
                .register(meterRegistry);
    }
}
