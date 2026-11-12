# SDIP — Local Reproducibility & Portfolio Hardening

**Date:** 2026-11-12
**Status:** awaiting approval
**Scope approved by:** project owner ("portfolio between and real", no hosting available yet)

---

## 1. Intent

SDIP is a genuinely well-built platform whose weakest claim is the one a reader checks
first: *that it runs*. Verified on the owner's machine today — it does not. There is no
`mvn` on PATH, `bin/env.sh` is bash-only and points at a `~/.cache/toolchain` directory
that does not exist, and `~/.m2` has never seen Spring Boot 3.5.0. The README's first
command fails at step zero.

The goal is therefore not "improve the code". It is: **make the repo prove its own
claims, on a clean machine, without the reader trusting the README.**

The code is already ahead of its packaging. The work below is packaging, evidence, and
operability — cheap, mechanical, and high-signal.

## 2. Definition of done (the acceptance test)

> On a machine with only **Git and Docker** installed, a stranger runs
> `git clone` → `docker compose up` → opens `http://localhost:8080`,
> signs in as `coach@sdip.local`, and sees four athletes with GPS and force-plate
> charts — in under ten minutes, with no JDK, no Maven, and no edits.

And, for developers and CI:

> `.\mvnw.cmd verify` (Windows) and `./mvnw verify` (Linux) pass from an **empty**
> `~/.m2`, on a clean checkout, with no environment variables set.

Both are executable tests, not aspirations. P0 and P1 below exist to make them true.

## 3. Verified current state

| Check | Result |
|---|---|
| `mvn -version` | **not found** — no Maven anywhere on the machine (`~/.cache/tooling` holds only Gradle) |
| `bin/env.sh` | bash-only; expects `~/.cache/toolchain/{jdk21,maven}` — **directory absent** |
| `java -version` | OpenJDK **25.0.2** (Temurin); `JAVA_HOME` points at it; project targets 21 |
| `docker --version` / compose | **Docker 29.4.0, Compose v5.1.1 — working** |
| `~/.m2/repository` | exists, but **no `spring-boot:3.5.0`** — project never built here |
| CI / `mvnw` / `.mvn` / `docs/` / `LICENSE` / `Dockerfile` | **all absent** |
| Testcontainers | declared in parent `pom.xml`, **used zero times** — PG path untested |
| Actuator | `management.endpoints...health` configured in `application.yml`, **dependency absent** → config is inert |
| Git | one commit ("Initial commit"), working tree clean |
| Size | 5 modules, ~7,100 Java lines, largest class 289 lines, 9 test classes / 36 tests claimed |

## 4. Goals

1. A clean machine with Git + Docker runs SDIP end-to-end, first try, no tribal knowledge.
2. A developer with JDK 21 and nothing else runs the full test suite via the wrapper.
3. The PostgreSQL path is exercised by an automated test, not asserted by a README.
4. The repo carries its own evidence: CI status, screenshots, a README a non-expert can follow.
5. The module boundaries in ADR-01 are enforced by a test rather than by prose.

## 5. Non-goals

- No React/SPA frontend. The zero-dependency dashboard stays; it gains screenshots and a
  short walkthrough, not a rewrite.
- No hosting, no cloud deployment, no domain (owner has nowhere to deploy yet).
- No multi-tenant onboarding, no admin UI, no RLS, no password reset.
- No architecture rewrite, no package reshuffle, no new features.
- No change to domain behaviour, thresholds, or the RBAC matrix.

## 6. Anti-goals (these would mean failure even if everything "works")

- Adding a second build system or a Node toolchain to solve a Java problem.
- Making the demo path require Docker **and** a manual Maven install.
- A README that is longer but no more verifiable.
- Green CI achieved by weakening or skipping tests.
- Cosmetic screenshots of an app that cannot be reproduced from the repo.

## 7. Constraints and decisions

**D1 — Docker is the only hard prerequisite.** The canonical "it runs" path is
`docker compose up` with a multi-stage image (Maven build inside the container). This
removes the host-Maven problem entirely and is what the acceptance test measures.
The Maven wrapper serves the developer path, not the demo path.

**D2 — Maven Wrapper, script-only.** Wrapper 3.3.x, no committed `maven-wrapper.jar`,
`distributionUrl` pinned to a specific Apache Maven 3.9.x. `mvnw.cmd` must work in
PowerShell/cmd with no Git-Bash installed.

**D3 — Toolchain contract.** Canonical JDK is **21** (Temurin), used by CI and the
Docker build. The build must also pass on the owner's **JDK 25**; if that requires
bumping Spring Boot, bump to the latest **3.5.x patch** (not 4.x) and re-run the suite.
If Java 25 cannot be supported cheaply, `mvnw` fails fast with an explicit,
human-readable message naming JDK 21 as required — silence is not acceptable.

**D4 — Cross-platform first.** Every documented command must work in PowerShell.
`bin/env.sh` and `demo/load-demo.sh` are replaced by equivalents in **both** shells
(`.ps1` + `.sh`), or eliminated in favour of Docker. No command in the README may
assume bash.

**D5 — GitHub is assumed** for CI (`ubuntu-latest`, Temurin 21, `./mvnw verify`).
If the repo will not be pushed to GitHub, P1's CI step degrades to a local
`verify.ps1`/`verify.sh` pair. This is the one assumption the owner should confirm.

**D6 — Nothing existing breaks.** All 36 tests keep passing. Any test changed for
portability is changed for portability only.

**D7 — Evidence over prose.** Where the README makes a numeric claim (throughput,
precision/recall), the number must either be reproduced by a command in the repo or be
relabelled as a measured result from a specific run, with the caveat stated.

**D8 — The demo seeds itself.** `docker compose up` must produce a *populated* dashboard
with no second command and no manual upload. Implemented as a `demo` Spring profile that
idempotently seeds the four athletes + 7 GPS sessions + 12 VALD tests on startup when the
tables are empty (`sdip.seed.demo-data=true`, no-op when `false` or when data exists).
The CSV fixtures in `demo/` remain the source of truth; `load-demo.sh/.ps1` become
optional developer conveniences, not part of the acceptance path.

**D9 — Compose runs the graded profile.** The compose app service sets
`SPRING_PROFILES_ACTIVE=pg` and `SDIP_SEED_DEFAULT_USERS=true` (demo only) so the
PostgreSQL path, not H2, is what a reader's very first run exercises. This is the whole
point: the path that is documented is the path that is proven.

## 8. The work

### P0 — Builds and runs from clean (blocking; everything else depends on it)

1. Commit Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`,
   `.mvn/jvm.config` if needed). Verify: empty `~/.m2` → `mvnw -v` works on Windows.
2. Resolve the JDK question (D3). Verify: `mvnw verify` green on JDK 25 and JDK 21.
3. Multi-stage `Dockerfile` **at the repo root** (build context `.`, because the API jar
   needs all five modules): stage 1 `maven:3.9-eclipse-temurin-21` runs the repo's own
   wrapper to build; stage 2 `eclipse-temurin:21-jre` runs `sdip-api/target/sdip-api-1.0.0.jar`
   as a non-root user, with a `HEALTHCHECK`.
4. Extend `docker-compose.yml` to app + Postgres: PG healthcheck, `depends_on:
   service_healthy`, named volume for PG data, `SPRING_PROFILES_ACTIVE=pg` and demo
   seeding per D8/D9, env supplied from `.env` with `.env.example` committed. One command
   starts everything; a second `docker compose down -v` returns to clean.
5. Replace bash-only scripts per D4/D8: seeding moves into the `demo` profile; delete
   `bin/env.sh` (it encodes a toolchain path that does not exist) and add
   `demo/load-demo.ps1` beside `load-demo.sh` only if manual re-loading is still useful
   after D8 (if not, delete both and say so in the README).
6. Rewrite §2 of the README so its commands are exactly the ones that were tested.

**Acceptance:** on a machine with Docker only, `git clone && docker compose up` yields a
reachable, seeded, signed-in-able app. On the owner's machine, `.\mvnw.cmd verify` is green
from an empty `~/.m2`.

### P1 — Proves itself

7. GitHub Actions workflow: Temurin 21, `./mvnw -B verify`, Maven cache, test-report
   artifact, README badge.
8. One **Testcontainers**-backed PostgreSQL integration test (`PostgreSQLContainer`,
   `@ServiceConnection` or explicit props) that runs the Flyway migrations and the
   canonical import path against real PG. Gated by Docker availability so `mvnw verify`
   still works without it (JUnit 5 `@EnabledIf`/`disabledWithoutDocker`), but **fails,
   not skips, in CI**.
9. Actuator: add `spring-boot-starter-actuator`, expose `health` (+ `info`), wire a real
   readiness/liveness, secure the endpoint appropriately. Or — if it adds nothing —
   delete the inert config. Decide, don't leave the current half-state.
10. ArchUnit test asserting the module dependency direction of ADR-01
    (ingestion → core; connectors → core; security ↔ core; api → all), so the claim is
    executable.
11. `LICENSE` (owner's choice; default recommendation: MIT), `.editorconfig`,
    `.gitattributes` (LF for `.sh`, CRLF-safe).

**Acceptance:** a public repo shows a passing badge; `mvnw verify` on a Docker host runs
the PG-backed test; a deliberate boundary violation makes the build fail.

### P2 — Presents itself

12. README rewritten as a landing page: one-paragraph what/why, the 30-second demo
    (command + screenshots + login credentials), the architecture diagram, the API/RBAC
    table, then the design notes moved to `docs/`.
13. `docs/architecture.md` (module map, request/ingest/sync flows), `docs/adr/` (the six
    ADRs from the README, one file each), `docs/evaluation.md` (RQ table with the exact
    reproducing command per row).
14. Screenshots: login, dashboard with charts, batch list, quarantine portal — captured
    from the Docker run, committed under `docs/images/`, referenced from the README.
15. Honest-limits section carried forward verbatim in spirit: H2 vs PG, the 3,810 rows/s
    measurement's context, the gated heap test.

**Acceptance:** a reader who opens only the README can decide in 60 seconds whether to
clone; a reader who clones succeeds without reading `docs/`.

## 9. Risks

- **R1 — Boot 3.5.0 on JDK 25.** Medium likelihood, blocking. Mitigation: patch bump to
  latest 3.5.x; fallback per D3 (document JDK 21, fail fast on unsupported versions).
- **R2 — Maven Wrapper bootstrap.** No Maven exists to *generate* the wrapper. Mitigation:
  commit the script-only wrapper files directly (they self-download Maven); no host
  Maven needed.
- **R3 — Testcontainers on this machine.** Docker is present, but Windows/Docker Desktop
  can make container networking slow. Mitigation: gate the test locally, always run in CI.
- **R4 — Docker image build time** makes `compose up` feel slow on first run. Mitigation:
  build stage caching + documented expected time.
- **R5 — README numbers.** The 3,810 rows/s and 0.9981 precision figures were measured in
  a sandbox; P2 must not present them as universal. Mitigation: D7.

## 10. Left to the builder

- Exact Maven/Boot patch versions (verify, don't guess).
- Whether demo seeding becomes a startup flag or stays a script (prefer flag).
- Whether `health` is public or authenticated.
- File/dir names inside `docs/`.
- LICENSE choice, if the owner has no preference.

## 11. Out of scope (recorded so it is not re-litigated)

React SPA · hosting/deployment · multi-tenant onboarding · admin UI · password reset ·
PostgreSQL RLS · restart-from-checkpoint for failed batches · Fellegi–Sunter tier ·
`Teamworks` connector · PDF exports.
