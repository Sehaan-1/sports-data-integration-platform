# ADR-02 — PostgreSQL aggregates-first

Date: 2026-11-12
Status: Accepted

## Context

Raw 10 Hz GPS ticks would multiply storage and query cost.

## Decision

Per-session aggregates persisted rather than raw ticks; TimescaleDB raw
hypertables left as a documented stretch; H2-in-PG-mode keeps the demo
dependency-free; `pg` profile + docker-compose is the graded target.

## Consequences

- Dashboard queries read aggregates; historical raw ticks remain available for
  debugging via the migration path.
