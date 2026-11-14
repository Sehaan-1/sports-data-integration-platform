import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { once } from "node:events";
import {
  createApiClient,
  ApiError,
} from "../sdip-api/src/main/resources/static/assets/api.js";
import {
  attachAcwr,
  buildWorkspace,
  csvCell,
  dateWindow,
  dayKey,
  enumerateDays,
  filterRoster,
  mapConcurrent,
  parseCsv,
  percentChange,
  shiftDay,
  toCsv,
  workloadStatus,
} from "../sdip-api/src/main/resources/static/assets/model.js";
import { createDemoClient } from "../sdip-api/src/main/resources/static/assets/demo-data.js";
import { curvePath } from "../sdip-api/src/main/resources/static/assets/charts.js";
import { esc } from "../sdip-api/src/main/resources/static/assets/ui.js";
import { createPreviewServer } from "./preview.mjs";

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
const memoryStorage = () => {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) || null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
};

test("UTC windows include exactly the selected number of calendar days", () => {
  assert.deepEqual(dateWindow(7, "2026-11-14"), {
    from: "2026-11-08",
    to: "2026-11-14",
    days: 7,
  });
  assert.equal(dayKey("2026-11-14T23:00:00-05:00"), "2026-11-15");
  assert.equal(shiftDay("2024-03-01", -1), "2024-02-29");
  assert.deepEqual(enumerateDays("2026-12-30", "2027-01-01"), [
    "2026-12-30",
    "2026-12-31",
    "2027-01-01",
  ]);
  assert.equal(dateWindow(-5, "2026-11-14").days, 1);
  assert.equal(dateWindow(1000, "2026-11-14").days, 180);
});

test("ACWR matches an uncoupled 7:21-day baseline and counts rest days", () => {
  const records = enumerateDays("2026-10-18", "2026-11-14").map(
    (day, index) => ({ day, playerLoad: index >= 21 ? 200 : 100 }),
  );
  const last = attachAcwr(records).at(-1);
  assert.equal(last.acuteLoad, 200);
  assert.equal(last.chronicLoad, 100);
  assert.equal(last.acwr, 2);
  const first = attachAcwr([{ day: "2026-11-14", playerLoad: 700 }])[0];
  assert.equal(first.acuteLoad, 100);
  assert.equal(first.acwr, null);
});

test("workload boundaries do not treat missing or zero baselines as healthy", () => {
  assert.equal(workloadStatus(null).key, "none");
  assert.equal(workloadStatus(NaN).key, "none");
  assert.equal(workloadStatus(0.79).key, "low");
  assert.equal(workloadStatus(0.8).key, "balanced");
  assert.equal(workloadStatus(1.3).key, "balanced");
  assert.equal(workloadStatus(1.301).key, "elevated");
  assert.equal(workloadStatus(1.5).key, "elevated");
  assert.equal(workloadStatus(1.501).key, "high");
  assert.equal(percentChange(10, 0), null);
  assert.equal(percentChange(110, 100), 10);
});

const athletes = [
  { id: "a", fullName: "Alpha Athlete", squadId: "A", squadName: "First team" },
  { id: "b", fullName: "Beta Athlete", squadId: "B", squadName: "Development" },
];
const team = [
  {
    athleteId: "a",
    playerLoad: 100,
    totalDistanceM: 1500,
    hsrM: 200,
    sessions: 2,
  },
  {
    athleteId: "b",
    playerLoad: 300,
    totalDistanceM: 4500,
    hsrM: 400,
    sessions: 3,
  },
];
const loads = new Map([
  [
    "a",
    [
      { day: "2026-11-01", playerLoad: 50, totalDistanceM: 750 },
      { day: "2026-11-14", playerLoad: 100, totalDistanceM: 1500, acwr: 1.1 },
    ],
  ],
  [
    "b",
    [{ day: "2026-11-14", playerLoad: 300, totalDistanceM: 4500, acwr: 1.7 }],
  ],
]);

test("client squad selection scopes every metric, chart series, and table row", () => {
  const result = buildWorkspace(athletes, team, loads, 7, "A", "2026-11-14");
  assert.equal(result.roster.length, 1);
  assert.equal(result.totals.playerLoad, 100);
  assert.equal(result.totals.distanceM, 1500);
  assert.equal(result.totals.sessions, 2);
  assert.equal(result.totals.previousLoad, 50);
  assert.equal(result.trend.at(-1).playerLoad, 100);
  assert.equal(result.trend.length, 7);
  assert.equal(result.roster[0].status.key, "balanced");
  assert.equal(
    buildWorkspace(athletes, team, loads, 7, "missing", "2026-11-14").totals
      .athletes,
    0,
  );
});

test("untrusted team IDs cannot add athletes outside the server-provided roster", () => {
  const result = buildWorkspace(
    athletes.slice(0, 1),
    [...team, { athleteId: "intruder", playerLoad: 9999 }],
    loads,
    7,
    "all",
    "2026-11-14",
  );
  assert.equal(result.totals.playerLoad, 100);
  assert.equal(result.roster.length, 1);
});

test("roster search and sorting are case-insensitive and do not mutate source data", () => {
  const result = buildWorkspace(athletes, team, loads, 7, "all", "2026-11-14");
  assert.equal(filterRoster(result.roster, "ALPHA")[0].id, "a");
  assert.equal(filterRoster(result.roster, "development")[0].id, "b");
  assert.equal(filterRoster(result.roster, "", "high").length, 1);
  assert.equal(filterRoster(result.roster, "", "all", "load-asc")[0].id, "a");
  assert.equal(result.roster[0].id, "b");
});

test("no-data athletes retain their profile but never invent a workload or baseline", () => {
  const result = buildWorkspace(
    athletes,
    [],
    new Map(),
    7,
    "all",
    "2026-11-14",
  );
  assert.equal(result.totals.playerLoad, 0);
  assert.equal(result.totals.active, 0);
  assert.equal(result.roster[0].acwr, null);
  assert.equal(result.roster[0].lastActivity, null);
  assert.equal(result.roster[0].status.label, "No baseline");
});

test("CSV preview supports BOM, CRLF, quoted commas, escaped quotes, and multiline cells", () => {
  assert.deepEqual(
    parseCsv(
      '\uFEFFName,Note\r\n"Doe, Alex","Said ""hello"""\r\nSam,"Line one\nLine two"\r\n\r\n',
    ),
    [
      ["Name", "Note"],
      ["Doe, Alex", 'Said "hello"'],
      ["Sam", "Line one\nLine two"],
    ],
  );
  assert.throws(() => parseCsv('Name,Note\nSam,"unclosed'), /unclosed/);
});

test("demo CSV exports escape delimiters and spreadsheet formulas", () => {
  assert.equal(csvCell('A,"B"'), '"A,""B"""');
  for (const value of ["=SUM(A1)", "+123", "-10", "@command", " \t=cmd"])
    assert.ok(csvCell(value).startsWith("\"'"));
  assert.ok(toCsv(["Name"], [["Alpha"]]).startsWith('\uFEFF"Name"\r\n"Alpha"'));
  assert.equal(
    esc('<img src=x onerror="x">'),
    "&lt;img src=x onerror=&quot;x&quot;&gt;",
  );
});

test("bounded request pool preserves order and respects concurrency", async () => {
  let running = 0,
    highest = 0;
  const result = await mapConcurrent(
    [1, 2, 3, 4, 5, 6, 7],
    async (value) => {
      highest = Math.max(highest, ++running);
      await new Promise((resolve) => setTimeout(resolve, 3));
      running--;
      return value * 2;
    },
    3,
  );
  assert.equal(highest, 3);
  assert.deepEqual(result, [2, 4, 6, 8, 10, 12, 14]);
});

test("chart paths never require a canvas and preserve exact endpoints", () => {
  assert.equal(curvePath([]), "");
  assert.equal(
    curvePath([
      [0, 10],
      [20, 30],
    ]),
    "M0.00,10.00 C10.00,10.00 10.00,30.00 20.00,30.00",
  );
});

test("demo data is deterministic, consistent, and isolated between browser sessions", async () => {
  const first = createDemoClient("2026-11-14"),
    second = createDemoClient("2026-11-14");
  const roster = (await first.request("/api/athletes")).content;
  assert.equal(roster.length, 24);
  assert.equal(new Set(roster.map((athlete) => athlete.squadId)).size, 2);
  const rows = await first.request("/api/dashboard/team/load?days=7");
  const series = new Map(
    await Promise.all(
      roster.map(async (athlete) => [
        athlete.id,
        (
          await first.request(
            `/api/dashboard/athlete/${athlete.id}/load?days=42`,
          )
        ).days,
      ]),
    ),
  );
  const result = buildWorkspace(roster, rows, series, 7, "all", "2026-11-14");
  assert.equal(
    result.trend.reduce((sum, day) => sum + day.playerLoad, 0),
    result.totals.playerLoad,
  );
  assert.equal(result.totals.sessions, 168);
  const queue = (await first.request("/api/links/quarantine")).content;
  await first.request(`/api/links/quarantine/${queue[0].id}/confirm`, {
    method: "POST",
  });
  assert.equal(
    (await first.request("/api/links/quarantine")).content.length,
    2,
  );
  assert.equal(
    (await second.request("/api/links/quarantine")).content.length,
    3,
  );
  await assert.rejects(first.request("/api/not-supported"), /not available/);
});

test("demo file imports are explicitly simulated and do not alter athlete records", async () => {
  const client = createDemoClient("2026-11-14");
  const body = new FormData();
  body.append("profile", "catapult-session-csv");
  body.append("file", new Blob(["Name,Load\nDemo,100"]), "test.csv");
  const result = await client.request("/api/ingest/files", {
    method: "POST",
    body,
    demoRows: 1,
  });
  assert.equal(result.status, "SIMULATED");
  const batch = await client.request(`/api/ingest/batches/${result.batchId}`);
  assert.equal(batch.simulated, true);
  assert.equal(batch.rowsRead, 1);
  assert.equal((await client.request("/api/athletes")).content.length, 24);
  assert.equal((await client.download()).type, "text/csv;charset=utf-8");
});

test("API client rotates expired tokens once for concurrent requests", async () => {
  let refreshCalls = 0,
    authorized = 0;
  const storage = memoryStorage();
  const client = createApiClient({
    storage,
    fetchImpl: async (url, options) => {
      if (url === "/api/auth/refresh") {
        refreshCalls++;
        await new Promise((resolve) => setTimeout(resolve, 5));
        return json({ accessToken: "new-access", refreshToken: "new-refresh" });
      }
      if (options.headers.get("Authorization") !== "Bearer new-access")
        return json({}, 401);
      authorized++;
      return json({ ok: true });
    },
  });
  client.setTokens({ accessToken: "old-access", refreshToken: "old-refresh" });
  await Promise.all([
    client.request("/api/one"),
    client.request("/api/two"),
    client.request("/api/three"),
  ]);
  assert.equal(refreshCalls, 1);
  assert.equal(authorized, 3);
  assert.equal(storage.getItem("sdip.rt"), "new-refresh");
});

test("a failed refresh clears the session and announces expiry once", async () => {
  let expired = 0;
  const storage = memoryStorage();
  const client = createApiClient({
    storage,
    onUnauthorized: () => expired++,
    fetchImpl: async () => json({}, 401),
  });
  client.setTokens({ accessToken: "old", refreshToken: "old-refresh" });
  await Promise.allSettled([
    client.request("/api/one"),
    client.request("/api/two"),
  ]);
  assert.equal(expired, 1);
  assert.equal(client.hasSession, false);
  assert.equal(storage.getItem("sdip.at"), null);
});

test("login requests do not send stale bearer tokens or attempt refresh on bad credentials", async () => {
  const calls = [];
  const client = createApiClient({
    storage: memoryStorage(),
    fetchImpl: async (url, options) => {
      calls.push({ url, headers: options.headers });
      return json({}, 401);
    },
  });
  client.setTokens({ accessToken: "stale", refreshToken: "stale-refresh" });
  await assert.rejects(
    client.request("/api/auth/login", {
      method: "POST",
      auth: false,
      json: { email: "demo", password: "wrong" },
    }),
    /incorrect/,
  );
  assert.equal(calls.length, 1);
  assert.equal(calls[0].headers.has("Authorization"), false);
});

test("API permissions and network errors use safe, actionable messages", async () => {
  const client = createApiClient({
    storage: null,
    fetchImpl: async () => json({ stack: "private stack" }, 403),
  });
  await assert.rejects(
    client.request("/api/admin"),
    (error) =>
      error instanceof ApiError &&
      error.status === 403 &&
      !error.message.includes("private"),
  );
  const offline = createApiClient({
    storage: null,
    fetchImpl: async () => {
      throw new TypeError("fetch failed");
    },
  });
  await assert.rejects(
    offline.request("/api/auth/me"),
    /Cannot reach the SDIP API/,
  );
});

test("token use survives a browser that disallows persistent storage", async () => {
  const blocked = {
    getItem() {
      throw new Error("blocked");
    },
    setItem() {
      throw new Error("blocked");
    },
    removeItem() {
      throw new Error("blocked");
    },
  };
  const client = createApiClient({
    storage: blocked,
    fetchImpl: async () => json({ ok: true }),
  });
  client.setTokens({
    accessToken: "session-only",
    refreshToken: "session-refresh",
  });
  assert.equal(client.hasSession, true);
  assert.deepEqual(await client.request("/api/test"), { ok: true });
  client.clearTokens();
  assert.equal(client.hasSession, false);
});

test("every literal interface icon is present in one valid, unnested SVG sprite", async () => {
  const root = new URL(
    "../sdip-api/src/main/resources/static/",
    import.meta.url,
  );
  const sprite = await readFile(new URL("assets/icons.svg", root), "utf8");
  const ids = new Set(
    [...sprite.matchAll(/<symbol id="([^"]+)"/g)].map((match) => match[1]),
  );
  assert.equal((sprite.match(/<svg\b/g) || []).length, 1);
  assert.equal((sprite.match(/<\/symbol>/g) || []).length, ids.size);
  for (const filename of [
    "dashboard.html",
    "assets/app.js",
    "assets/ui.js",
    "assets/charts.js",
  ]) {
    const source = await readFile(new URL(filename, root), "utf8");
    const names = [
      ...source.matchAll(/icons\.svg#([a-z0-9-]+)|\bicon\(["']([a-z0-9-]+)["']/g),
    ].map((match) => match[1] || match[2]);
    for (const name of names)
      assert.ok(ids.has(name), `${filename}: missing ${name}`);
  }
});

test("preview serves the shipped frontend, rejects traversal, and never exposes a fake API", async () => {
  const server = createPreviewServer({ apiUrl: "" });
  server.listen(0, "0.0.0.0");
  await once(server, "listening");
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const home = await fetch(base, { redirect: "manual" });
    assert.equal(home.status, 302);
    assert.equal(home.headers.get("location"), "/dashboard.html?demo=1");
    const html = await fetch(base + "/dashboard.html");
    assert.equal(html.status, 200);
    assert.ok((await html.text()).includes('aria-labelledby="page-title"'));
    const css = await fetch(base + "/assets/styles.css", { method: "HEAD" });
    assert.equal(css.status, 200);
    assert.ok(css.headers.get("content-type").includes("text/css"));
    assert.equal(
      (await fetch(base + "/assets/fonts/manrope-latin.woff2")).headers.get(
        "content-type",
      ),
      "font/woff2",
    );
    assert.equal((await fetch(base + "/api/auth/me")).status, 503);
    assert.equal((await fetch(base + "/..%2F..%2Fpom.xml")).status, 403);
    assert.equal((await fetch(base + "/missing.html")).status, 404);
    assert.equal(
      (await fetch(base + "/dashboard.html", { method: "POST" })).status,
      405,
    );
  } finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
});
