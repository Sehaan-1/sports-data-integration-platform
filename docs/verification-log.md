# Verification log — local reproducibility acceptance

- **Date:** 2026-11-13 (Asia/Kolkata, UTC+05:30)
- **Branch / commit:** `feat/local-reproducibility` at `2acc1ab`
- **Machine:** Intel Core i7-1355U (10 cores / 12 logical), 31.7 GB RAM
- **OS:** Microsoft Windows 11 Home 10.0.26200
- **Git:** 2.56.0.windows.4
- **Docker:** 29.4.0 (client and server), Compose v5.1.1
- **JDK:** OpenJDK 25.0.2 LTS on the host. The app image builds with Temurin 21
  (`maven:3.9-eclipse-temurin-21`); CI uses Temurin 21.

## 1. Acceptance path — spec §2

Prerequisites on this machine: Git and Docker only. The branch has not been
pushed (operator instruction), so `git clone` used the local repository path;
every command after the clone is the README verbatim.

```powershell
git clone "C:\Users\INDIA TECHNOLOGY\Documents\SDIP JAVA\sdip" sdip-acceptance
cd sdip-acceptance
docker compose up -d
```

| Measurement | Result |
|---|---:|
| `docker compose up -d` returns | 28.2 s |
| App reports `healthy` | 49.5 s |
| Build cache | warm (BuildKit layers from the documentation run) |

Cold-cache companion run, same commit family (`3d81b94`): build cache pruned
(42.01 GB reclaimed), `docker compose up -d` returned in **131.1 s**, app
healthy in **152.4 s**. The README records the cold number (2 min 32 s), so the
published claim is the conservative one.

Checks after startup:

| Check | Result |
|---|---|
| `POST /api/auth/login` as `coach@sdip.local` | 200, access token returned |
| `GET /api/athletes?size=200` | **4** athletes (Arjun Mehta, Vikram Singh, Rohan Patil, Karan Shah) |
| `GET /api/dashboard/team/load?days=28` | **4** bars, first `Vikram Singh: 2431.0` |
| `GET /api/dashboard/athlete/{id}/load?days=42` | **7** load days returned |
| `GET /api/dashboard/athlete/{id}/fatigue-overlay?days=90` | **3** force tests returned |
| `GET /api/ingest/batches?size=50` | 2 batches, both `COMPLETED` |
| `GET /` and `/dashboard.html` | 200; `/` redirects to the dashboard |
| `docker compose restart app` | healthy; log: `Demo dataset already present (28 GPS rows) — not re-seeding.` |

Outcome: the README path yields four athletes with GPS and force-plate charts in
under ten minutes, from a clone with no edits.

## 2. Developer path — spec §2

An empty Maven user home and an empty local repository were used:

```powershell
$env:MAVEN_USER_HOME = "$env:TEMP\sdip-m2-acceptance"
.\mvnw.cmd -B "-Dmaven.repo.local=$env:TEMP\sdip-m2-acceptance\repository" verify
```

| Measurement | Result |
|---|---:|
| Exit code | 0 (`BUILD SUCCESS`) |
| Duration | **228.3 s** (3 min 40 s) |
| Tests | **44** run, 0 failures, 0 errors, 0 skipped |
| Throughput probe | **4,947 rows/s** (20,000 rows in 4,043 ms) |
| Artifacts downloaded | 848 |
| Empty repository after run | 2,263 files |

Test breakdown: 11 core-domain, 3 ingestion, 5 connectors, 25 api. The
PostgreSQL-tagged tests are excluded by the default build (`excludedGroups=pg`)
and are run as a separate mandatory CI step. This host has JDK 25; the
canonical JDK 21 build was verified in the Temurin 21 container during Task 2
and is what CI runs.

Note: `MAVEN_USER_HOME` relocates only the Maven Wrapper distribution
(`mvnw.cmd` line 82). `-Dmaven.repo.local` was added so the dependency
repository itself was empty; without it the host's warm `~/.m2` would have
masked the clean-repository check.

## 3. Final-review regression check

The Task 9 dashboard table fix had removed HTML escaping from every cell
(stored-XSS risk through athlete names and batch fields). The fix restores
`esc()` and marks only the two pill columns as raw.

| Check | Result |
|---|---|
| Node harness against `dashboard.html` before the fix | **FAIL** — `<td><img src=x onerror=alert(1)></td>` rendered raw |
| Same harness after the fix | **PASS** — untrusted cell escaped; raw pill still renders |
| Served `/dashboard.html` contains `c[2]?c[1](r):esc(c[1](r))` | yes |

## 4. Deviations and caveats

- Clone source was the local repository, not the public GitHub URL, because the
  branch is intentionally unpushed. The clone/start/sign-in sequence is
  otherwise identical.
- Acceptance timing was measured with a warm BuildKit cache (49.5 s). The
  README publishes the cold-cache number (152.4 s), which is the stricter
  figure; both are far below ten minutes.
- The quarantine screenshot shows an empty queue: no quarantine rows are
  seeded, and the tab is admin-only. This is documented, not faked.
- Docker base-image pulls were already present locally; a first-ever pull adds
  network time but not application work.

No functional check failed, and no observed result contradicted the README.
