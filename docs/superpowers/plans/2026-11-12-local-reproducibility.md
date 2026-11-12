# SDIP Local Reproducibility — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make SDIP run and prove itself on a clean machine — Git + Docker only — and on a developer machine with JDK 21 and nothing else.

**Architecture:** A Maven Wrapper removes the host-Maven requirement; a multi-stage Docker image plus an extended compose file makes `docker compose up` the one-command demo, running the `pg` profile against real PostgreSQL; a `demo` seeding runner populates the dashboard through the existing ingestion service so no second command is needed. CI runs the wrapper on Temurin 21, a Testcontainers test exercises PostgreSQL, and an ArchUnit test enforces the module boundaries ADR-01 claims.

**Tech Stack:** Java 21 · Spring Boot 3.5.x · Maven Wrapper 3.3 · Docker/Compose · PostgreSQL 16 · Testcontainers · ArchUnit · GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-12-local-reproducibility-design.md`

## Global Constraints

- Canonical JDK: **21 (Temurin)**. Spring Boot stays on **3.5.x** — never 4.x. The build must also pass on the owner's **JDK 25**; if it cannot, `mvnw` must fail with an explicit message naming JDK 21.
- Every documented command must work in **PowerShell** on Windows. No command may assume bash.
- No behaviour change to domain logic, thresholds, or the RBAC matrix. All existing tests keep passing.
- New dependencies allowed: `spring-boot-starter-actuator` (runtime), `archunit-junit5` (test), `testcontainers:postgresql` + `junit-jupiter` (test). Nothing else without saying so.
- Docker is the only hard prerequisite of the demo path. A manual JDK or Maven install is never required for `docker compose up`.
- Secrets enter containers through `.env`; `.env.example` is committed and `.env` is git-ignored.
- Commits are small and conventional (`build:`, `test:`, `feat:`, `ci:`, `docs:`).

## Review Focus

These are implied by the spec but not covered by any single task's tests; each gets pinned in the task named.

1. **Second `docker compose up` / container restart must not duplicate demo data** — a reader will restart. Pinned in Task 3.
2. **Port 8080 already in use** must produce a clear compose failure, not a half-started stack. Pinned in Task 4.
3. **An existing Postgres volume from a previous schema** must not break startup silently — Flyway behaviour must be observable in logs. Pinned in Task 4.
4. **JDK 25 at runtime, not just compile time** — the jar must boot, not merely build. Pinned in Task 2.
5. **Windows line endings** in committed `.sh`/`.csv` and the `.gitattributes` guarantee. Pinned in Task 1.

---

### Task 1: Maven Wrapper

**Files:**
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitattributes`, `.editorconfig`
- Modify: `.gitignore`

**Interfaces:**
- Produces: `./mvnw` and `.\mvnw.cmd` that download Apache Maven 3.9.x on first run and execute the standard lifecycle with no host Maven.

- [ ] **Step 1: Add the script-only wrapper files**

`maven-wrapper.properties` pins an exact Maven version (`distributionUrl=https\://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.x/apache-maven-3.9.x-bin.zip`) and `wrapperVersion=3.3.2`. Use the official script-only `mvnw`/`mvnw.cmd` — no `maven-wrapper.jar` is committed.

- [ ] **Step 2: Verify on Windows with no Maven and an empty repository**

Run: `$env:MAVEN_USER_HOME="$env:TEMP\mvn-clean"; .\mvnw.cmd -v`
Expected: prints `Apache Maven 3.9.x` and `Java version: 25` (or 21), exit code 0. Delete `$env:TEMP\mvn-clean` afterwards.

- [ ] **Step 3: Add line-ending and editor guarantees**

`.gitattributes` sets `* text=auto`, `*.sh text eol=lf`, `mvnw text eol=lf`, `*.cmd text eol=crlf`, `*.csv text eol=lf`. `.editorconfig` sets UTF-8, final newline, 4-space Java indent, 2-space YAML.

- [ ] **Step 4: Run the full build through the wrapper**

Run: `.\mvnw.cmd -B verify`
Expected: `BUILD SUCCESS`, 36 tests. If the build fails for a reason unrelated to the wrapper, record it and proceed — Task 2 owns compatibility fixes.

- [ ] **Step 5: Commit**

```bash
git add mvnw mvnw.cmd .mvn .gitattributes .editorconfig .gitignore
git commit -m "build: add Maven wrapper and repo-level editor conventions"
```

---

### Task 2: JDK 25 compatibility gate

**Files:**
- Modify: `pom.xml` (Boot version property, and any plugin version needed)
- Create: `.mvn/jvm.config` only if a JVM flag is objectively required

**Interfaces:**
- Produces: a build that passes on JDK 21 and JDK 25, or a wrapper that refuses unsupported JDKs with a clear message.

- [ ] **Step 1: Establish the baseline**

Run: `.\mvnw.cmd -B verify`
Expected: record the exact failure or success. Any failure here is the input to Step 2.

- [ ] **Step 2: Bump Boot to the latest 3.5.x patch**

Change only `spring-boot.version` in `pom.xml` to the newest released 3.5.x (verify on Maven Central; do not guess). Re-run `verify`.

- [ ] **Step 3: Verify on the canonical JDK**

Run the same build with `JAVA_HOME` pointed at a JDK 21 (Temurin), e.g.
`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21...'; .\mvnw.cmd -B verify`
Expected: `BUILD SUCCESS` on both JDKs.

- [ ] **Step 4: Add the fail-fast guard if 25 still fails**

Create `bin/check-jdk.ps1` and have `mvnw.cmd` call it before invoking Maven when the current JDK is > 21 and the build is known-unsupported: print `SDIP requires JDK 21. Set JAVA_HOME to a JDK 21 before building.` and exit 1. Mirror the check in `mvnw` for bash. If Step 3 passes on 25, skip this step and say so in the commit message.

- [ ] **Step 5: Boot the jar on JDK 25 (runtime, not just compile)**

Run: `.\mvnw.cmd -B -DskipTests package; java -jar sdip-api/target/sdip-api-1.0.0.jar`
Expected: application starts, `Started SdipApplication`, then stop it with Ctrl-C. A compile-only pass is not sufficient for this step.

- [ ] **Step 6: Commit**

```bash
git add pom.xml bin/check-jdk.ps1 bin/check-jdk.sh mvnw mvnw.cmd
git commit -m "build: support JDK 25 alongside the canonical JDK 21"
```

---

### Task 3: Demo data seeds itself

**Files:**
- Create: `sdip-api/src/main/java/com/sdip/api/demo/DemoDataSeeder.java`
- Modify: `sdip-api/pom.xml` (add `../demo` as a resource directory), `sdip-api/src/main/resources/application.yml`
- Test: `sdip-api/src/test/java/com/sdip/api/DemoDataSeederTest.java`

**Interfaces:**
- Consumes: `com.sdip.api.ingest.IngestionAdminService` (the service `IngestionController` calls) and the checked-in fixtures `demo/catapult-sessions.csv`, `demo/vald-forcedecks.csv`.
- Produces: `DemoDataSeeder` implementing `ApplicationRunner`, constructed with `IngestionAdminService`, an actor `AppUser`, and `@Value("${sdip.seed.demo-data:false}") boolean enabled`. Behaviour: if disabled, or if `gps_metric` already holds rows, do nothing; otherwise submit both CSVs through the ingestion service exactly once.

- [ ] **Step 1: Make the fixtures reachable from the classpath**

Add to `sdip-api/pom.xml`:
```xml
<resource>
  <directory>${project.basedir}/../demo</directory>
  <targetPath>demo</targetPath>
  <includes><include>*.csv</include></includes>
</resource>
```
Verify: `.\mvnw.cmd -B -pl sdip-api process-resources` then confirm both CSVs exist under `sdip-api/target/classes/demo/`.

- [ ] **Step 2: Write the failing idempotency test**

`DemoDataSeederTest` (Spring Boot test, H2 profile, `sdip.seed.demo-data=true`): assert that after the context starts, `gps_metric` and `force_test` are non-empty; call `seeder.run(null)` a second time and assert the row counts are unchanged. Assert `athlete` has 4 rows.

Run: `.\mvnw.cmd -B -pl sdip-api test -Dtest=DemoDataSeederTest`
Expected: FAIL — `DemoDataSeeder` does not exist.

- [ ] **Step 3: Implement `DemoDataSeeder`**

Follow the existing `com.sdip.security.UserSeedRunner` pattern (constructor injection of a `boolean enabled`, guard at the top of `run`). Build an in-memory `MultipartFile` from each classpath CSV and hand it to `IngestionAdminService` with the seeded admin as actor. Log one line: `Seeded demo dataset (4 athletes).` Do not parse CSV by hand — the ingestion pipeline owns that.

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\mvnw.cmd -B -pl sdip-api test -Dtest=DemoDataSeederTest`
Expected: PASS, and a second run of the same test class leaves counts unchanged.

- [ ] **Step 5: Enable it only for the demo path**

Add `sdip.seed.demo-data: false` to `application.yml`. The compose service (Task 4) sets it to `true`. Local `spring-boot:run` leaves it off.

- [ ] **Step 6: Commit**

```bash
git add sdip-api/pom.xml sdip-api/src/main/java/com/sdip/api/demo/DemoDataSeeder.java sdip-api/src/test/java/com/sdip/api/DemoDataSeederTest.java sdip-api/src/main/resources/application.yml
git commit -m "feat: seed the demo dataset through the real ingestion pipeline"
```

---

### Task 4: One command runs everything

**Files:**
- Create: `Dockerfile`, `.dockerignore`, `.env.example`
- Modify: `docker-compose.yml`, `.gitignore`

**Interfaces:**
- Produces: `docker compose up` serving `http://localhost:8080` against PostgreSQL with demo data, demo users, and a working `/actuator/health`. `docker compose down -v` returns to clean.

- [ ] **Step 1: Write the multi-stage Dockerfile at the repo root**

Stage 1 `maven:3.9-eclipse-temurin-21`, build context `.`, runs `./mvnw -B -DskipTests package` with a BuildKit cache mount for `~/.m2`. Stage 2 `eclipse-temurin:21-jre`, copies only `sdip-api/target/sdip-api-1.0.0.jar`, runs as non-root `sdip`, `EXPOSE 8080`, `HEALTHCHECK` curling `/actuator/health`.

- [ ] **Step 2: Extend compose to app + database**

Postgres service keeps its `pg_isready` healthcheck and gains a named volume. App service: `build: .`, `depends_on: {db: {condition: service_healthy}}`, `SPRING_PROFILES_ACTIVE=pg`, `SDIP_SEED_DEFAULT_USERS=true`, `SDIP_SEED_DEMO_DATA=true`, `PGHOST=db`, and `SDIP_JWT_SECRET_B64`/`SDIP_CREDENTIAL_KEY_B64` from `${...}` with `.env` defaults documented in `.env.example`.

Also in this step: **delete `bin/env.sh`** (it encodes `~/.cache/toolchain`, which does not exist, and is bash-only), and either delete `demo/load-demo.sh` now that seeding is automatic or replace it with `demo/load-demo.ps1` — decide, and state the decision in the commit message. Nothing may remain that the README could tempt a reader into running.

- [ ] **Step 3: Verify the first run from a truly clean state**

Run: `docker compose down -v; docker compose up --build -d; docker compose logs -f app` until `Started SdipApplication`.
Expected: startup succeeds, Flyway logs `V1`–`V4` applied, seeder logs `Seeded demo dataset (4 athletes).`

- [ ] **Step 4: Verify login and data over HTTP**

Run:
```powershell
$t = (Invoke-RestMethod -Method Post http://localhost:8080/api/auth/login -ContentType 'application/json' -Body '{"email":"coach@sdip.local","password":"Coach#123"}').accessToken
(Invoke-RestMethod http://localhost:8080/api/athletes -Headers @{Authorization="Bearer $t"}).Count
```
Expected: a token, and `4` athletes visible to the A-Team coach.

- [ ] **Step 5: Verify restart idempotency (Review Focus 1)**

Run: `docker compose restart app; docker compose logs app | Select-String "Seeded demo dataset"`
Expected: the second startup does **not** re-seed (row count still 4 athletes; no duplicate ingestion batch).

- [ ] **Step 6: Verify failure clarity (Review Focus 2 and 3)**

Occupy port 8080, run `docker compose up`, and confirm compose reports the port conflict and the app does not claim to be healthy. Then bring the stack down without `-v`, change nothing, and `up` again: Flyway must report "up to date" rather than re-running migrations or failing.

- [ ] **Step 7: Commit**

```bash
git add Dockerfile .dockerignore docker-compose.yml .env.example .gitignore
git commit -m "build: add app image and one-command compose demo"
```

---

### Task 5: Actuator — wire it or delete it

**Files:**
- Modify: `sdip-api/pom.xml`, `sdip-api/src/main/resources/application.yml`, `sdip-api/src/main/resources/application-pg.yml`, `sdip-security/src/main/java/com/sdip/security/SecurityConfig.java` (or the filter chain class)
- Test: `sdip-api/src/test/java/com/sdip/api/HealthEndpointTest.java`

**Interfaces:**
- Produces: `GET /actuator/health` returning `{"status":"UP"}` without credentials; all other actuator endpoints not exposed.

- [ ] **Step 1: Write the failing test**

`HealthEndpointTest` (MockMvc): anonymous `GET /actuator/health` returns 200 and `status=UP`; anonymous `GET /actuator/env` returns 404 or 401.

Run: `.\mvnw.cmd -B -pl sdip-api test -Dtest=HealthEndpointTest`
Expected: FAIL — no actuator endpoint.

- [ ] **Step 2: Add the dependency and keep the config honest**

Add `spring-boot-starter-actuator`. Keep `management.endpoints.web.exposure.include: health`. Add `management.endpoint.health.probes.enabled: true` so `health/liveness` and `health/readiness` exist for the compose healthcheck. If the classpath already contains an `ApiExceptionHandler` mapping for unknown paths that would swallow 404s, verify the `/actuator/env` assertion against actual behaviour and adjust the test only if the 404 shape differs — never remove the assertion.

- [ ] **Step 3: Permit the health path in the security chain**

Allow `GET /actuator/health/**` anonymously; leave everything else under `authenticated()`. Do not widen any other matcher.

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\mvnw.cmd -B -pl sdip-api test -Dtest=HealthEndpointTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdip-api/pom.xml sdip-api/src/main/resources/application*.yml sdip-security/src/main/java sdip-api/src/test/java/com/sdip/api/HealthEndpointTest.java
git commit -m "feat: expose a real health endpoint and secure the rest"
```

---

### Task 6: Prove PostgreSQL

**Files:**
- Create: `sdip-api/src/test/java/com/sdip/api/PostgresIntegrationTest.java`
- Modify: `sdip-api/pom.xml` (test-scope `testcontainers:postgresql`, `junit-jupiter`), parent `pom.xml` if versions are centralized

**Interfaces:**
- Produces: a test, tagged `pg`, that starts `postgres:16-alpine`, runs all Flyway migrations including `db/migration-pg-batch`, and asserts the canonical import path writes and reads a row through JPA.

- [ ] **Step 1: Write the failing test**

`PostgresIntegrationTest` uses `@Testcontainers(disabledWithoutDocker = true)`, `@SpringBootTest` with `@ServiceConnection PostgreSQLContainer<>("postgres:16-alpine")`, `@ActiveProfiles("pg")`. Assert: Flyway applied V1–V4, `audit_event` UPDATE is a no-op (the V4 rule), and a `CanonicalImportService.upsertGps` call persists and is readable.

Run: `.\mvnw.cmd -B -pl sdip-api test -Dtest=PostgresIntegrationTest`
Expected: FAIL — no testcontainers dependency.

- [ ] **Step 2: Add the test dependencies and make the skip impossible in CI**

Add `org.testcontainers:postgresql` and `org.testcontainers:junit-jupiter` at test scope. Configure surefire so that when the environment variable `CI=true` is set, `disabledWithoutDocker` cannot silently skip: use a JUnit `@Tag("pg")` plus a surefire `excludedGroups` default of `pg`, and in CI run `-Dgroups=pg -DexcludedGroups=` so the tag is mandatory.

- [ ] **Step 3: Run the test locally to verify it passes**

Run: `.\mvnw.cmd -B -pl sdip-api test -Dgroups=pg -DexcludedGroups=`
Expected: PASS, container starts and is removed.

- [ ] **Step 4: Confirm the default build still works without Docker**

Run: `.\mvnw.cmd -B verify`
Expected: `BUILD SUCCESS`, and the PG test is excluded (not failed) when Docker is unavailable.

- [ ] **Step 5: Commit**

```bash
git add sdip-api/pom.xml pom.xml sdip-api/src/test/java/com/sdip/api/PostgresIntegrationTest.java
git commit -m "test: exercise the PostgreSQL path with Testcontainers"
```

---

### Task 7: Enforce the module boundaries

**Files:**
- Create: `sdip-api/src/test/java/com/sdip/api/ModuleBoundaryTest.java`
- Modify: `sdip-api/pom.xml`

**Interfaces:**
- Produces: an ArchUnit test asserting ADR-01's dependency direction.

- [ ] **Step 1: Write the failing test**

Rules: no class in `com.sdip.domain..` may depend on `com.sdip.ingest..`, `com.sdip.connectors..`, `com.sdip.security..`, or `com.sdip.api..`; no class in `com.sdip.ingest..` may depend on `com.sdip.api..` or `com.sdip.connectors..`; `com.sdip.security..` may not depend on `com.sdip.ingest..` or `com.sdip.connectors..`.

Run: `.\mvnw.cmd -B -pl sdip-api test -Dtest=ModuleBoundaryTest`
Expected: FAIL — ArchUnit absent.

- [ ] **Step 2: Add ArchUnit and run it**

Add `com.tngtech.archunit:archunit-junit5` (test scope, version verified on Maven Central). Run again.

- [ ] **Step 3: Resolve or record every violation**

If a rule fails, the violation is real: either move the offending class or, if the dependency is legitimate and the ADR is wrong, amend ADR-01 in the same commit and state why. Do not weaken the rule to make it pass silently.

- [ ] **Step 4: Commit**

```bash
git add sdip-api/pom.xml sdip-api/src/test/java/com/sdip/api/ModuleBoundaryTest.java docs/adr
git commit -m "test: enforce the modular-monolith boundaries with ArchUnit"
```

---

### Task 8: Continuous integration

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `README.md` (badge only; the rewrite is Task 9)

**Interfaces:**
- Produces: a workflow running `./mvnw -B verify` on `ubuntu-latest` with Temurin 21, Maven cache, and a mandatory `pg` test run.

- [ ] **Step 1: Write the workflow**

Trigger on `push` and `pull_request`. Steps: checkout → `setup-java` (temurin, 21) → cache `~/.m2/repository` keyed on `pom.xml` hash → `./mvnw -B -DskipTests package` → `./mvnw -B verify` → `./mvnw -B -pl sdip-api test -Dgroups=pg -DexcludedGroups=` with `CI: true` → upload surefire reports on failure.

- [ ] **Step 2: Make the workflow fail loudly if Docker is ever unavailable**

The `pg` step must not be `continue-on-error`. A skipped container test in CI is a failure, not a pass.

- [ ] **Step 3: Verify locally what CI will do**

Run each command from the workflow in a fresh shell on this machine. Expected: identical results to the workflow's intent, with the `pg` step running because Docker is present.

- [ ] **Step 4: Commit and push, then confirm the badge is green**

```bash
git add .github/workflows/ci.yml README.md
git commit -m "ci: run the full verification on GitHub Actions"
git push
```
Expected: the workflow run on GitHub completes with every step green. If a step fails there but passed locally, treat the runner difference as the bug and fix it before moving on.

---

### Task 9: Documentation that a stranger can follow

**Files:**
- Modify: `README.md`
- Create: `LICENSE`, `docs/architecture.md`, `docs/evaluation.md`, `docs/adr/ADR-01..06.md`, `docs/images/*.png`

**Interfaces:**
- Produces: a README whose every command was executed in Task 4; screenshots captured from that run.

- [ ] **Step 1: Capture screenshots from the compose stack**

With the stack up and signed in as `coach@sdip.local`: login screen, dashboard with charts, batch list, quarantine portal. Save as `docs/images/01-login.png` … `04-quarantine.png`. If a screenshot cannot be produced from the one-command path, that is a defect in Task 4, not a reason to fake it.

- [ ] **Step 2: Rewrite the README as a landing page**

Order: one-paragraph what/why → "Run it in 10 minutes" (three commands, the four credentials, the four screenshots) → architecture diagram and module table → API/RBAC table → "What's proven" (link to `docs/evaluation.md`) → "Limits" → links to ADRs. Move the long design notes out of the README verbatim where possible.

- [ ] **Step 3: Split the documents**

`docs/architecture.md` (module map, ingest/sync/dashboard flows), `docs/evaluation.md` (the RQ table, each row with the exact reproducing command and its caveat), one file per ADR. Every numeric claim either names its reproducing command or its measurement context — no orphan numbers.

- [ ] **Step 4: Add the license**

`LICENSE` = MIT, copyright line year 2026, owner name as the user directs.

- [ ] **Step 5: Verify the README from scratch**

Delete the cloned directory, re-clone, and follow the README literally in PowerShell. Expected: the app is running with four athletes in under ten minutes. Time it and put the measured time in the README.

- [ ] **Step 6: Commit**

```bash
git add README.md LICENSE docs
git commit -m "docs: rewrite the README around a verified ten-minute demo"
```

---

### Task 10: Final acceptance

**Files:**
- Create: `docs/verification-log.md`
- Modify: `README.md` (only if the measured numbers changed)

**Interfaces:**
- Produces: a committed record of the acceptance run — what was run, on what machine, with what output.

- [ ] **Step 1: Run the acceptance test exactly as the spec defines it**

Prerequisites: only Git and Docker. Run `git clone` → `docker compose up` → sign in as `coach@sdip.local` → see four athletes and charts. Record wall-clock time.

- [ ] **Step 2: Run the developer path on a clean Maven repository**

Point `MAVEN_USER_HOME` at an empty temp directory and run `.\mvnw.cmd -B verify`. Record duration and test count.

- [ ] **Step 3: Record both runs with their evidence**

`docs/verification-log.md`: date, OS, Docker version, JDK version, commands, outcomes, durations, and anything that did not match the README.

- [ ] **Step 4: Commit**

```bash
git add docs/verification-log.md README.md
git commit -m "docs: record the acceptance run"
```
