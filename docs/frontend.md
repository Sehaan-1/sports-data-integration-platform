# SDIP frontend

## Design direction

The redesign uses [Leonxlnx/taste-skill](https://github.com/Leonxlnx/taste-skill)
(`design-taste-frontend`, version 2.5.1) as design guidance. The skill and its
copy, typography, layout, system, and motion references were reviewed, its
software typography dealer was run (Manrope + JetBrains Mono), and its real-
browser consistency checker was used during review.

The resulting **application workspace** uses a warm orange signal color,
neutral surfaces, one spacing/radius system, restrained SVG charts, local fonts,
a persistent navigation hierarchy, and brief hover/press feedback. The code and
SDIP mark are original. No generated photographs or third-party product-logo
lookalikes are used: decorative interface icons identify data types, with the
vendor names in text.

The skill's marketing-page rules are intentionally adapted rather than applied
literally. A working dashboard needs compact panels, chart legends, accessible
filter labels, and hash-based application routes. It should not acquire a
full-screen hero, scroll scenes, a sales CTA, or promotional feature photography.
The stock consistency checker treats those application patterns as landing-page
failures (including chart legends as captions, hash routes as missing anchors,
and explicit sample-data labels as placeholders). **Its marketing gate is not
claimed to pass.** Its useful findings did lead to stronger focus outlines,
better neutral-text contrast, larger mobile chart controls, and contained
horizontal table scrolling. The app-specific browser suite checks actual
navigation, data interactions, keyboard access, responsive layout, and axe
WCAG A/AA rules instead. Automated checks do not constitute a full accessibility
audit.

## Shipped architecture

The existing static frontend remains in `sdip-api/src/main/resources/static/`:

| File                  | Responsibility                                                                  |
| --------------------- | ------------------------------------------------------------------------------- |
| `dashboard.html`      | Sign-in and accessible workspace shell                                          |
| `assets/styles.css`   | Design system, layouts, responsive/reduced-motion/print styles                  |
| `assets/app.js`       | Hash routes, UI state, role visibility, dialogs and workflows                   |
| `assets/api.js`       | Same-origin requests, JWT storage, single-flight token rotation, safe errors    |
| `assets/model.js`     | UTC windows, scoped view models, ACWR helpers, CSV safety, bounded request pool |
| `assets/charts.js`    | Responsive SVG charts with pointer/arrow-key inspection and distinct axes       |
| `assets/demo-data.js` | Explicitly selected, isolated synthetic fixtures                                |
| `assets/ui.js`        | Escaping, formatting, icon/avatar helpers and downloads                         |

No bundler or JavaScript framework is required to build or run the jar. There
are no external font/icon requests. Asset licenses are included under
`assets/licenses/` (Manrope/JetBrains Mono: OFL; Lucide: ISC).

Only the static `/assets/**` path was added to the security permit list. API
rules and server-side squad/role enforcement are unchanged. A Spring MockMvc
regression test covers anonymous asset access while athlete and review APIs
remain protected.

## Workflows

- **Overview:** scoped team totals, daily workload comparison, source sync
  details when allowed, and an athlete snapshot.
- **Athletes:** name/squad search, status filters, sortable load/name columns,
  and a profile drawer with a 42-day load timeline.
- **Fatigue analysis:** per-athlete GPS and ForceDecks data. RSI uses an explicitly
  labeled independent axis and the most recent test type; different protocols
  are never averaged into one series. Missing tests remain gaps.
- **Sources:** administrator-only sync controls. `OK`, `DEGRADED`, and `DISABLED`
  responses are handled distinctly; a disabled connector is not reported synced.
- **Imports:** mapping-profile selection, an explicit destination squad for
  administrators (including squads with no athletes yet), file validation,
  drag/drop, accepted-job feedback, recent lineage, and row errors for staff.
- **Identity review:** inspect the incoming identity, choose a canonical athlete,
  add a note, confirm a reversible link, or keep identities separate.
- **Exports:** use the existing authenticated/audited roster CSV endpoint. The
  dialog explains that page filters do not change this server-authorized export.
- **Preferences/search:** local distance and density settings; Ctrl/Cmd+K search;
  keyboard-operable native dialogs; mobile navigation and a working skip link.

## Demo versus live mode

`?demo=1` or **Explore demo workspace** deliberately selects 24 synthetic athlete
profiles. Fixtures and demo mutations are in memory and reset on reload. No API
requests, vendor calls, uploads, or live athlete writes are made in this mode.
The header, footer, imports, sync actions, and exported filename distinguish it.
CSV previews are capped at 10 MB and do not claim server-side validation; XLSX
requires the real ingestion pipeline. No data from the demo is silently mixed
into a signed-in session, and a failed live request never falls back to fixtures.

`npm run preview` starts `scripts/preview.mjs` on `0.0.0.0:3000` and opens this
explicit demo. `SDIP_API_URL` optionally enables a same-origin reverse proxy to
the real backend. It does not change browser API URLs, relax backend auth, or
expose mock HTTP endpoints. The preview accepts proxied development hosts and
supports embedding in development preview panels.

## Data interpretation and bounds

- The API supplies **rolling-window totals**, while daily-series queries are
  calendar aggregates. Charts use UTC, and their help explains possible
  differences at a partial-day boundary. Live summary cards do not invent a
  previous-period percentage the aggregate API does not provide.
- ACWR uses the backend's uncoupled 7-day / preceding 21-day convention. The
  descriptive low/balanced/elevated/high flags are not diagnoses or predictions
  of fatigue or injury. Missing baselines are labeled, never treated as healthy.
- The current API exposes daily history per athlete, not an aggregate team
  timeline. Histories are cached and fetched with five workers; failed series
  produce a marked unavailable chart, not an apparently complete partial total.
- Roster reads follow pagination (200 per request, 4,000-profile safety bound,
  explicit error instead of silent truncation). The history/review views load up
  to 100 recent records; refresh checks the queue again. A dedicated paginated
  server-side reporting endpoint would be preferable for large installations.
- Preferences are local browser settings. They do not pretend to alter server
  scheduling, credentials, roles, or squad membership.

## Verification

```sh
npm ci
npm run test:frontend
npm run test:browser
# Deliberately update the documentation captures after a passing run:
npm run test:browser -- --screenshots
```

The pure-helper suite covers UTC boundaries, scoping, ACWR, CSV quoting/formula
safety, concurrent request limits, JWT refresh, storage failures, icon integrity,
and preview-server path/MIME handling. The Chromium suite exercises all six
pages, imports/exports, identity decisions, search, preferences, mobile
navigation, and layouts at 320, 390, 768, 1024, and 1440 pixels. It checks axe
A/AA rules in the main pages and modal states and captures uncaught exceptions.
A test-only REST mock additionally exercises the **production client path** for
all four roles, multipart fields, empty squads, failures/retry, and sign-out.

The final local run on 2026-11-14 passed **20/20 helper tests and 41/41 browser
checks**, with no uncaught browser errors or axe violations in the audited states.
All six routes fit the five tested widths without document overflow. The linked
skill's performance checker also passed at 1440 and 390 pixels (three loads
per width, approximately 328 KiB transferred and zero measured layout shift).
Those are sandbox measurements, not production field-performance claims.
The unmodified marketing finish gate was also run; it does not pass for the
application-specific reasons above, and has not been bypassed or waived.

Java/Maven are not installed in the redesign sandbox, so the Spring backend and
new MockMvc regression test were **not run locally**. The existing Maven CI job
is configured to run them with JDK 21; that remote run is not claimed here.
The browser's mock-contract checks are not a claim of end-to-end verification
against Java, PostgreSQL, or either vendor.

Generated reports, browser binaries, downloaded design references, and scratch
captures are ignored under `.cache/` or `node_modules/`. Only the small, selected
documentation screenshots are kept in Git.
