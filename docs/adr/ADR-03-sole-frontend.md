# ADR-03 — Sole frontend

Date: 2026-11-12
Status: Accepted

## Context

No frontend team or build budget for the capstone; evaluation needed a UI.

## Decision

Zero-dependency dashboard (`static/dashboard.html`: vanilla JS + inline SVG
charts, no CDN) ships in the jar; React+Chart.js SPA remains the planned
replacement consuming the same REST API.

## Consequences

- No Node build step in the demo path; swap-in SPA is API-compatible.
