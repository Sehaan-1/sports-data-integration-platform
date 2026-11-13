# ADR-05 — Java 21 + Boot 3.5.x

Date: 2026-11-12
Status: Accepted

## Context

Need LTS baseline with managed dependency versions; Spring Boot 4 not yet
production-proven for the stack.

## Decision

Java 21 LTS + Spring Boot 3.5.x (Boot manages Spring Batch 5.2); Spring Batch
pin aligned to Boot-managed version.

## Consequences

- Verified on both canonical JDK 21 (Temurin, via Docker) and host JDK 25.
