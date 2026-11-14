#!/usr/bin/env node
import assert from "node:assert/strict";
import { mkdir, readFile, writeFile, copyFile } from "node:fs/promises";
import { once } from "node:events";
import path from "node:path";
import { createPreviewServer } from "./preview.mjs";
import { launchBrowser } from "./browser.mjs";
import { createDemoClient } from "../sdip-api/src/main/resources/static/assets/demo-data.js";

const output = path.resolve(".cache/frontend-check");
await mkdir(output, { recursive: true });
const server = createPreviewServer({ apiUrl: "" });
server.listen(0, "0.0.0.0");
await once(server, "listening");
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await launchBrowser();
const page = await browser.newPage({
  viewport: { width: 1440, height: 1000 },
  reducedMotion: "reduce",
  acceptDownloads: true,
});
page.setDefaultTimeout(12_000);
const errors = [],
  violations = [],
  results = [];
const axeSource = await readFile(
  new URL("../node_modules/axe-core/axe.min.js", import.meta.url),
  "utf8",
);
page.on("pageerror", (error) => errors.push(error.message));
let demoApiCalls = [];
const trackDemoApi = (request) => {
  if (new URL(request.url()).pathname.startsWith("/api/"))
    demoApiCalls.push(request.url());
};
page.on("request", trackDemoApi);
function pass(label) {
  results.push(label);
  console.log(`PASS ${label}`);
}
async function ready() {
  await page
    .locator('#workspace:not([hidden]) #page-content[aria-busy="false"]')
    .waitFor();
  await page.evaluate(() => document.fonts.ready);
}
const names = {
  overview: "Overview",
  athletes: "Athletes",
  fatigue: "Fatigue analysis",
  sources: "Data sources",
  history: "Import history",
  review: "Identity review",
};
async function go(route) {
  await page.evaluate((value) => {
    location.hash = `/${value}`;
  }, route);
  await page.waitForFunction(
    (name) => document.querySelector("#breadcrumb-page").textContent === name,
    names[route],
  );
  if (route === "fatigue") await page.locator("#fatigue-chart").waitFor();
  await page.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
}
async function audit(label) {
  if (!(await page.evaluate(() => Boolean(window.axe))))
    await page.addScriptTag({ content: axeSource });
  const report = await page.evaluate(async () =>
    axe.run(document, {
      runOnly: {
        type: "tag",
        values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"],
      },
    }),
  );
  const failures = report.violations.map((item) => ({
    id: item.id,
    impact: item.impact,
    nodes: item.nodes.map((node) => ({
      target: node.target,
      summary: node.failureSummary,
    })),
  }));
  if (failures.length) {
    violations.push({ label, failures });
    console.log(
      `FAIL accessibility: ${label} (${failures.map((item) => item.id).join(", ")})`,
    );
  } else pass(`accessibility: ${label}`);
}
async function noOverflow(label) {
  const dimensions = await page.evaluate(() => ({
    viewport: innerWidth,
    document: document.documentElement.scrollWidth,
  }));
  assert.ok(
    dimensions.document <= dimensions.viewport + 1,
    `${label}: document width ${dimensions.document} exceeds ${dimensions.viewport}`,
  );
  const overlappingMetrics = await page.evaluate(() =>
    [...document.querySelectorAll(".stat-value-row")].flatMap((row) => {
      const value = row.querySelector(".stat-value")?.getBoundingClientRect();
      const spark = row.querySelector(".stat-spark");
      if (!value || !spark || !spark.getClientRects().length) return [];
      return value.right > spark.getBoundingClientRect().left + 1
        ? [row.textContent]
        : [];
    }),
  );
  assert.deepEqual(
    overlappingMetrics,
    [],
    `${label}: a metric overlaps its sparkline`,
  );
}
async function shot(name, fullPage = false) {
  await page.screenshot({
    path: path.join(output, name),
    fullPage,
    animations: "disabled",
    timeout: 30000,
  });
}
async function closeDialog() {
  if (await page.locator("#app-dialog[open]").count())
    await page.keyboard.press("Escape");
}

try {
  await page.goto(base + "/");
  await ready();
  assert.equal(await page.locator(".stat-value").first().textContent(), "24");
  await page.keyboard.press("Tab");
  assert.equal(
    await page.evaluate(() => document.activeElement.className),
    "skip-link",
  );
  await page.keyboard.press("Enter");
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "main-content",
  );
  pass("explicit demo entry, initial metrics, and keyboard skip link");

  for (const route of Object.keys(names)) {
    await go(route);
    await audit(`${route}, desktop`);
    await noOverflow(`${route}, desktop`);
  }
  await go("overview");
  const playerLoad = await page.locator("#workload-summary").textContent();
  await page.locator('[data-metric="distance"]').click();
  assert.notEqual(
    await page.locator("#workload-summary").textContent(),
    playerLoad,
  );
  await page.locator('[data-metric="load"]').click();
  const chart = page.locator("#workload-chart svg");
  await chart.focus();
  await page.keyboard.press("ArrowLeft");
  assert.equal(
    await page.locator("#workload-chart .chart-tooltip").isVisible(),
    true,
  );
  await page.keyboard.press("Escape");
  pass("chart metric switching and keyboard inspection");

  await page.locator("#squad-select").selectOption({ label: "Development" });
  assert.equal(await page.locator(".stat-value").first().textContent(), "6");
  await page.locator("#squad-select").selectOption("all");
  await page.locator("#period-select").selectOption("21");
  await ready();
  assert.ok(
    (await page.locator("#workload-summary").textContent()).includes(
      "this period",
    ),
  );
  await page.locator("#period-select").selectOption("7");
  await ready();
  pass("squad and date-range filters rebuild metrics and chart data");

  await go("athletes");
  await page.locator("#roster-search").fill("Arjun");
  await page.waitForFunction(
    () =>
      document.querySelector("#roster-count").textContent ===
      "1 of 24 athletes",
  );
  await page.locator("#roster-table .athlete-link").click();
  await page.locator("#athlete-drawer-chart svg").waitFor();
  await audit("athlete detail drawer");
  await shot("athlete-detail.png");
  await closeDialog();
  await page.locator("#roster-search").fill("no-such-athlete");
  await page.getByText("No athletes found", { exact: true }).waitFor();
  await page.locator("#roster-search").fill("");
  await page.waitForFunction(
    () =>
      document.querySelector("#roster-count").textContent ===
      "24 of 24 athletes",
  );
  await page
    .locator('[data-action="roster-status"][data-status="high"]')
    .click();
  assert.ok(
    (await page.locator("#roster-count").textContent()).startsWith("1 of"),
  );
  await page
    .locator('[data-action="roster-status"][data-status="all"]')
    .click();
  await page
    .locator('[data-action="sort-roster"][data-sort="name-asc"]')
    .click();
  assert.equal(
    await page.locator("#roster-table .athlete-name").first().textContent(),
    "Aarav Desai",
  );
  pass(
    "athlete search, empty state, load-status filtering, sorting, and detail drawer",
  );

  await page.locator("#command-button").click();
  await page.locator("#command-search").fill("Vikram");
  await page.waitForFunction(
    () =>
      document.querySelectorAll("#command-results .command-result").length ===
      1,
  );
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  assert.equal(
    await page.locator("#dialog-title").textContent(),
    "Vikram Singh",
  );
  await closeDialog();
  pass("command search works with the keyboard");

  await page.locator("#import-button").click();
  await audit("import dialog");
  await page.locator("#import-squad").selectOption({ label: "First team" });
  await page
    .locator("#import-file")
    .setInputFiles({
      name: "bad.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("not a CSV"),
    });
  await page
    .getByText(
      "Choose a .csv or .xlsx file. Other file types are not supported.",
      { exact: true },
    )
    .waitFor();
  assert.equal(await page.locator("#submit-import").isDisabled(), true);
  await page
    .locator("#import-file")
    .setInputFiles({
      name: "browser-training.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(
        "Player Name,Date,Player Load\r\nArjun Mehta,2026-11-14,400\r\nVikram Singh,2026-11-14,430\r\n",
      ),
    });
  await page.locator("#submit-import:not([disabled])").waitFor();
  await page.locator("#submit-import").click();
  await page
    .getByText(
      "A simulated batch was added to this browser session. No live data was ingested.",
      { exact: true },
    )
    .waitFor();
  await page.locator('[data-action="import-done"]').click();
  await page.getByText("browser-training.csv", { exact: true }).waitFor();
  pass("file validation and clearly labeled, browser-only demo import");

  await page.locator("#export-button").click();
  await audit("export dialog");
  const downloadEvent = page.waitForEvent("download");
  await page.locator("#download-roster").click();
  const download = await downloadEvent;
  assert.equal(download.suggestedFilename(), "sdip-demo-athletes.csv");
  await download.saveAs(path.join(output, "demo-export.csv"));
  assert.ok(
    (await readFile(path.join(output, "demo-export.csv"), "utf8")).includes(
      "Arjun Mehta",
    ),
  );
  pass("roster CSV download");

  await go("history");
  await page.locator("#history-status").selectOption("attention");
  await page.locator("#history-table .history-file").first().click();
  await page.getByText("Quarantined rows", { exact: true }).waitFor();
  await audit("import lineage and row errors");
  assert.equal(await page.locator(".batch-error-row").count(), 3);
  await closeDialog();
  await page.locator("#history-status").selectOption("all");
  pass("import status filters, batch lineage, and row-error details");

  await go("review");
  await page
    .getByRole("button", { name: "Review match", exact: true })
    .first()
    .click();
  await audit("identity confirmation");
  await page
    .locator("#review-note")
    .fill("Confirmed by browser test against the synthetic fixture.");
  await page.locator("#submit-review").click();
  await page.locator("#app-dialog:not([open])").waitFor({ state: "attached" });
  assert.equal(await page.locator("#review-count").textContent(), "2");
  await page
    .getByRole("button", { name: "Keep separate", exact: true })
    .first()
    .click();
  await page.locator("#submit-review").click();
  await page.locator("#app-dialog:not([open])").waitFor({ state: "attached" });
  assert.equal(await page.locator("#review-count").textContent(), "1");
  pass("confirm and reject identity suggestions update the review queue");

  await go("sources");
  await page.locator('[data-action="sync-source"]').first().click();
  await page
    .getByText("Demo sync completed. No vendor was contacted.", { exact: true })
    .waitFor();
  await ready();
  pass("source sync remains explicitly simulated in demo mode");

  await page.locator("#account-button").click();
  await page.locator("#preferences-button").click();
  await audit("display preferences");
  await page.locator("#preference-distance").selectOption("m");
  await page.locator("#preference-density").selectOption("compact");
  await page
    .getByRole("button", { name: "Save preferences", exact: true })
    .click();
  await go("overview");
  assert.equal(
    await page.evaluate(() => localStorage.getItem("sdip.distance-unit")),
    "m",
  );
  assert.ok(
    await page
      .locator("body")
      .evaluate((element) => element.classList.contains("compact")),
  );
  await page.locator("#account-button").click();
  await page.locator("#preferences-button").click();
  await page.locator("#preference-distance").selectOption("km");
  await page.locator("#preference-density").selectOption("comfortable");
  await page
    .getByRole("button", { name: "Save preferences", exact: true })
    .click();
  pass("distance and density preferences apply and persist");

  for (const width of [320, 390, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: width < 600 ? 844 : 1000 });
    for (const route of Object.keys(names)) {
      await go(route);
      await noOverflow(`${route} at ${width}px`);
    }
    pass(
      `all six pages fit ${width}px without page overflow or overlapping metrics`,
    );
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await go("overview");
  await page.locator("#period-select").selectOption("90");
  await ready();
  await noOverflow("90-day metrics, phone");
  await page.locator("#period-select").selectOption("7");
  await ready();
  await audit("overview, phone");
  await page.locator("#open-mobile-nav").click();
  assert.equal(
    await page.locator("#sidebar").getAttribute("aria-modal"),
    "true",
  );
  await page.locator('.nav-item[data-route="athletes"]').click();
  await page.waitForFunction(
    () => document.querySelector("#breadcrumb-page").textContent === "Athletes",
  );
  assert.equal(
    await page.locator("#open-mobile-nav").getAttribute("aria-expanded"),
    "false",
  );
  pass("mobile navigation opens, changes pages, and closes accessibly");
  await go("overview");
  if (await page.locator('[data-action="dismiss-toast"]').count())
    await page.locator('[data-action="dismiss-toast"]').click();
  await shot("mobile.png", true);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await go("overview");
  await shot("workspace.png");
  assert.equal(
    demoApiCalls.length,
    0,
    `Demo mode made API calls: ${demoApiCalls.join(", ")}`,
  );
  page.off("request", trackDemoApi);
  pass("demo exploration never sends a request to a live API");

  await page.goto(base + "/dashboard.html?signin=1");
  await page.locator("#login-screen:not([hidden])").waitFor();
  await audit("sign-in, desktop");
  await shot("signin.png");
  await page.setViewportSize({ width: 390, height: 844 });
  await noOverflow("sign-in, phone");
  await audit("sign-in, phone");
  await page.locator(".demo-credentials summary").click();
  await page.locator('[data-demo-account="coach"]').click();
  assert.equal(await page.locator("#login-password").inputValue(), "Coach#123");
  await page.locator("#toggle-password").click();
  assert.equal(
    await page.locator("#login-password").getAttribute("type"),
    "text",
  );
  pass("sign-in demo-account chooser and password visibility");

  // A test-only API adapter verifies the production path and role-specific UI without claiming
  // that Java, PostgreSQL, vendor APIs, or server authorization were integration-tested here.
  await page.setViewportSize({ width: 1440, height: 1000 });
  const fixture = createDemoClient();
  const fullRoster = (await fixture.request("/api/athletes")).content;
  let role = "HEAD_COACH",
    teamFailure = false,
    empty = false;
  let uploads = [],
    forbiddenRequests = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      pathname = url.pathname;
    const fulfill = (body, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(body),
      });
    if (pathname === "/api/auth/login")
      return fulfill({
        accessToken: "live-access",
        refreshToken: "live-refresh",
      });
    const squadId =
      role === "ADMIN" ? "" : fullRoster[role === "ANALYST" ? 23 : 0].squadId;
    if (pathname === "/api/auth/me")
      return fulfill({
        role,
        squadId,
        email: "staff@sdip.local",
        userId: "test-user",
      });
    if (request.headers().authorization !== "Bearer live-access")
      return fulfill({ error: "missing bearer token" }, 401);
    if (
      role !== "ADMIN" &&
      (pathname.startsWith("/api/admin/") ||
        pathname.startsWith("/api/connectors/") ||
        pathname.startsWith("/api/links/"))
    ) {
      forbiddenRequests.push(pathname);
      return fulfill({}, 403);
    }
    if (pathname === "/api/ingest/files") {
      uploads.push(request.postDataBuffer().toString("utf8"));
      return fulfill(
        { batchId: "accepted-test-batch", status: "STARTED" },
        202,
      );
    }
    if (pathname === "/api/athletes") {
      const scoped = empty
        ? []
        : fullRoster.filter(
            (athlete) => !squadId || athlete.squadId === squadId,
          );
      return fulfill({
        content: scoped,
        totalElements: scoped.length,
        last: true,
      });
    }
    if (pathname === "/api/dashboard/team/load" && teamFailure)
      return fulfill({ message: "test-only backend failure" }, 500);
    try {
      let response = await fixture.request(pathname + url.search, {
        method: request.method(),
      });
      if (pathname === "/api/dashboard/team/load")
        response = empty
          ? []
          : response.filter(
              (row) =>
                !squadId ||
                fullRoster.find((athlete) => athlete.id === row.athleteId)
                  ?.squadId === squadId,
            );
      return fulfill(response);
    } catch {
      return fulfill({ error: "Test route not implemented" }, 404);
    }
  });
  async function loginAs(nextRole, options = {}) {
    role = nextRole;
    empty = Boolean(options.empty);
    teamFailure = Boolean(options.failure);
    await page.goto(base + "/dashboard.html?signin=1");
    await page.locator("#login-screen:not([hidden])").waitFor();
    await page.locator("#login-email").fill("staff@sdip.local");
    await page.locator("#login-password").fill("test-password");
    await page.locator(".login-submit").click();
    await ready();
  }
  await loginAs("HEAD_COACH");
  assert.equal(await page.locator(".stat-value").first().textContent(), "18");
  assert.equal(await page.locator('[data-route="sources"]').isVisible(), false);
  assert.equal(await page.locator('[data-route="review"]').isVisible(), false);
  assert.equal(await page.locator("#export-button").isVisible(), true);
  await page.locator("#import-button").click();
  assert.equal(await page.locator("#import-squad").count(), 0);
  await page
    .locator("#import-file")
    .setInputFiles({
      name: "coach.csv",
      mimeType: "text/csv",
      buffer: Buffer.from("Name,Load\nTest,200"),
    });
  await page.locator("#submit-import:not([disabled])").click();
  await page.getByText("Your import has started", { exact: true }).waitFor();
  assert.ok(!uploads.at(-1).includes('name="squadId"'));
  await closeDialog();
  pass(
    "production API path: scoped coach sign-in, bearer requests, and accepted upload",
  );

  await loginAs("PRACTITIONER");
  assert.equal(await page.locator("#import-button").isVisible(), true);
  assert.equal(await page.locator("#export-button").isVisible(), false);
  await loginAs("ANALYST");
  assert.equal(await page.locator(".stat-value").first().textContent(), "6");
  assert.equal(await page.locator("#import-button").isVisible(), false);
  assert.equal(await page.locator("#export-button").isVisible(), false);
  await page.evaluate(() => {
    location.hash = "/review";
  });
  await page.waitForFunction(() => location.hash === "#/overview");
  assert.deepEqual(forbiddenRequests, []);
  pass(
    "production API path: practitioner and analyst permissions, including restricted deep links",
  );

  await loginAs("ADMIN", { empty: true });
  assert.equal(await page.locator(".stat-value").first().textContent(), "0");
  await page.getByText("No athletes in this view", { exact: true }).waitFor();
  await audit("overview, empty roster");
  await page.locator("#import-button").click();
  assert.equal(await page.locator("#import-squad option").count(), 3);
  await page.locator("#import-squad").selectOption({ label: "First team" });
  await page
    .locator("#import-file")
    .setInputFiles({
      name: "admin.xlsx",
      mimeType:
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      buffer: Buffer.from("test-only file, server validation is mocked"),
    });
  await page.locator("#submit-import:not([disabled])").click();
  await page.getByText("Your import has started", { exact: true }).waitFor();
  assert.ok(uploads.at(-1).includes('name="squadId"'));
  await closeDialog();
  pass(
    "production API path: an admin can select an empty destination squad and submit XLSX",
  );

  await loginAs("ADMIN", { failure: true });
  await page
    .getByText("Your team data could not be loaded", { exact: true })
    .waitFor();
  await audit("workspace load error");
  teamFailure = false;
  await page.locator('[data-action="refresh"]').click();
  await page.locator(".stat-value").first().waitFor();
  assert.equal(await page.locator(".stat-value").first().textContent(), "24");
  pass("production API path: loading failure and successful retry");

  await page.locator("#account-button").click();
  await page.locator("#logout-button").click();
  await page.locator("#login-screen:not([hidden])").waitFor();
  assert.equal(
    await page.evaluate(() => localStorage.getItem("sdip.at")),
    null,
  );
  pass("sign-out removes authentication tokens");

  if (process.argv.includes("--screenshots")) {
    const docs = path.resolve("docs/images");
    await mkdir(docs, { recursive: true });
    for (const [source, dest] of [
      ["workspace.png", "05-workspace.png"],
      ["mobile.png", "06-mobile.png"],
      ["signin.png", "07-signin.png"],
    ])
      await copyFile(path.join(output, source), path.join(docs, dest));
  }
  assert.deepEqual(errors, [], "Uncaught browser errors");
  await writeFile(
    path.join(output, "report.json"),
    JSON.stringify(
      {
        passed: results.length,
        results,
        uncaughtErrors: errors,
        accessibilityViolations: violations,
      },
      null,
      2,
    ),
  );
  assert.deepEqual(
    violations,
    [],
    "Accessibility violations; see .cache/frontend-check/report.json",
  );
  console.log(
    `\n${results.length} browser checks passed. No uncaught JavaScript errors or WCAG A/AA violations in the audited states.`,
  );
} catch (error) {
  await shot("failure.png", true).catch(() => {});
  await writeFile(
    path.join(output, "report.json"),
    JSON.stringify(
      {
        passed: results.length,
        results,
        error: error.message,
        uncaughtErrors: errors,
        accessibilityViolations: violations,
      },
      null,
      2,
    ),
  );
  throw error;
} finally {
  await browser.close();
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
}
