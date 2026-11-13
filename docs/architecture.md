# SDIP Architecture

Module map and flow detail for the Sports Data Integration Platform.

## Module map

```
sdip-parent
├── sdip-core-domain     entities, repos, EntityLinker, MetricDictionary, UnitConverter,
│                         CanonicalImportService (idempotent upserts), AuditService,
│                         CredentialCryptoService, RowScopeGuard, Flyway V1+V2
├── sdip-ingestion       Spring Batch job: streaming CSV/XLSX readers → mapping/validation
│                         processor → bulk upsert writer; skip/quarantine of corrupt rows;
│                         batch bookkeeping; default mapping profiles
├── sdip-connectors      Catapult/VALD HTTP clients (WebClient), Resilience4j policies,
│                         watermark SyncOrchestrator, scheduled sync
├── sdip-security        JWT (HS256 access+refresh, rotation), security filter chain,
│                         squad-scope provider, seeded dev users
└── sdip-api             REST surface (athletes, ingestion, links/quarantine, connectors,
                         dashboards, exports, admin), static dashboard, pg profile
```

Hard module boundaries enforced by ArchUnit tests (`ModuleBoundaryTest`):

- domain → {ingest, connectors, security, api} must not depend on each other.
- ingest → {api, connectors}.
- security → {ingest, connectors}.

## Ingest / sync flow

1. Vendor export (Catapult CSV / VALD CSV or XLSX) lands via `POST /api/ingest/files`
   (multipart) or via connector sync (`/api/connectors/**`).
2. Column-mapping profile (admin-editable) maps vendor columns → metric dictionary
   entries (units + aliases + range guards).
3. Spring Batch reader streams rows; processor validates; bulk upsert writer applies
   idempotent natural keys `(source_system, source_record_id)`.
4. Corrupt rows are skipped and recorded in `ingestion_row_error`; the file never aborts
   (comprehensive-file policy, skipLimit 100).
5. Watermark sync `[lastSyncedAt − overlapHours, now]` absorbs late vendor edits;
   watermark advances only on full success.

## Dashboard / evaluation flow

1. `GET /api/dashboard/team/load?days=N` — per-athlete aggregates (player load,
   distance, HSR, sessions) over a rolling window.
2. `GET /api/dashboard/athlete/{id}/load?days=42` — per-day athlete load + ACWR.
3. `GET /api/dashboard/athlete/{id}/fatigue-overlay?days=90` — GPS load × neuromuscular
   fatigue (VALD force tests: RSI-mod, asymmetry, peak force).

Entity resolution (C2) feeds the dashboards: deterministic tier → heuristic tier →
quarantine band → auto-link (`score ≥ τ_high 0.90`) or provisional athlete
(`score ≥ τ_low 0.68`); humans resolve the rest through the quarantine queue.

## Reproducing

- Full behaviour matrix: `.\mvnw.cmd -B verify` (H2 in demo mode; `pg` profile for
  PostgreSQL via Testcontainers).
- Demo path: `docker compose up -d` → seeded demo dataset → dashboard at
  `http://localhost:8080`.
