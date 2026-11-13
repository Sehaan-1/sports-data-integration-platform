# ADR-06 — Stateless JWT

Date: 2026-11-12
Status: Accepted

## Context

Need horizontal scalability of the API tier without sticky sessions.

## Decision

Stateless JWT (15 min access + 12 h rotating refresh); refresh tokens rejected
as bearer credentials; refresh rotation limits replay windows.

## Consequences

- Server-side session store not required; audit still emitted per mutation.
