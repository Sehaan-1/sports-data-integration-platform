# ADR-01 — Modular monolith

Date: 2026-11-12
Status: Accepted

## Context

The capstone must deliver cross-vendor ingestion, identity resolution, security,
and dashboards as one product without enterprise plumbing overhead.

## Decision

Five Maven modules with hard package boundaries: domain, ingestion, connectors,
security, api.

## Consequences

- Complexity budget went into data-correctness, not distributed plumbing.
- Boundaries are enforced by ArchUnit tests in CI.
