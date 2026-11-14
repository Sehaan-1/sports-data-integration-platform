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

## Amendment — 2026-11-14

The redesigned workspace keeps the accepted zero-build architecture instead of
adding a framework migration. HTML, local CSS, ES modules, SVG charts/icons,
and self-hosted fonts ship in the jar. Public `/assets/**` access lets the
sign-in screen load without a JWT; `/api/**` authorization is unchanged.

Node 22 tooling is **development/test-only**: a static preview with an optional
same-origin API proxy, pure-helper tests, and Chromium/axe browser checks.
`?demo=1` explicitly selects isolated synthetic fixtures, never an automatic
fallback for a failed live request. No demo HTTP endpoints are exposed.

The previous planned React replacement is no longer a prerequisite for a
usable workspace. A future framework can still consume the same REST API.
See [frontend notes](../frontend.md).
