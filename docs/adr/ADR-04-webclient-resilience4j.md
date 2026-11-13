# ADR-04 — WebClient + Resilience4j

Date: 2026-11-12
Status: Accepted

## Context

Vendor APIs throttle, fail, and send garbage; resilience must be bounded.

## Decision

WebClient + Resilience4j: bounded retry with jitter, circuit breaker, rate
limiter; virtual threads enabled for request handling.

## Consequences

- Watermark advances only on full success; retries are bounded and auditable.
