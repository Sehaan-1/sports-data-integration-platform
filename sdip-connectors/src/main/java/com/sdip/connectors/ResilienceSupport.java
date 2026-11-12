package com.sdip.connectors;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Resilience4j policy surfaces for vendor calls (§4.1): exponential-backoff
 * retry on retryable faults, a circuit breaker that opens after repeated
 * failure, and a client-side rate limiter honouring vendor 429 semantics.
 * Registries are kept per vendor so breaker state persists across calls and
 * is observable in tests (RQ4).
 */
@Component
public class ResilienceSupport {

    private static final Logger log = LoggerFactory.getLogger(ResilienceSupport.class);

    private final Map<String, Retry> retries = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public <T> T execute(String vendor, double requestsPerSecond, Supplier<T> supplier) {
        Retry retry = retries.computeIfAbsent(vendor, v -> Retry.of(v, RetryConfig.custom()
                .maxAttempts(4)
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialBackoff(Duration.ofMillis(400), 2.0))
                .retryOnException(e -> e instanceof VendorApiException vex && vex.isRetryable())
                .build()));

        CircuitBreaker breaker = breakers.computeIfAbsent(vendor, v -> CircuitBreaker.of(v,
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(6)
                        .minimumNumberOfCalls(4)
                        .failureRateThreshold(50.0f)
                        .waitDurationInOpenState(Duration.ofSeconds(5))
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .build()));

        RateLimiter limiter = limiters.computeIfAbsent(vendor, v -> RateLimiter.of(v,
                RateLimiterConfig.custom()
                        .limitForPeriod(Math.max(1, (int) Math.ceil(requestsPerSecond)))
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ofSeconds(10))
                        .build()));

        Supplier<T> decorated = () -> supplier.get();
        decorated = RateLimiter.decorateSupplier(limiter,
                CircuitBreaker.decorateSupplier(breaker,
                        Retry.decorateSupplier(retry, decorated)));
        try {
            return decorated.get();
        } finally {
            log.debug("{} breaker={} metrics: success={} failure={}",
                    vendor, breaker.getState(),
                    breaker.getMetrics().getNumberOfSuccessfulCalls(),
                    breaker.getMetrics().getNumberOfFailedCalls());
        }
    }

    public CircuitBreaker.State breakerState(String vendor) {
        CircuitBreaker cb = breakers.get(vendor);
        return cb != null ? cb.getState() : CircuitBreaker.State.CLOSED;
    }

    /** Test hook. */
    public void reset(String vendor) {
        retries.remove(vendor);
        breakers.remove(vendor);
        limiters.remove(vendor);
    }
}
