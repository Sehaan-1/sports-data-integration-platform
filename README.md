# SDIP — Sports Data Integration Platform

![CI](https://github.com/Sehaan-1/sports-data-integration-platform/actions/workflows/ci.yml/badge.svg)

A modular-monolith integration platform that ingests athlete monitoring data
from **Catapult** (GPS microtechnology) and **VALD** (ForceDecks strength
testing), resolves cross-vendor athlete identities safely, and serves unified
longitudinal dashboards under strict role/tenant scoping.

Built as a capstone against four concrete problems:

| # | Problem | How SDIP answers it |
|---|---------|---------------------|
| C1 | Vendor exports arrive as messy CSV/XLSX with shifting column names | Streaming Spring Batch pipeline (OpenCSV + SAX XLSX) with declarative, admin-editable **column-mapping profiles** over a governed **metric dictionary** (units + aliases + range guards) |
| C2 | The same athlete appears under different IDs in different systems | **Tiered entity resolution** (deterministic → heuristic Jaro–Winkler/Double-Metaphone/DOB with conservative thresholds → human quarantine queue); **reversible** links, never destructive merges |
| C3 | Medical-adjacent data needs least-privilege access | Stateless **JWT RBAC** (4 roles) + **squad row-scoping** (Hibernate filter + explicit SQL restriction + 404 on cross-tenant by-id) + **AES-GCM-256** credential vault + **append-only audit** |
| C4 | Vendor APIs throttle, fail, and send garbage | WebClient + Resilience4j (bounded retry w/ jitter, circuit breaker, rate limiter), **watermark sync with 24–48h overlap**, watermark advances **only** on full success |

---

## 1. Module layout (§4 of the proposal)

```
sdip-parent
├── sdip-core-domain    entities, repos, EntityLinker, MetricDictionary, UnitConverter,
│                       CanonicalImportService (idempotent upserts), AuditService,
│                       CredentialCryptoService, RowScopeGuard, Flyway V1+V2
├── sdip-ingestion      Spring Batch job: streaming CSV/XLSX readers → mapping/validation
│                       processor → bulk upsert writer; skip/quarantine of corrupt rows;
│                       batch bookkeeping; default mapping profiles
├── sdip-connectors     Catapult/VALD HTTP clients (WebClient), Resilience4j policies,
│                       watermark SyncOrchestrator, scheduled sync
├── sdip-security       JWT (HS256 access+refresh, rotation), security filter chain,
│                       squad-scope provider, seeded dev users
└── sdip-api            REST surface (athletes, ingestion, links/quarantine, connectors,
                        dashboards, exports, admin), static dashboard, pg profile
```

Stack: Java 21 LTS · Spring Boot 3.5 · Spring Batch 5.2 · PostgreSQL 16
(H2-in-PG-mode for the zero-dependency demo + tests) · Flyway · POI +
excel-streaming-reader (SAX) · OpenCSV · Spring Security 6 + JJWT ·
Resilience4j + WebClient · springdoc-OpenAPI · JUnit 5 + WireMock + MockMvc.

## 2. Quickstart

```bash
# toolchain: JDK 21 + Maven (bin/env.sh points at a local toolchain if present)
source bin/env.sh

# run all 36 tests (H2, no docker needed)
mvn verify

# build & run the self-contained demo (H2 in-memory, seeded demo users)
mvn package -DskipTests
java -jar sdip-api/target/sdip-api-1.0.0.jar

# in another shell: load the bundled demo data (4 athletes × 7 GPS sessions + 12 VALD tests)
./demo/load-demo.sh

# open the dashboard
http://localhost:8080/          (redirects to /dashboard.html — or /swagger-ui.html for the API)
```

Development users (tenant `SDIP-DEMO`; gated by `sdip.seed.default-users`):

| User | Password | Role | Squad scope |
|------|----------|------|-------------|
| admin@sdip.local | Admin#123 | ADMIN | all |
| coach@sdip.local | Coach#123 | HEAD_COACH | A Team |
| physio@sdip.local | Physio#123 | PRACTITIONER | A Team |
| analyst@sdip.local | Analyst#123 | ANALYST (read-only) | B Team |

### PostgreSQL 16 (the graded deployment)

```bash
docker compose up -d                                # postgres:16-alpine, db/user/pass = sdip
SPRING_PROFILES_ACTIVE=pg java -jar sdip-api/target/sdip-api-1.0.0.jar
```

The `pg` profile adds `V3__spring_batch_pg.sql` (official Spring Batch DDL)
and `V4__pg_audit_immutability.sql` (database rules that turn UPDATE/DELETE on
`audit_event` into no-ops — tamper resistance below the application layer).

### Production secrets (env)

| Variable | Purpose |
|----------|---------|
| `SDIP_JWT_SECRET_B64` | HS256 signing key (dev fallback is auto-logged as a warning) |
| `SDIP_CREDENTIAL_KEY_B64` | AES-GCM-256 key for the vendor-credential vault |
| `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD` | PostgreSQL connection |
| `SDIP_SEED_DEFAULT_USERS=false` | disable the demo users |

## 3. API & RBAC matrix

Interactive docs: `/swagger-ui.html`. Highlights:

| Endpoint | ADMIN | HEAD_COACH | PRACTITIONER | ANALYST |
|---|:-:|:-:|:-:|:-:|
| `POST /api/auth/login`, `/refresh` | ✅ | ✅ | ✅ | ✅ |
| `GET /api/athletes`, `/api/athletes/{id}` | all | squad-scoped | squad-scoped | squad-scoped |
| `GET /api/dashboard/**` | all | squad-scoped | squad-scoped | squad-scoped |
| `POST /api/ingest/files` (upload) | ✅ | ✅ | ✅ | 403 |
| `GET /api/ingest/batches[...]` | ✅ | ✅ | ✅ | ✅ |
| `POST /api/ingest/mapping-profiles` | ✅ | 403 | 403 | 403 |
| `GET /api/exports/athletes.csv` | ✅ | ✅ | 403 | 403 |
| `/api/links/**` (quarantine portal, confirm/reject/unlink) | ✅ | 403 | 403 | 403 |
| `/api/connectors/**` (manual sync, state) | ✅ | 403 | 403 | 403 |
| `/api/admin/**` (users, audit, squads) | ✅ | 403 | 403 | 403 |

Every mutating operation emits **exactly one** audit event
(`FILE_UPLOAD`, `LINK_CONFIRM`, `LINK_REJECT`, `LINK_UNLINK`,
`PERMISSION_CHANGE`, `EXPORT_DATA`, `CONNECTOR_SYNC[_FAILED]`,
`LOGIN`, `LOGIN_FAILED`, `TOKEN_REFRESH`, …).

## 4. Design notes

### 4.1 Entity resolution (C2, `EntityLinker`)
- **Tier 1a** — previously stored `(source_system, source_athlete_id)` link →
  stable re-confirmation; **idempotent quarantine** (re-ingestion of a pending
  identity returns the existing queue item instead of flooding it).
- **Tier 1b** — exact e-mail, **scoped to the record's squad** (email is
  unique *per squad* — see `uq_athlete_squad_email` — so cross-tenant identity
  can never collide or leak).
- **Tier 2** — composite score `0.52·JaroWinkler(name) + 0.14·DoubleMetaphone
  + 0.34·dobAgreement`, DOB-year blocking, ambiguity tie-break: two near-tied
  candidates ≥ τ_high are forced into the quarantine band rather than a coin
  flip. `score ≥ τ_high (0.90)` auto-links; `[τ_low 0.68, τ_high)` → **quarantine
  queue** for a human; `< τ_low` → **provisional athlete** (flagged
  `provisional=true`) so ingestion never blocks.
- Links are **reversible**: unlink revokes the row (kept for audit) and frees
  the natural key by appending `::revoked-<id>`; nothing is ever hard-deleted.

### 4.2 Ingestion pipeline (C1)
Streaming reader → per-row map/validate processor → 500-row chunk writer:
- corrupt rows (bad date/unit/range) are **skipped, recorded in
  `ingestion_row_error`, and never abort the file** (comprehensive-file
  policy, skipLimit 100);
- natural keys `(source_system, source_record_id)` make every fact row
  **idempotent** — re-uploading the same file yields zero duplicates
  (verified live: 28, 28, 28 written → exactly 28 rows in `gps_metric`);
- every row carries lineage (`ingestion_batch_id` → immutable batch log).

### 4.3 Connector sync (C4)
`[lastSyncedAt − overlapHours, now]` fetch window absorbs late vendor edits
(idempotent upserts make replays free); watermark advances only when the whole
window succeeds. 429/5xx → retryable, bounded (4 attempts, exp. backoff ×2
from 400 ms), circuit breaker (50 % failure over ≥4 calls opens 5 s), rate
limiter per vendor, OAuth2 client-credentials with token caching (VALD),
static token (Catapult).

### 4.4 Security (C3)
Stateless JWT (15 min access + 12 h rotating refresh; refresh tokens are
rejected as bearer credentials), method-level `@PreAuthorize` on every
controller, squad restriction pushed into Hibernate `squadFilter` for JPQL and
**explicit SQL parameters for JdbcTemplate paths** (defense in depth; a
cross-squad by-id read is a 404, not a 403 — existence isn't leaked).
Vendor credentials are AES-GCM-256 encrypted at rest; athlete names/emails are
the only PII stored (GDPR Art. 9 / DPDP 2023 minimisation).

## 5. Measured evaluation (§8)

| RQ | Target | Measured (this sandbox: 2 vCPU, H2-PG) | Harness |
|----|--------|-----------------------------------------|---------|
| **RQ1** throughput | ≥ 5,000 rows/s on commodity HW + PG | **3,810 rows/s** end-to-end (20 000 rows / 5.25 s) — on a 2-CPU sandbox with in-memory H2; the test asserts a conservative > 1,200 rows/s guard-rail. The 5,000 target is a PostgreSQL benchmark (Testcontainers variant provided for Docker hosts) | `CsvIngestionFlowTest.throughputProbe` |
| **RQ1** corrupt rows | 100 % quarantined, file never aborts | ✅ 4/4 corrupt rows skipped & recorded, 196/200 good rows imported, batch = `COMPLETED_WITH_SKIPS` | `CsvIngestionFlowTest.corruptRows…` |
| **RQ1** 100 MB XLSX ≤ 512 MB heap | stream-only SAX reader | Reader streams with `rowCacheSize=1`; restricted-heap IT provided (runs where CI allows larger heaps) | `XlsxIngestionFlowTest` + `HeapBoundedXlsxIT` (gated) |
| **RQ2** linkage | precision ≥ 0.98, recall ≥ 0.90 | **precision = 0.9981, recall = 0.9508, F1 = 0.9739**, quarantine rate 4.73 %, **decoys leaked = 0** on the 1 000-athlete corpus (1 627 dup rows incl. misspellings, typos, name+DOB twins, +40 decoys) | `LinkageEvaluationTest` |
| **RQ3** authZ | zero escapes in role×endpoint×tenant matrix | **0 escapes across 13 matrix tests** (anonymous 401s, analyst read-only 403s, cross-squad 404s incl. dashboard JDBC paths, squad-claim-wins upload, exactly-one-audit-event per mutation, refresh-rotation, refresh-not-accepted-as-bearer) | `AuthorizationMatrixTest`, `DashboardSmokeTest` |
| **RQ4** resilience | graceful degradation; bounded retries; watermark only on success | ✅ 429-storm → DEGRADED, 0 writes, watermark untouched, exactly 4 attempts; malformed JSON → DEGRADED; breaker OPEN after repeated 500s; late vendor edit inside overlap window re-pulled and applied | `ConnectorResilienceTest` (WireMock fault injection) |

Reproduce: `mvn verify` — the RQ1/RQ2 numbers are printed to stdout
(`THROUGHPUT: …`, `RQ2 metrics: …`).

## 6. Design decisions (ADRs, Appendix)

1. **ADR-01 Modular monolith** — five Maven modules with hard package
   boundaries; the complexity budget went into data-correctness, not
   distributed plumbing.
2. **ADR-02 PostgreSQL aggregates-first** — per-session aggregates, not raw
   10 Hz ticks (~1.2 M rows/session avoided); TimescaleDB raw hypertables are
   a documented stretch. H2-PG-mode keeps the demo dependency-free; `pg`
   profile + docker-compose is the graded target.
3. **ADR-03 Sole frontend** — a zero-dependency dashboard
   (`static/dashboard.html`: vanilla JS + inline SVG charts, no CDN) ships in
   the jar so capstone evaluation needs no Node build. The React+Chart.js SPA
   remains the planned replacement (proposal §5) and consumes the same REST
   API.
4. **ADR-04 WebClient + Resilience4j** for vendor I/O; virtual threads
   enabled for request handling.
5. **ADR-05 Java 21 + Boot 3.5.x** baseline (LTS; Boot 3.5 manages Batch 5.2).
6. **ADR-06 Stateless JWT** — server stays horizontally scalable; refresh
   rotation limits replay windows.

## 7. Known limitations / backlog (stretch items from §10)

- **Restart bit** for failed mid-file jobs: the lineage makes re-runs safe
  (idempotent), but resume-from-checkpoint isn't wired to the UI.
- **Fellegi–Sunter probabilistic tier** (Tier 3) — designed hook points exist
  (`EntityLinker.score`), not activated.
- **100 MB XLSX heap test** is provided as a restricted-heap IT suited to CI;
  the sandbox's 2 GB budget runs the streaming path with smaller fixtures.
- **PostgreSQL RLS** as a second enforcement layer under the Hibernate filter
  (migration sketch in mind; the application-level scoping is what RQ3 tests).
- **Teamworks connector**, PDF exports, per-athlete wellness ingestion UI.

## 8. Repository map

```
bin/env.sh                 toolchain bootstrap (JDK 21 / Maven under ~/.cache)
demo/                      demo CSVs + load-demo.sh
docker-compose.yml         PostgreSQL 16
sdip-api/src/main/resources/static/dashboard.html   zero-dependency UI
docs? → this README has it all
```

All tests green: **36/36** (`mvn verify` ≈ 100 s on this sandbox).
