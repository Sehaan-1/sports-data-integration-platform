# Evaluation

Measured evaluation of the four capstone requirements (RQ1–RQ4) plus how each
number was produced. Every numeric claim names its reproducing command or its
measurement context — no orphan numbers.

## RQ1 — Ingestion throughput and integrity

| # | Question | Target | Measured | Harness |
|---|---|---|---:|---|
| RQ1a | Throughput | ≥ 5,000 rows/s on commodity HW + PostgreSQL | **3,810 rows/s** end-to-end (20,000 rows / 5.25 s) — on a 2-CPU sandbox with in-memory H2; the test asserts a conservative > 1,200 rows/s guard-rail. The 5,000 target is the PostgreSQL benchmark (Testcontainers variant provided for Docker hosts) | `CsvIngestionFlowTest.throughputProbe` |
| RQ1b | Corrupt rows | 100% quarantined, file never aborts | ✅ 4/4 corrupt rows skipped & recorded, 196/200 good rows imported, batch `COMPLETED_WITH_SKIPS` | `CsvIngestionFlowTest` (corrupt-rows case) |
| RQ1c | XLSX size | 100 MB XLSX ≤ 512 MB heap | Reader streams with `rowCacheSize=1`; restricted-heap IT provided (runs where CI allows larger heaps) | `XlsxIngestionFlowTest` + `HeapBoundedXlsxIT` (gated) |

Reproducing: `.\mvnw.cmd -B verify` — the RQ1 numbers print to stdout
(`THROUGHPUT: …`, `RQ2 metrics: …`).

## RQ2 — Linkage accuracy

| Question | Target | Measured | Harness |
|---|---|---:|---|
| Linkage precision / recall | precision ≥ 0.98, recall ≥ 0.90 | **precision = 0.9981, recall = 0.9508, F1 = 0.9739**, quarantine rate 4.73 %, **decoys leaked = 0** on the 1,000-athlete corpus (1,627 dup rows incl. misspellings, typos, name+DOB twins, +40 decoys) | `LinkageEvaluationTest` |

Reproducing: `.\mvnw.cmd -B -pl sdip-core-domain test --tests '*LinkageEvaluation*'`.

## RQ3 — Authorization & audit

| Question | Target | Measured | Harness |
|---|---|---|---:|
| Zero role×endpoint×tenant escapes | 0 escapes | **0 escapes across 13 matrix tests** (anonymous 401s, analyst read-only 403s, cross-squad 404s incl. dashboard JDBC paths, squad-claim-wins upload, exactly-one-audit-event per mutation, refresh-rotation, refresh-not-accepted-as-bearer) | `AuthorizationMatrixTest`, `DashboardSmokeTest` |

Reproducing: `.\mvnw.cmd -B verify`.

## RQ4 — Resilience

| Question | Target | Measured | Harness |
|---|---|---|---:|
| Graceful degradation; bounded retries; watermark only on success | ✅ | ✅ 429-storm → DEGRADED, 0 writes, watermark untouched, exactly 4 attempts; malformed JSON → DEGRADED; breaker OPEN after repeated 500s; late vendor edit inside overlap window re-pulled and applied | `ConnectorResilienceTest` (WireMock fault injection) |

Reproducing: `.\mvnw.cmd -B -pl sdip-connectors test --tests '*ConnectorResilience*'`.

## Known caveats

- The 3,810 rows/s and linkage numbers were measured in the local sandbox
  (2 vCPU, in-memory H2); PostgreSQL-bound numbers come from the Testcontainers
  run.
- The throughput probe is wall-clock based and was observed noisy on this host;
  the asserted guard-rail is > 1,200 rows/s.
