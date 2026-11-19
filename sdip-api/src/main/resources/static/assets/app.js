import { createApiClient } from "./api.js";
import {
  buildWorkspace,
  dayKey,
  dateWindow,
  filterRoster,
  mapConcurrent,
  parseCsv,
  percentChange,
  shiftDay,
} from "./model.js";
import {
  $,
  $$,
  avatar,
  debounce,
  emptyState,
  esc,
  formatDate,
  formatDateTime,
  formatNumber as num,
  icon,
  initials,
  loadingPanel,
  relativeTime,
  saveBlob,
  statusTag,
} from "./ui.js";
import { mountLineChart, sparkline } from "./charts.js";

const pages = {
  overview: {
    name: "Overview",
    title: "Team overview",
    description: "The complete picture of your team, in one place.",
    kicker: "YOUR PERFORMANCE WORKSPACE",
    icon: "layout-dashboard",
  },
  athletes: {
    name: "Athletes",
    title: "Every athlete, in view",
    description:
      "Connected profiles. Individual training loads. More context for your team.",
    kicker: "YOUR TEAM",
    icon: "users",
  },
  fatigue: {
    name: "Fatigue analysis",
    title: "Connect the signals",
    description: "Explore training load alongside VALD ForceDecks testing.",
    kicker: "LOAD & NEUROMUSCULAR PERFORMANCE",
    icon: "heart-pulse",
  },
  sources: {
    name: "Data sources",
    title: "Your data, connected",
    description:
      "Bring your monitoring systems into one performance workspace.",
    kicker: "CONNECTED INTEGRATIONS",
    icon: "database",
    admin: true,
  },
  history: {
    name: "Import history",
    title: "Every import, accounted for",
    description: "Follow your data from the source to the athlete profile.",
    kicker: "DATA LINEAGE",
    icon: "file-clock",
  },
  review: {
    name: "Identity review",
    title: "Make the right connection",
    description: "Review suggested matches before athlete records are linked.",
    kicker: "HUMAN-IN-THE-LOOP",
    icon: "link-2",
    admin: true,
  },
};
const roleLabels = {
  ADMIN: "Workspace admin",
  HEAD_COACH: "Head coach",
  PRACTITIONER: "Practitioner",
  ANALYST: "Read-only analyst",
};
const readPreference = (key, fallback) => {
  try {
    return localStorage.getItem(`sdip.${key}`) || fallback;
  } catch {
    return fallback;
  }
};
const state = {
  demo: new URLSearchParams(location.search).get("demo") === "1",
  client: null,
  me: null,
  route: routeFromHash(),
  days: 7,
  squad: "all",
  today: dayKey(),
  athletes: [],
  teamRows: [],
  loads: new Map(),
  loadCache: new Map(),
  connectors: [],
  batches: [],
  quarantine: [],
  profiles: [],
  squads: [],
  errors: {},
  loaded: false,
  model: null,
  chartMetric: "load",
  rosterQuery: "",
  rosterStatus: "all",
  rosterSort: "load-desc",
  historyFilter: "all",
  fatigueId: "",
  fatigueCache: new Map(),
  distanceUnit: readPreference("distance-unit", "km") === "m" ? "m" : "km",
  compact: readPreference("density", "comfortable") === "compact",
  refreshAt: null,
};
let loadVersion = 0,
  pageVersion = 0,
  dialogVersion = 0;
let chartCleanups = [],
  dialogChartCleanups = [];
let toastTimer, enterTimer;
let importFile = null,
  importRows = 0,
  importValidating = false,
  importSelectionVersion = 0;
const allowed = (...roles) => roles.includes(state.me?.role);
const isAdmin = () => allowed("ADMIN");
const canImport = () => allowed("ADMIN", "HEAD_COACH", "PRACTITIONER");
const canExport = () => allowed("ADMIN", "HEAD_COACH");
const distance = (meters) =>
  state.distanceUnit === "km" ? num(meters / 1000, 1) : num(meters);
const distanceValue = (meters) =>
  state.distanceUnit === "km" ? meters / 1000 : meters;
const distanceDecimals = () => (state.distanceUnit === "km" ? 1 : 0);
const pageLink = (route, label, extra = "") =>
  `<button type="button" class="text-button ${extra}" data-action="navigate" data-page="${route}">${esc(label)}${icon("arrow-up-right", "small")}</button>`;

function routeFromHash() {
  const route = location.hash.replace(/^#\/?/, "").split("?")[0];
  return Object.hasOwn(pages, route) ? route : "overview";
}
function navigate(route) {
  if (!Object.hasOwn(pages, route)) return;
  closePopovers();
  closeMobileNav();
  if (state.route === route && routeFromHash() === route) renderPage();
  else location.hash = `/${route}`;
}
function showLogin(message = "") {
  state.me = null;
  state.loaded = false;
  loadVersion++;
  clearCharts();
  closeDialog();
  closeMobileNav();
  $("#boot-screen").hidden = true;
  $("#workspace").hidden = true;
  $("#login-screen").hidden = false;
  $("#login-error").textContent = message;
  $("#login-error").hidden = !message;
  document.title = "Sign in · SDIP";
  $("#login-password").value = "";
}
function toast(message, error = false) {
  clearTimeout(toastTimer);
  $("#toast-region").innerHTML =
    `<div class="toast${error ? " error" : ""}">${icon(error ? "info" : "check-circle-2")}<span>${esc(message)}</span><button type="button" class="icon-button" data-action="dismiss-toast" aria-label="Dismiss notification">${icon("x")}</button></div>`;
  toastTimer = setTimeout(
    () => {
      $("#toast-region").innerHTML = "";
    },
    error ? 9000 : 6500,
  );
}
function closePopovers() {
  $("#account-menu").hidden = true;
  $("#activity-popover").hidden = true;
  $("#account-button").setAttribute("aria-expanded", "false");
  $("#activity-button").setAttribute("aria-expanded", "false");
}
function clearCharts() {
  chartCleanups.forEach((cleanup) => cleanup());
  chartCleanups = [];
}
function clearDialogCharts() {
  dialogChartCleanups.forEach((cleanup) => cleanup());
  dialogChartCleanups = [];
}
function closeDialog() {
  dialogVersion++;
  clearDialogCharts();
  if ($("#app-dialog").open) $("#app-dialog").close();
}
function openDialog(content, type = "") {
  closePopovers();
  closeDialog();
  const dialog = $("#app-dialog");
  dialog.className = `app-dialog${type ? ` ${type}` : ""}`;
  $("#dialog-content").innerHTML = content;
  dialog.showModal();
  return dialogVersion;
}
function dialogHeader(title, description = "", extra = "") {
  return `<div class="dialog-header"><div>${extra}<h2 id="dialog-title">${esc(title)}</h2>${description ? `<p>${esc(description)}</p>` : ""}</div><button class="icon-button" type="button" data-action="close-dialog" aria-label="Close dialog">${icon("x")}</button></div>`;
}
function chartLegend(items) {
  return `<div class="chart-legend">${items.map((item) => `<span class="legend-item"><span class="legend-swatch ${item.className || ""}"></span>${esc(item.label)}</span>`).join("")}</div>`;
}
function renderError(
  message,
  description = "Your data has not been changed. Refresh to try again.",
) {
  return `<div class="panel">${emptyState(message, description, { symbol: "cloud-upload", action: `<button class="button secondary" type="button" data-action="refresh">${icon("refresh-cw")}Retry loading data</button>` })}</div>`;
}

async function boot() {
  document.body.classList.toggle("compact", state.compact);
  if (!/Mac|iPhone|iPad/.test(navigator.platform))
    $("#command-key").textContent = "Ctrl K";
  if (state.demo) {
    const { createDemoClient } = await import("./demo-data.js");
    state.client = createDemoClient(state.today);
    await enterWorkspace();
  } else {
    state.client = createApiClient({
      onUnauthorized: () =>
        showLogin("Your session has expired. Please sign in again."),
    });
    if (
      state.client.hasSession &&
      !new URLSearchParams(location.search).has("signin")
    ) {
      try {
        await enterWorkspace();
      } catch (error) {
        showLogin(error.message);
      }
    } else showLogin();
  }
}

async function enterWorkspace() {
  state.me = await state.client.request("/api/auth/me");
  const displayName =
    state.me.displayName ||
    {
      ADMIN: "Administrator",
      HEAD_COACH: "Head coach",
      PRACTITIONER: "Practitioner",
      ANALYST: "Analyst",
    }[state.me.role] ||
    state.me.email;
  $("#account-name").textContent = displayName;
  $("#account-initials").textContent = initials(displayName);
  $("#account-role").textContent = roleLabels[state.me.role] || state.me.role;
  $("#account-email").textContent = state.me.email;
  $("#workspace-mode").textContent = state.demo
    ? "Demo workspace"
    : state.me.squadId
      ? "Squad-scoped workspace"
      : "All-squad workspace";
  $("#logout-label").textContent = state.demo ? "Leave demo" : "Sign out";
  $("#connection-badge").innerHTML =
    `<span></span>${state.demo ? "Demo workspace" : "Secure workspace"}`;
  $("#connection-badge").classList.toggle("live", !state.demo);
  $("#workspace-footer-note").innerHTML =
    `${icon("shield-check", "small")}${state.demo ? "Sample data · Not connected to live athletes" : "Role-based access · Squad-scoped data"}`;
  $$("[data-roles]").forEach((element) => {
    element.hidden = !element.dataset.roles.split(",").includes(state.me.role);
  });
  $("#boot-screen").hidden = true;
  $("#login-screen").hidden = true;
  $("#workspace").hidden = false;
  document.title = `${pages[state.route].name} · SDIP`;
  renderPage();
  await loadWorkspace();
  document.body.classList.add("workspace-enter");
  clearTimeout(enterTimer);
  enterTimer = setTimeout(
    () => document.body.classList.remove("workspace-enter"),
    650,
  );
  updateMobileNav();
}

async function fetchAllAthletes() {
  const athletes = [];
  for (let page = 0; page < 20; page++) {
    const result = await state.client.request(
      `/api/athletes?size=200&page=${page}&sort=fullName,asc`,
    );
    athletes.push(...(result.content || []));
    if (result.last !== false || !result.content?.length) return athletes;
  }
  throw new Error(
    "This workspace is too large to load in one view. Ask an administrator to use squad-scoped access.",
  );
}

async function loadWorkspace({ force = false } = {}) {
  const version = ++loadVersion;
  const days = state.days;
  const seriesDays = Math.max(42, days * 2);
  $("#refresh-button").classList.add("is-loading");
  $("#refresh-button").disabled = true;
  $("#updated-label").textContent = "Refreshing…";
  $("#page-content").setAttribute("aria-busy", "true");
  const tasks = {
    athletes: fetchAllAthletes(),
    team: state.client.request(`/api/dashboard/team/load?days=${days}`),
    batches: state.client.request(
      "/api/ingest/batches?size=100&sort=startedAt,desc",
    ),
    profiles: state.client.request("/api/ingest/mapping-profiles"),
    squads: isAdmin()
      ? state.client.request("/api/admin/squads")
      : Promise.resolve([]),
    connectors: isAdmin()
      ? state.client.request("/api/connectors/state")
      : Promise.resolve([]),
    quarantine: isAdmin()
      ? state.client.request(
          "/api/links/quarantine?size=100&status=PENDING&sort=createdAt,desc",
        )
      : Promise.resolve({ content: [] }),
  };
  const results = await Promise.allSettled(Object.values(tasks));
  if (version !== loadVersion || !state.me) return;
  const errors = {};
  const data = {};
  Object.keys(tasks).forEach((key, index) => {
    if (results[index].status === "fulfilled") data[key] = results[index].value;
    else errors[key] = results[index].reason.message;
  });
  const athletes = data.athletes || state.athletes;
  const loads = new Map();
  if (!errors.athletes) {
    await mapConcurrent(athletes, async (athlete) => {
      try {
        let cache = state.loadCache.get(athlete.id);
        if (force || !cache || cache.days < seriesDays) {
          const result = await state.client.request(
            `/api/dashboard/athlete/${encodeURIComponent(athlete.id)}/load?days=${seriesDays}`,
          );
          cache = { days: seriesDays, records: result.days || [] };
          // An older, shorter request must never replace a newer, larger window.
          if (version === loadVersion) state.loadCache.set(athlete.id, cache);
        }
        loads.set(athlete.id, cache.records);
      } catch (error) {
        errors.trend = error.message;
      }
    });
  }
  if (version !== loadVersion || !state.me) return;
  state.athletes = athletes;
  state.teamRows = data.team || state.teamRows;
  state.batches = data.batches?.content || [];
  state.connectors = data.connectors || [];
  state.quarantine = data.quarantine?.content || [];
  state.profiles = data.profiles || [];
  state.squads = data.squads || [];
  state.loads = loads;
  state.errors = errors;
  if (force) state.fatigueCache.clear();
  if (
    state.squad !== "all" &&
    !athletes.some((athlete) => athlete.squadId === state.squad) &&
    !state.squads.some((squad) => squad.id === state.squad)
  )
    state.squad = "all";
  state.model = buildWorkspace(
    athletes,
    state.teamRows,
    loads,
    days,
    state.squad,
    state.today,
  );
  state.loaded = true;
  if (!errors.athletes && !errors.team) state.refreshAt = new Date();
  updateFilterOptions();
  updateReviewBadge();
  $("#refresh-button").classList.remove("is-loading");
  $("#refresh-button").disabled = false;
  $("#updated-label").textContent = state.refreshAt
    ? "Updated just now"
    : "Refresh needed";
  renderPage();
}

function updateFilterOptions() {
  const squads =
    isAdmin() && state.squads.length
      ? state.squads.map((squad) => [squad.id, squad.name])
      : [
          ...new Map(
            state.athletes.map((athlete) => [
              athlete.squadId,
              athlete.squadName,
            ]),
          ).entries(),
        ];
  $("#squad-select").innerHTML =
    `<option value="all">${isAdmin() ? "All squads" : "My squad"}</option>${squads.map(([id, name]) => `<option value="${esc(id)}">${esc(name)}</option>`).join("")}`;
  $("#squad-select").value = state.squad;
  $("#period-select").value = String(state.days);
  const window = dateWindow(state.days, state.today);
  $("#date-range").textContent =
    `${formatDate(window.from)} – ${formatDate(window.to, { year: "numeric" })}`;
}
function updateReviewBadge() {
  const count = state.quarantine.filter(
    (item) => item.status === "PENDING",
  ).length;
  $("#review-count").textContent = String(count);
  $("#review-count").hidden = !count;
  $("#notification-dot").hidden = !count;
}

function renderPage() {
  if (!state.me) return;
  if (pages[state.route].admin && !isAdmin()) {
    state.route = "overview";
    history.replaceState(
      null,
      "",
      `${location.pathname}${location.search}#/overview`,
    );
  }
  const page = pages[state.route];
  document.title = `${page.name} · SDIP`;
  $("#breadcrumb-page").textContent = page.name;
  $("#page-title").innerHTML =
    `${esc(page.title)}<span class="heading-dot">.</span>`;
  $("#page-description").textContent = page.description;
  $("#page-kicker").textContent = page.kicker;
  $("#filter-bar").hidden = !["overview", "athletes", "fatigue"].includes(
    state.route,
  );
  $$(".nav-item[data-route]").forEach((item) => {
    if (item.dataset.route === state.route)
      item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  });
  clearCharts();
  const notice = $("#global-notice");
  const secondaryErrors = Object.keys(state.errors).filter(
    (key) => !["athletes", "team"].includes(key),
  );
  notice.hidden = !secondaryErrors.length;
  if (secondaryErrors.length)
    notice.innerHTML = `${icon("info")}<span>Some workspace data could not be loaded. Unavailable panels are marked below. Use refresh to try again.</span>`;
  if (!state.loaded) {
    $("#page-content").innerHTML = loadingPanel();
    return;
  }
  $("#page-content").setAttribute("aria-busy", "false");
  pageVersion++;
  ({
    overview: renderOverview,
    athletes: renderAthletes,
    fatigue: renderFatigue,
    sources: renderSources,
    history: renderHistory,
    review: renderReview,
  })[state.route]();
}

function deltaMarkup(current, previous, fallback) {
  const change =
    state.demo && !state.errors.trend ? percentChange(current, previous) : null;
  if (change == null)
    return `<span class="stat-detail">${esc(fallback)}</span>`;
  return `<span class="stat-delta${change < 0 ? " down" : ""}">${icon(change < 0 ? "trending-down" : "trending-up")}${change > 0 ? "+" : ""}${num(change, 1)}%</span><span>vs previous ${state.days} days</span>`;
}
function statCard({
  label,
  value,
  unit = "",
  symbol,
  bottom,
  trend = [],
  accent = false,
  sparkColor,
}) {
  return `<article class="stat-card"><div class="stat-top"><span class="stat-title">${esc(label)}</span><span class="stat-icon${accent ? " orange" : ""}">${icon(symbol)}</span></div><div class="stat-value-row"><p class="stat-value${String(value).length >= 7 ? " long-value" : ""}">${esc(value)}${unit ? `<small>${esc(unit)}</small>` : ""}</p>${trend.length ? sparkline(trend, sparkColor || "#e99c7b", accent) : ""}</div><div class="stat-bottom">${bottom}</div></article>`;
}
function renderStats() {
  const { totals, trend } = state.model;
  const loadTrend = state.errors.trend
    ? []
    : trend.slice(-7).map((day) => day.playerLoad);
  const distanceTrend = state.errors.trend
    ? []
    : trend.slice(-7).map((day) => day.totalDistanceM);
  const sessionTrend = state.errors.trend
    ? []
    : trend
        .slice(-7)
        .map(
          (day) =>
            state.model.roster.filter((athlete) =>
              athlete.days.some((record) => record.day === day.day),
            ).length,
        );
  return `<div class="stat-grid">
    ${statCard({ label: "Monitored athletes", value: num(totals.athletes), symbol: "users", bottom: `<span class="stat-detail"><span class="tiny-dot"></span>${num(totals.active)} active across ${totals.squads} ${totals.squads === 1 ? "squad" : "squads"}</span>` })}
    ${statCard({ label: "Total player load", value: num(totals.playerLoad), unit: "AU", symbol: "activity", accent: true, trend: loadTrend, bottom: deltaMarkup(totals.playerLoad, totals.previousLoad, "Accumulated GPS player load") })}
    ${statCard({ label: "Distance covered", value: distance(totals.distanceM), unit: state.distanceUnit, symbol: "flag", trend: distanceTrend, sparkColor: "#77a890", bottom: deltaMarkup(totals.distanceM, totals.previousDistanceM, "Total GPS distance recorded") })}
    ${statCard({ label: "Athlete sessions", value: num(totals.sessions), symbol: "calendar-days", trend: sessionTrend, sparkColor: "#a9b7c5", bottom: `<span class="stat-detail">${icon("check-circle-2")}${totals.sessions ? "Individual GPS session records" : "No sessions in this period"}</span>` })}
  </div>`;
}

function renderOverview() {
  if (state.errors.athletes || state.errors.team) {
    $("#page-content").innerHTML = renderError(
      "Your team data could not be loaded",
      state.errors.athletes || state.errors.team,
    );
    return;
  }
  const { roster } = state.model;
  const snapshot = roster.slice(0, 5);
  $("#page-content").innerHTML =
    `${renderStats()}<div class="overview-grid"><article class="panel workload-panel"><div class="panel-header"><div><h2 class="panel-title">Team workload<button class="info-button" type="button" data-action="workload-info" aria-label="About workload data">${icon("info")}</button></h2><p class="panel-subtitle">Daily training load. A little more perspective.</p></div><div class="segmented" aria-label="Chart metric"><button type="button" data-action="chart-metric" data-metric="load" aria-pressed="${state.chartMetric === "load"}">Player load</button><button type="button" data-action="chart-metric" data-metric="distance" aria-pressed="${state.chartMetric === "distance"}">Distance</button></div></div><div id="workload-summary" class="workload-summary"></div><div id="workload-chart" class="chart-wrap"></div>${chartLegend([{ label: "Current period" }, { label: "Previous period", className: "previous" }])}</article>${renderSourceSummary()}</div>
    <article class="panel snapshot-panel"><div class="panel-header"><div><h2 class="panel-title">Athlete snapshot</h2><p class="panel-subtitle">Individual performance, with the context that matters.</p></div>${pageLink("athletes", "View all athletes")}</div>${renderAthleteTable(snapshot, true)}<div class="table-foot"><span>Showing ${snapshot.length} of ${roster.length} athletes</span><span>Load status based on latest ACWR</span></div></article>`;
  mountOverviewChart();
}
function mountOverviewChart() {
  const { trend, totals } = state.model;
  const isDistance = state.chartMetric === "distance";
  const currentTotal = isDistance ? totals.distanceM : totals.playerLoad;
  const previousTotal = isDistance
    ? totals.previousDistanceM
    : totals.previousLoad;
  const change = !state.errors.trend
    ? percentChange(currentTotal, previousTotal)
    : null;
  $("#workload-summary").innerHTML =
    `<strong class="workload-number">${isDistance ? distance(currentTotal) : num(currentTotal)}<small>${isDistance ? state.distanceUnit : "AU"} this period</small></strong>${state.demo && change != null ? `<span class="workload-change">${icon(change >= 0 ? "trending-up" : "trending-down")}${change > 0 ? "+" : ""}${num(change, 1)}%</span>` : ""}`;
  if (state.errors.trend) {
    $("#workload-chart").innerHTML =
      `<div class="chart-empty empty-state" style="padding:45px 15px">${icon("info")}<p>Daily data could not be loaded. Refresh to retry.</p></div>`;
    return;
  }
  const value = (key) =>
    trend.map((day) => (isDistance ? distanceValue(day[key]) : day[key]));
  chartCleanups.push(
    mountLineChart($("#workload-chart"), {
      title: `Team ${isDistance ? "distance" : "player load"} by UTC day`,
      labels: trend.map((day) => day.day),
      series: [
        {
          label: "Current period",
          values: value(isDistance ? "totalDistanceM" : "playerLoad"),
          color: "#e86532",
          fill: true,
          unit: isDistance ? state.distanceUnit : "AU",
          decimals: isDistance ? distanceDecimals() : 0,
        },
        {
          label: "Previous period",
          values: value(isDistance ? "previousDistanceM" : "previousLoad"),
          color: "#cbd0d9",
          dashed: true,
          unit: isDistance ? state.distanceUnit : "AU",
          decimals: isDistance ? distanceDecimals() : 0,
        },
      ],
    }),
  );
}
function renderSourceSummary() {
  const canSee = isAdmin();
  const sourceRow = (vendor, label, description, symbol) => {
    const source = state.connectors.find((item) => item.vendor === vendor);
    const hasSync = Boolean(source?.lastSyncedAt);
    return `<div class="source-mini"><span class="vendor-icon${vendor === "VALD" ? " vald" : ""}">${icon(symbol)}</span><span class="vendor-label"><strong>${label}</strong><small>${description}</small></span>${statusTag(canSee ? (hasSync ? (state.demo ? "Connected" : "Synced") : "Not synced") : "Scoped", hasSync ? "" : "neutral")}</div>`;
  };
  const latest = state.connectors
    .map((item) => item.lastSyncedAt)
    .filter(Boolean)
    .sort()
    .at(-1);
  const recent =
    state.connectors.length === 2 &&
    state.connectors.every(
      (item) =>
        item.lastSyncedAt &&
        Date.now() - new Date(item.lastSyncedAt).getTime() < 900_000,
    );
  return `<article class="panel sources-panel"><div class="panel-header"><h2 class="panel-title">Connected sources</h2>${canSee ? pageLink("sources", "Manage") : ""}</div>${sourceRow("CATAPULT", "Catapult", "GPS & training load", "radio")}${sourceRow("VALD", "VALD", "Strength & force testing", "zap")}<div class="source-sync">${icon("refresh-cw")}<span>${state.errors.connectors ? "Sync details unavailable" : canSee ? (latest ? `Last synced ${relativeTime(latest).toLowerCase()}` : "No sync recorded yet") : "Connection details are admin-only"}</span></div><div class="source-health">${icon("shield-check")}<span><strong>${state.demo ? "Your sample sources are in sync." : recent ? "Recent syncs recorded." : "One connected athlete view."}</strong><small>${state.demo ? "Explore without affecting live data." : "GPS and ForceDecks, side by side."}</small></span></div><button type="button" class="source-manage" data-action="${canSee ? "navigate" : "help"}"${canSee ? ' data-page="sources"' : ""}>${canSee ? "View integrations" : "About connected data"}${icon("arrow-right")}</button></article>`;
}
function renderAthleteTable(roster, snapshot = false) {
  if (!roster.length)
    return emptyState(
      state.rosterQuery ? "No athletes found" : "No athletes in this view",
      state.rosterQuery
        ? "Try another name or squad, or clear the load-status filter."
        : "Athletes appear here when scoped data is imported.",
      {
        symbol: "users",
        action:
          canImport() && !state.rosterQuery
            ? '<button class="button secondary" type="button" data-action="open-import">Import your first file</button>'
            : "",
      },
    );
  const maximum = Math.max(
    1,
    ...state.model.roster.map((athlete) => athlete.playerLoad),
  );
  return `<div class="table-scroll"><table class="data-table"><thead><tr><th scope="col">${snapshot ? "Athlete" : `<button type="button" class="table-sort" data-action="sort-roster" data-sort="name-asc">Athlete${icon("chevron-down")}</button>`}</th><th scope="col">Squad</th><th scope="col">${snapshot ? "Player load" : `<button type="button" class="table-sort" data-action="sort-roster" data-sort="${state.rosterSort === "load-desc" ? "load-asc" : "load-desc"}">Player load${icon(state.rosterSort === "load-asc" ? "arrow-up" : "arrow-down")}</button>`}</th><th scope="col">Distance (${state.distanceUnit})</th>${snapshot ? "" : '<th scope="col">Sessions</th>'}<th scope="col"><span title="Acute:chronic workload ratio, using an uncoupled 7:21-day window">ACWR</span></th><th scope="col">Load status</th><th scope="col"><span class="sr-only">View athlete</span></th></tr></thead><tbody>${roster.map((athlete) => `<tr><td><button type="button" class="athlete-cell athlete-link" data-action="open-athlete" data-id="${esc(athlete.id)}">${avatar(athlete.fullName)}<span><strong class="athlete-name">${esc(athlete.fullName)}</strong><span class="athlete-meta">${esc(athlete.position || (athlete.provisional ? "Provisional profile" : "Canonical athlete"))}</span></span></button></td><td>${esc(athlete.squadName)}</td><td><span class="load-cell">${num(athlete.playerLoad)}<span class="load-meter" aria-hidden="true"><span style="width:${Math.max(0, Math.min(100, (athlete.playerLoad / maximum) * 100)).toFixed(0)}%"></span></span></span></td><td>${distance(athlete.totalDistanceM)}</td>${snapshot ? "" : `<td>${num(athlete.sessions)}</td>`}<td>${num(athlete.acwr, 2)}</td><td>${statusTag(athlete.status.label, athlete.status.tone)}</td><td><button class="row-arrow" type="button" data-action="open-athlete" data-id="${esc(athlete.id)}" aria-label="View ${esc(athlete.fullName)}">${icon("chevron-right")}</button></td></tr>`).join("")}</tbody></table></div>`;
}
function renderAthletes() {
  if (state.errors.athletes || state.errors.team) {
    $("#page-content").innerHTML = renderError(
      "Athlete data could not be loaded",
    );
    return;
  }
  const counts = (key) =>
    state.model.roster.filter(
      (athlete) => key === "all" || athlete.status.key === key,
    ).length;
  $("#page-content").innerHTML =
    `<article class="panel"><div class="roster-toolbar"><div class="roster-tabs" aria-label="Filter by workload status">${[
      ["all", "All athletes"],
      ["balanced", "Balanced"],
      ["elevated", "Elevated"],
      ["high", "High load"],
    ]
      .map(
        ([key, label]) =>
          `<button type="button" class="roster-tab${state.rosterStatus === key ? " active" : ""}" aria-pressed="${state.rosterStatus === key}" data-action="roster-status" data-status="${key}">${label}<span>${counts(key)}</span></button>`,
      )
      .join(
        "",
      )}</div><div class="search-field">${icon("search")}<label class="sr-only" for="roster-search">Search athletes by name or squad</label><input type="search" id="roster-search" placeholder="Search athletes…" value="${esc(state.rosterQuery)}" autocomplete="off"></div></div><div id="roster-table"></div><div class="table-foot"><span id="roster-count"></span><span>All data shown in UTC</span></div></article><p class="roster-note">${icon("info")}ACWR compares 7-day acute load with the preceding 21-day baseline. Load flags are context, not a medical assessment.</p>`;
  updateRosterTable();
}
function updateRosterTable() {
  if (!$("#roster-table")) return;
  const roster = filterRoster(
    state.model.roster,
    state.rosterQuery,
    state.rosterStatus,
    state.rosterSort,
  );
  $("#roster-table").innerHTML = renderAthleteTable(roster);
  $("#roster-count").textContent =
    `${roster.length} of ${state.model.roster.length} athletes`;
}

async function renderFatigue() {
  if (state.errors.athletes) {
    $("#page-content").innerHTML = renderError(
      "Athlete profiles could not be loaded",
    );
    return;
  }
  const roster = state.model.roster;
  if (!roster.length) {
    $("#page-content").innerHTML =
      `<article class="panel">${emptyState("An athlete profile comes first", "Import athlete data to explore training load and ForceDecks results.", { symbol: "heart-pulse" })}</article>`;
    return;
  }
  if (!roster.some((athlete) => athlete.id === state.fatigueId))
    state.fatigueId = roster[0].id;
  $("#page-content").innerHTML =
    `<div class="fatigue-athlete-control"><label for="fatigue-athlete">Athlete profile</label><div class="select-wrap"><select class="athlete-select" id="fatigue-athlete">${roster.map((athlete) => `<option value="${esc(athlete.id)}"${athlete.id === state.fatigueId ? " selected" : ""}>${esc(athlete.fullName)} · ${esc(athlete.squadName)}</option>`).join("")}</select>${icon("chevron-down", "small select-chevron")}</div></div><div id="fatigue-content">${loadingPanel("Connecting load and force-test data…")}</div>`;
  const id = state.fatigueId,
    version = pageVersion,
    days = Math.max(90, state.days);
  try {
    const key = `${id}:${days}`;
    let result = state.fatigueCache.get(key);
    if (!result) {
      result = await state.client.request(
        `/api/dashboard/athlete/${encodeURIComponent(id)}/fatigue-overlay?days=${days}`,
      );
      state.fatigueCache.set(key, result);
    }
    if (
      version !== pageVersion ||
      state.route !== "fatigue" ||
      id !== state.fatigueId ||
      !state.me
    )
      return;
    const window = state.model.window;
    const inWindow = (record) =>
      record.day >= window.from && record.day <= window.to;
    const tests = (result.forceTests || []).filter(inWindow);
    const load = (result.days || []).filter(inWindow);
    const latest = tests.at(-1);
    const athlete = roster.find((value) => value.id === id);
    $("#fatigue-content").innerHTML = `<div class="stat-grid">
      ${statCard({ label: "Player load", value: num(athlete.playerLoad), unit: "AU", symbol: "activity", accent: true, bottom: '<span class="stat-detail">Selected period · Catapult GPS</span>' })}
      ${statCard({ label: "Latest RSI-mod", value: num(latest?.rsiModified, 3), symbol: "zap", bottom: `<span class="stat-detail">${latest ? `${esc(latest.testType)} · ${formatDate(latest.day)}` : "No force tests in this period"}</span>` })}
      ${statCard({ label: "Peak force", value: num(latest?.peakForceN), unit: "N", symbol: "gauge", bottom: '<span class="stat-detail">Latest force-test daily average</span>' })}
      ${statCard({ label: "L/R asymmetry", value: num(latest?.asymmetryPct, 1), unit: "%", symbol: "target", bottom: '<span class="stat-detail">Latest force-test daily average</span>' })}
      </div><div class="fatigue-panels"><article class="panel fatigue-chart-panel"><div class="panel-header"><div><h2 class="panel-title">Load &amp; RSI-mod</h2><p class="panel-subtitle">Left axis: player load (AU) · Right axis: RSI-mod${latest ? ` (${esc(latest.testType)})` : ""}</p></div></div><div id="fatigue-chart" class="chart-wrap"></div>${chartLegend([{ label: "Player load (AU)" }, { label: "RSI-mod", className: "secondary" }])}</article><article class="panel"><div class="panel-header"><div><h2 class="panel-title">Athlete context</h2><p class="panel-subtitle">${esc(athlete.fullName)} · ${esc(athlete.squadName)}</p></div></div><div class="metric-list"><div class="metric-list-row"><span>Latest ACWR</span><strong>${num(athlete.acwr, 2)}</strong></div><div class="metric-list-row"><span>Load status</span>${statusTag(athlete.status.label, athlete.status.tone)}</div><div class="metric-list-row"><span>GPS distance</span><strong>${distance(athlete.totalDistanceM)} <small>${state.distanceUnit}</small></strong></div><div class="metric-list-row"><span>Force-test days</span><strong>${num(new Set(tests.map((test) => test.day)).size)}</strong></div></div></article></div>
      <article class="panel"><div class="panel-header" style="padding-bottom:17px"><div><h2 class="panel-title">ForceDecks test results</h2><p class="panel-subtitle">Daily averages, grouped by test type</p></div>${statusTag("VALD", "neutral")}</div>${
        tests.length
          ? `<div class="table-scroll"><table class="data-table"><thead><tr><th scope="col">Test date</th><th scope="col">Test type</th><th scope="col">RSI-mod</th><th scope="col">Asymmetry (%)</th><th scope="col">Peak force (N)</th><th scope="col"><span class="sr-only">Source</span></th></tr></thead><tbody>${[
              ...tests,
            ]
              .reverse()
              .map(
                (test) =>
                  `<tr><td>${formatDate(test.day, { year: "numeric" })}</td><td>${esc(test.testType)}</td><td>${num(test.rsiModified, 3)}</td><td>${num(test.asymmetryPct, 1)}</td><td>${num(test.peakForceN)}</td><td></td></tr>`,
              )
              .join("")}</tbody></table></div>`
          : emptyState(
              "No ForceDecks tests in this period",
              "Choose another athlete or a longer date range. GPS load is still shown when available.",
              { symbol: "zap" },
            )
      }</article><p class="clinical-note">${icon("info")}These measures support a conversation with your performance team. ACWR and force-test changes do not diagnose fatigue or injury.</p>`;
    const labels = state.model.trend.map((day) => day.day);
    const loadMap = new Map(load.map((row) => [row.day, row.playerLoad]));
    const rsiMap = new Map();
    // Compare one test type, never average CMJ and unrelated protocols into one RSI.
    tests
      .filter((test) => test.testType === latest?.testType)
      .forEach((test) => {
        const list = rsiMap.get(test.day) || [];
        list.push(test.rsiModified);
        rsiMap.set(test.day, list);
      });
    chartCleanups.push(
      mountLineChart($("#fatigue-chart"), {
        title: `${athlete.fullName}: player load and RSI-mod, on separate axes`,
        labels,
        height: 210,
        series: [
          {
            label: "Player load",
            values: labels.map((day) => loadMap.get(day) || 0),
            color: "#e86532",
            fill: true,
            unit: "AU",
          },
          {
            label: "RSI-mod",
            values: labels.map((day) =>
              rsiMap.has(day)
                ? rsiMap.get(day).reduce((sum, value) => sum + value, 0) /
                  rsiMap.get(day).length
                : null,
            ),
            axis: "right",
            color: "#6b9c83",
            decimals: 3,
          },
        ],
      }),
    );
  } catch (error) {
    if (
      version === pageVersion &&
      state.route === "fatigue" &&
      $("#fatigue-content")
    )
      $("#fatigue-content").innerHTML = renderError(
        "Force-test data could not be loaded",
        error.message,
      );
  }
}

function renderSources() {
  if (state.errors.connectors) {
    $("#page-content").innerHTML = renderError(
      "Source status could not be loaded",
      state.errors.connectors,
    );
    return;
  }
  const sourceCard = ({
    vendor,
    title,
    subtitle,
    description,
    symbol,
    fields,
  }) => {
    const source = state.connectors.find((item) => item.vendor === vendor);
    return `<article class="panel source-card"><div class="source-card-head"><span class="vendor-icon${vendor === "VALD" ? " vald" : ""}">${icon(symbol)}</span><span class="vendor-label"><strong>${title}</strong><small>${subtitle}</small></span>${statusTag(source?.lastSyncedAt ? "Synced" : "Not synced", source?.lastSyncedAt ? "" : "neutral")}</div><p class="source-card-desc">${description}</p><div class="source-properties"><div class="source-property"><span>Data available</span><strong>${fields}</strong></div><div class="source-property"><span>Last successful sync</span><strong>${source?.lastSyncedAt ? formatDateTime(source.lastSyncedAt) + " UTC" : "No sync recorded"}</strong></div><div class="source-property"><span>Sync strategy</span><strong>Incremental watermark</strong></div><div class="source-property"><span>Identity resolution</span><strong>Cross-vendor athlete profiles</strong></div></div><div class="source-card-actions"><button class="text-button" type="button" data-action="source-details" data-vendor="${vendor}">Connector details${icon("arrow-up-right", "small")}</button><button class="button secondary" type="button" data-action="sync-source" data-vendor="${vendor}">${icon("refresh-cw")}${state.demo ? "Simulate sync" : "Sync now"}</button></div></article>`;
  };
  $("#page-content").innerHTML =
    `<div class="page-intro-row"><div><h2>Your integrations</h2><p>Two monitoring systems. A shared athlete identity.</p></div>${statusTag("Admin controls", "neutral", "shield")}</div><div class="source-cards">${sourceCard({ vendor: "CATAPULT", title: "Catapult", subtitle: "GPS & athlete monitoring", description: "Bring session distance, high-speed running, player load, and peak velocity into your team view.", symbol: "radio", fields: "GPS sessions & training load" })}${sourceCard({ vendor: "VALD", title: "VALD ForceDecks", subtitle: "Strength & neuromuscular testing", description: "Add force testing to the same athlete timeline, with reactive strength, asymmetry, and peak force.", symbol: "zap", fields: "Force tests & RSI-mod" })}</div><div class="integration-note">${icon("shield-check")}<div><strong>${state.demo ? "This is an interactive demo workspace." : "Credentials stay on the server."}</strong><p>${state.demo ? "Sync actions use local sample data. No vendor is contacted, and no changes affect a live athlete record." : "Vendor access and scheduling are managed in the backend configuration. A failed sync does not advance the watermark. You can also import CSV or XLSX files."}</p></div></div>`;
}
function batchStatus(batch) {
  if (batch.simulated) return statusTag("Simulated", "neutral");
  const labels = {
    COMPLETED: ["Completed", "", "check"],
    COMPLETED_WITH_SKIPS: ["Needs attention", "warning", "info"],
    FAILED: ["Failed", "danger", "info"],
    STARTED: ["Processing", "neutral", "clock"],
  };
  const [label, tone, symbol] = labels[batch.status] || [
    batch.status,
    "neutral",
    "clock",
  ];
  return statusTag(label, tone, symbol);
}
function renderHistory() {
  if (state.errors.batches) {
    $("#page-content").innerHTML = renderError(
      "Import history could not be loaded",
      state.errors.batches,
    );
    return;
  }
  const rows = state.batches;
  const written = rows.reduce(
    (sum, batch) => sum + Number(batch.rowsWritten || 0),
    0,
  );
  const skipped = rows.reduce(
    (sum, batch) => sum + Number(batch.rowsSkipped || 0),
    0,
  );
  $("#page-content").innerHTML =
    `<div class="history-overview"><div class="panel mini-stat"><span>Recent imports</span><strong>${num(rows.length)}</strong></div><div class="panel mini-stat"><span>Records written</span><strong>${num(written)}</strong></div><div class="panel mini-stat"><span>Rows quarantined</span><strong>${num(skipped)}</strong></div></div><article class="panel"><div class="panel-header" style="align-items:center;padding-bottom:18px"><div><h2 class="panel-title">Import activity</h2><p class="panel-subtitle">Up to 100 recent files and connector syncs</p></div><div class="history-filter"><label class="sr-only" for="history-status">Filter import status</label><select id="history-status"><option value="all">All statuses</option><option value="completed">Completed</option><option value="attention">Needs attention</option><option value="failed">Failed</option></select></div></div><div id="history-table"></div></article>`;
  $("#history-status").value = state.historyFilter;
  updateHistoryTable();
}
function updateHistoryTable() {
  const filters = {
    all: () => true,
    completed: (batch) => batch.status === "COMPLETED",
    attention: (batch) => batch.status === "COMPLETED_WITH_SKIPS",
    failed: (batch) => batch.status === "FAILED",
  };
  const rows = state.batches.filter(
    filters[state.historyFilter] || filters.all,
  );
  $("#history-table").innerHTML = rows.length
    ? `<div class="table-scroll"><table class="data-table"><thead><tr><th scope="col">Import</th><th scope="col">Source</th><th scope="col">Status</th><th scope="col">Written / Read</th><th scope="col">Skipped</th><th scope="col">Started (UTC)</th><th scope="col"><span class="sr-only">View details</span></th></tr></thead><tbody>${rows
        .map((batch) => {
          const vendor =
            batch.sourceSystem === "CATAPULT"
              ? "Catapult"
              : batch.sourceSystem === "VALD"
                ? "VALD"
                : batch.sourceSystem;
          const name = batch.fileName || `${vendor} connector sync`;
          return `<tr><td><button type="button" class="athlete-link history-file" data-action="batch-detail" data-id="${esc(batch.id)}"><span class="file-icon">${icon(batch.batchType === "CONNECTOR" ? "refresh-cw" : "file-spreadsheet")}</span><span><strong>${esc(name)}</strong><small>${batch.simulated ? "Local demo preview" : esc(batch.triggeredBy || "System import")}</small></span></button></td><td class="history-vendor">${esc(vendor)}</td><td>${batchStatus(batch)}</td><td>${num(batch.rowsWritten)} <span class="muted">/ ${num(batch.rowsRead)}</span></td><td>${num(batch.rowsSkipped)}</td><td>${formatDateTime(batch.startedAt)}</td><td><button type="button" class="row-arrow" data-action="batch-detail" data-id="${esc(batch.id)}" aria-label="View ${esc(name)} details">${icon("chevron-right")}</button></td></tr>`;
        })
        .join(
          "",
        )}</tbody></table></div><div class="table-foot"><span>${rows.length} recent imports shown</span><span>Every real import has an audit trail</span></div>`
    : emptyState(
        "No imports in this view",
        "Choose another status or import a training-data file.",
        {
          symbol: "file-clock",
          action: canImport()
            ? '<button class="button secondary" type="button" data-action="open-import">Import data</button>'
            : "",
        },
      );
}
function identityPayload(item) {
  try {
    return JSON.parse(item.payload || "{}");
  } catch {
    return {};
  }
}
function renderReview() {
  if (state.errors.quarantine) {
    $("#page-content").innerHTML = renderError(
      "The identity queue could not be loaded",
      state.errors.quarantine,
    );
    return;
  }
  const rows = state.quarantine.filter((item) => item.status === "PENDING");
  $("#page-content").innerHTML =
    `<div class="review-summary"><div><h2>${rows.length ? `${rows.length} ${rows.length === 1 ? "identity needs" : "identities need"} your review` : "You’re all caught up"}</h2><p>Suggested matches stay separate until an administrator confirms them.</p></div>${statusTag("Admin review", "neutral", "shield")}</div>${
      rows.length
        ? `<div class="review-list">${rows
            .map((item) => {
              const payload = identityPayload(item);
              const candidate = state.athletes.find(
                (athlete) => athlete.id === item.candidateAthleteId,
              );
              const candidateName =
                candidate?.fullName ||
                payload.candidateName ||
                "Choose an athlete";
              return `<article class="panel review-card"><div class="review-top"><div class="review-source"><span class="vendor-icon${item.sourceSystem === "VALD" ? " vald" : ""}">${icon(item.sourceSystem === "VALD" ? "zap" : "radio")}</span><span>${esc(item.sourceSystem)} · ${esc(item.sourceAthleteId)}</span></div>${statusTag("Awaiting review", "warning", "clock")}</div><div class="review-identities"><div class="identity-block"><span>Incoming identity</span><strong>${esc(payload.fullName || payload.name || item.sourceAthleteId)}</strong><p>${payload.dob ? `Date of birth · ${esc(payload.dob)}` : "Date of birth not provided"}</p></div><span class="review-link-icon">${icon("link-2")}</span><div class="identity-block"><span>Suggested athlete profile</span><strong>${esc(candidateName)}</strong><p>${esc(candidate?.squadName || payload.candidateDob || "No automatic match was confirmed")}</p></div></div><div class="review-bottom"><div class="confidence">${icon("info", "small")}Match score <strong>${num(item.score, 3)}</strong><span>· ${relativeTime(item.createdAt)}</span></div><div class="review-actions"><button type="button" class="button secondary" data-action="review-decision" data-id="${esc(item.id)}" data-decision="reject">Keep separate</button><button type="button" class="button primary" data-action="review-decision" data-id="${esc(item.id)}" data-decision="confirm">${icon("link-2")}Review match</button></div></div></article>`;
            })
            .join("")}</div>`
        : `<article class="panel">${emptyState("Every identity has a home", "There are no athlete identities awaiting review. New ambiguous matches will appear here.", { symbol: "check-check", success: true })}</article>`
    }`;
}

function showImport() {
  if (!canImport()) {
    toast("Your role does not have permission to import data.", true);
    return;
  }
  if (!state.profiles.length) {
    openDialog(
      `${dialogHeader("Import training data")}<div class="dialog-body">${emptyState("Mapping profiles are unavailable", state.errors.profiles || "An administrator needs to configure a column-mapping profile before you can import data.", { symbol: "file-spreadsheet", action: '<button class="button secondary" type="button" data-action="refresh-and-close">Refresh profiles</button>' })}</div>`,
    );
    return;
  }
  importFile = null;
  importRows = 0;
  importValidating = false;
  const squads = state.squads.length
    ? state.squads
    : [
        ...new Map(
          state.athletes.map((athlete) => [
            athlete.squadId,
            { id: athlete.squadId, name: athlete.squadName },
          ]),
        ).values(),
      ];
  openDialog(
    `<form id="file-import-form">${dialogHeader("Import training data", "Connect a vendor export to the right athlete profiles.")}<div class="dialog-body"><span class="dialog-label">1. Choose your data source</span><div class="source-options"><button type="button" class="source-option active" data-action="import-source" data-vendor="CATAPULT" aria-pressed="true"><span class="vendor-icon">${icon("radio")}</span><span><strong>Catapult</strong><small>GPS & training load</small></span><span class="radio-mark"></span></button><button type="button" class="source-option" data-action="import-source" data-vendor="VALD" aria-pressed="false"><span class="vendor-icon vald">${icon("zap")}</span><span><strong>VALD ForceDecks</strong><small>Strength & force testing</small></span><span class="radio-mark"></span></button></div><div class="field"><label for="mapping-profile">Column-mapping profile</label><select id="mapping-profile" required>${state.profiles.map((profile) => `<option value="${esc(profile.name)}">${esc(profile.name)} · ${esc(profile.sourceSystem)}</option>`).join("")}</select><p class="field-help">Choose the profile that matches your file’s columns.</p></div>${isAdmin() ? `<div class="field"><label for="import-squad">Destination squad</label><select id="import-squad" required><option value="">Choose a squad</option>${squads.map((squad) => `<option value="${esc(squad.id)}"${state.squad === squad.id ? " selected" : ""}>${esc(squad.name)}</option>`).join("")}</select><p class="field-help">${squads.length ? "Every imported row is scoped to this squad." : "No squads are available. Create a squad in the backend before importing."}</p></div>` : '<p class="dialog-description">Your account’s squad scope is enforced by the server.</p>'}<span class="dialog-label" style="margin-top:22px">2. Add your export file</span><label class="drop-zone" id="drop-zone"><input id="import-file" type="file" accept=".csv,.xlsx" aria-label="Choose a CSV or XLSX file"><span class="drop-zone-icon">${icon("cloud-upload")}</span><strong><span>Click to upload</span> or drag and drop</strong><small>${state.demo ? "CSV up to 10 MB for the local demo" : "CSV or XLSX · Up to 150 MB"}</small></label><div id="selected-file" hidden></div><p class="field-error" id="import-error" role="alert" hidden></p>${state.demo ? `<p class="import-demo-notice">${icon("info")}Demo mode: your CSV stays in this browser. Imports are simulated; no live athlete data is created.</p>` : `<p class="import-demo-notice">${icon("shield-check")}Invalid rows are quarantined for review. A corrupt row does not stop the rest of your file.</p>`}</div><div class="dialog-footer"><button class="button secondary" type="button" data-action="close-dialog">Cancel</button><button class="button primary" id="submit-import" type="submit" disabled>${icon("upload")}${state.demo ? "Simulate import" : "Upload & ingest"}</button></div></form>`,
    "wide",
  );
  if ($("#import-squad") && !$("#import-squad").value && squads.length === 1)
    $("#import-squad").value = squads[0].id;
  syncImportSource();
  const drop = $("#drop-zone");
  drop.addEventListener("dragover", (event) => {
    event.preventDefault();
    drop.classList.add("dragging");
  });
  drop.addEventListener("dragleave", () => drop.classList.remove("dragging"));
  drop.addEventListener("drop", (event) => {
    event.preventDefault();
    drop.classList.remove("dragging");
    if (event.dataTransfer.files.length !== 1)
      setImportError("Please select one file at a time.");
    else selectImportFile(event.dataTransfer.files[0]);
  });
}
function syncImportSource() {
  const profile = state.profiles.find(
    (value) => value.name === $("#mapping-profile")?.value,
  );
  $$(".source-option", $("#app-dialog")).forEach((button) => {
    const active = button.dataset.vendor === profile?.sourceSystem;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
}
function setImportError(message) {
  if (!$("#import-error")) return;
  $("#import-error").textContent = message;
  $("#import-error").hidden = !message;
}
function updateImportButton() {
  if ($("#submit-import"))
    $("#submit-import").disabled =
      !importFile ||
      importValidating ||
      (isAdmin() && !$("#import-squad")?.value);
}
async function selectImportFile(file) {
  const selection = ++importSelectionVersion;
  importFile = null;
  importRows = 0;
  importValidating = false;
  setImportError("");
  $("#selected-file").hidden = true;
  updateImportButton();
  if (!file) return;
  if (!/\.(csv|xlsx)$/i.test(file.name)) {
    setImportError(
      "Choose a .csv or .xlsx file. Other file types are not supported.",
    );
    return;
  }
  if (!file.size) {
    setImportError(
      "This file is empty. Choose an export that contains training data.",
    );
    return;
  }
  const limit = (state.demo ? 10 : 150) * 1024 * 1024;
  if (file.size > limit) {
    setImportError(
      `The maximum file size ${state.demo ? "in demo mode is 10" : "is 150"} MB.`,
    );
    return;
  }
  if (state.demo && /\.xlsx$/i.test(file.name)) {
    setImportError(
      "Local demo previews support CSV files. Sign in to ingest XLSX using the backend.",
    );
    return;
  }
  const version = dialogVersion;
  importValidating = true;
  try {
    if (state.demo) {
      const rows = parseCsv(await file.text());
      if (rows.length < 2)
        throw new Error(
          "The CSV needs a header row and at least one data row.",
        );
      importRows = rows.length - 1;
    }
    if (version !== dialogVersion || selection !== importSelectionVersion)
      return;
    importFile = file;
    $("#selected-file").className = "selected-file";
    $("#selected-file").innerHTML =
      `${icon("file-check-2")}<span class="selected-file-copy"><strong>${esc(file.name)}</strong><small>${file.size < 1_048_576 ? num(file.size / 1024, 1) + " KB" : num(file.size / 1_048_576, 1) + " MB"}${state.demo ? ` · ${num(importRows)} data rows for preview` : " · Ready to upload"}</small></span><button class="icon-button" type="button" data-action="clear-import-file" aria-label="Remove selected file">${icon("x")}</button>`;
    $("#selected-file").hidden = false;
  } catch (error) {
    if (version === dialogVersion && selection === importSelectionVersion)
      setImportError(error.message);
  } finally {
    if (version === dialogVersion && selection === importSelectionVersion) {
      importValidating = false;
      updateImportButton();
    }
  }
}
async function submitImport(event) {
  event.preventDefault();
  if (!canImport() || !importFile || importValidating) return;
  setImportError("");
  const button = $("#submit-import"),
    version = dialogVersion;
  let accepted = false;
  const body = new FormData();
  body.append("file", importFile);
  body.append("profile", $("#mapping-profile").value);
  if (isAdmin()) {
    if (!$("#import-squad").value) {
      setImportError("Choose a destination squad.");
      return;
    }
    body.append("squadId", $("#import-squad").value);
  }
  button.disabled = true;
  button.textContent = state.demo ? "Preparing preview…" : "Starting import…";
  try {
    const result = await state.client.request("/api/ingest/files", {
      method: "POST",
      body,
      demoRows: importRows,
    });
    accepted = true;
    if (version !== dialogVersion || !state.me) return;
    openDialog(
      `${dialogHeader(state.demo ? "Demo import complete" : "Your import has started")}<div class="dialog-body"><div class="form-success"><span class="success-mark">${icon("check")}</span><h3>${state.demo ? "Your file preview is ready." : "The pipeline is on it."}</h3><p>${state.demo ? "A simulated batch was added to this browser session. No live data was ingested." : "Your file was accepted. Watch the import history for progress, written rows, and quarantined errors."}</p><p class="mono">Batch ${esc(result.batchId)}</p><button class="button primary" type="button" data-action="import-done">View import history${icon("arrow-right")}</button></div></div>`,
    );
    // Refresh lineage, not unrelated per-athlete histories, while the new job may still be running.
    const page = await state.client.request(
      "/api/ingest/batches?size=100&sort=startedAt,desc",
    );
    state.batches = page.content || [];
    delete state.errors.batches;
  } catch (error) {
    if (version === dialogVersion && $("#submit-import")) {
      setImportError(error.message);
      button.disabled = false;
      button.innerHTML = `${icon("upload")}${state.demo ? "Simulate import" : "Upload & ingest"}`;
    } else if (state.me)
      toast(
        accepted
          ? "The import was accepted, but history could not be refreshed. Open Import history and refresh."
          : "The import could not be confirmed. Check import history before trying again.",
        true,
      );
  }
}
function showExport() {
  if (!canExport()) {
    toast("Your role cannot export athlete data.", true);
    return;
  }
  openDialog(
    `${dialogHeader("Export athlete data", "Download the roster your account is allowed to access.")}<div class="dialog-body"><div class="export-option"><span class="file-icon">${icon("file-spreadsheet")}</span><div><strong>Athlete roster · CSV</strong><p>Canonical athlete profiles with name, date of birth, email, gender, provisional status, and squad.</p><small>${state.demo ? "Synthetic demo data only" : "Server-authorized export · Recorded in the audit log"}</small></div></div><p class="dialog-description" style="margin:17px 0 0">Includes all athletes your role can access. The squad and date filters on this page do not change this roster export.</p><p class="field-error" id="export-error" role="alert" hidden></p></div><div class="dialog-footer"><button type="button" class="button secondary" data-action="close-dialog">Cancel</button><button type="button" class="button primary" id="download-roster" data-action="download-roster">${icon("download")}Download roster CSV</button></div>`,
  );
}
async function downloadRoster(button) {
  if (!canExport()) return;
  const version = dialogVersion;
  button.disabled = true;
  button.textContent = "Preparing download…";
  try {
    const blob = await state.client.download("/api/exports/athletes.csv");
    saveBlob(blob, state.demo ? "sdip-demo-athletes.csv" : "athletes.csv");
    if (version === dialogVersion) closeDialog();
    toast(
      state.demo
        ? "Demo roster downloaded. All athlete details are synthetic."
        : "Athlete roster downloaded. Your export was recorded in the audit log.",
    );
  } catch (error) {
    if (version === dialogVersion && $("#export-error")) {
      $("#export-error").textContent = error.message;
      $("#export-error").hidden = false;
      button.disabled = false;
      button.innerHTML = `${icon("download")}Download roster CSV`;
    }
  }
}

function openAthlete(id) {
  const athlete =
    state.model?.roster.find((value) => value.id === id) ||
    buildWorkspace(
      state.athletes,
      state.teamRows,
      state.loads,
      state.days,
      "all",
      state.today,
    ).roster.find((value) => value.id === id);
  if (!athlete) {
    toast("This athlete is not available in your current scope.", true);
    return;
  }
  const series = athlete.series.filter(
    (record) => record.day >= shiftDay(state.today, -41),
  );
  openDialog(
    `<div class="dialog-header"><div class="athlete-profile-head">${avatar(athlete.fullName)}<div><h2 id="dialog-title">${esc(athlete.fullName)}</h2><p>${esc(athlete.squadName)}${athlete.position ? ` · ${esc(athlete.position)}` : ""}</p></div></div><button type="button" class="icon-button" data-action="close-dialog" aria-label="Close athlete profile">${icon("x")}</button></div><div class="dialog-body"><div class="drawer-stat-grid"><div class="drawer-stat"><span>Player load · ${state.days} days</span><strong>${num(athlete.playerLoad)} <small>AU</small></strong></div><div class="drawer-stat"><span>Distance · ${state.days} days</span><strong>${distance(athlete.totalDistanceM)} <small>${state.distanceUnit}</small></strong></div><div class="drawer-stat"><span>Latest ACWR</span><strong>${num(athlete.acwr, 2)}</strong></div></div><div class="drawer-chart-title"><h3>Player load timeline</h3><span>Last 42 days · AU</span></div><div id="athlete-drawer-chart" class="chart-wrap"></div>${chartLegend([{ label: "Daily player load" }])}<h3 class="drawer-section-title">Athlete profile</h3><dl class="athlete-detail-grid"><div><dt>Canonical name</dt><dd>${esc(athlete.fullName)}</dd></div><div><dt>Squad</dt><dd>${esc(athlete.squadName)}</dd></div><div><dt>Profile status</dt><dd>${athlete.provisional ? "Provisional" : "Canonical profile"}</dd></div><div><dt>Last GPS activity</dt><dd>${formatDate(athlete.lastActivity)}</dd></div><div><dt>Load status</dt><dd>${statusTag(athlete.status.label, athlete.status.tone)}</dd></div><div><dt>Sessions in period</dt><dd>${num(athlete.sessions)}</dd></div></dl><button type="button" class="button secondary full" style="margin-top:19px;font-size:11px" data-action="athlete-fatigue" data-id="${esc(athlete.id)}">${icon("heart-pulse")}Explore load & fatigue${icon("arrow-right")}</button><p class="clinical-note">${icon("shield-check")}${state.demo ? "This is a synthetic athlete profile in an isolated demo workspace." : "Your account’s squad scope is enforced by the server. Training-load flags are not a medical assessment."}</p></div>`,
    "drawer",
  );
  dialogChartCleanups.push(
    mountLineChart($("#athlete-drawer-chart"), {
      title: `${athlete.fullName}, daily player load over 42 days`,
      labels: series.map((record) => record.day),
      height: 200,
      series: [
        {
          label: "Player load",
          values: series.map((record) => record.playerLoad),
          color: "#e86532",
          fill: true,
          unit: "AU",
        },
      ],
    }),
  );
}
async function openBatch(id) {
  const version = openDialog(
    `${dialogHeader("Import details")}<div class="dialog-body">${loadingPanel("Loading batch lineage…")}</div>`,
    "wide",
  );
  try {
    const batch = await state.client.request(
      `/api/ingest/batches/${encodeURIComponent(id)}`,
    );
    let errors = [],
      errorMessage = "";
    if (canImport()) {
      try {
        errors = await state.client.request(
          `/api/ingest/batches/${encodeURIComponent(id)}/errors`,
        );
      } catch (error) {
        errorMessage = error.message;
      }
    }
    if (version !== dialogVersion || !state.me) return;
    $("#dialog-content").innerHTML =
      `${dialogHeader(batch.fileName || `${batch.sourceSystem} connector sync`, "Import lineage and quarantined rows.")}<div class="dialog-body">${batchStatus(batch)}<div class="batch-detail-grid"><div><span>Rows read</span><strong>${num(batch.rowsRead)}</strong></div><div><span>Rows written</span><strong>${num(batch.rowsWritten)}</strong></div><div><span>Rows skipped</span><strong>${num(batch.rowsSkipped)}</strong></div></div><dl class="athlete-detail-grid"><div><dt>Source system</dt><dd>${esc(batch.sourceSystem)}</dd></div><div><dt>Import type</dt><dd>${esc(batch.batchType)}</dd></div><div><dt>Started (UTC)</dt><dd>${formatDateTime(batch.startedAt)}</dd></div><div><dt>Duration</dt><dd>${batch.durationMs != null ? num(batch.durationMs / 1000, 2) + " s" : "Still running"}</dd></div><div><dt>Triggered by</dt><dd>${esc(batch.triggeredBy || "System")}</dd></div><div><dt>Batch ID</dt><dd class="mono">${esc(batch.id)}</dd></div></dl>${batch.simulated ? '<p class="import-demo-notice">This batch was simulated in the browser. Its rows were not validated or ingested by the backend.</p>' : ""}${errorMessage ? `<p class="field-error">Row errors could not be loaded: ${esc(errorMessage)}</p>` : errors.length ? `<h3 class="drawer-section-title">Quarantined rows</h3><div class="batch-error-list">${errors.map((error) => `<div class="batch-error-row"><strong>Row ${num(error.rowNumber)} · ${esc(error.errorCode || error.reason || "Validation error")}</strong>${esc(error.message || error.errorMessage || error.detail || "Check this row against the selected mapping profile.")}</div>`).join("")}</div>` : `<p class="dialog-description" style="margin:18px 0 0">${canImport() ? (batch.status === "STARTED" ? "This import is still running. Refresh the history to check its progress." : "No quarantined row errors were returned for this batch.") : "Row-level error details are restricted to staff roles."}</p>`}${batch.errorSummary ? `<p class="field-error">${esc(batch.errorSummary)}</p>` : ""}</div><div class="dialog-footer"><button type="button" class="button secondary" data-action="close-dialog">Close details</button></div>`;
  } catch (error) {
    if (version === dialogVersion)
      $("#dialog-content").innerHTML =
        `${dialogHeader("Import details unavailable")}<div class="dialog-body"><p class="field-error">${esc(error.message)}</p></div>`;
  }
}

function showReviewDecision(id, decision) {
  const item = state.quarantine.find(
    (value) => value.id === id && value.status === "PENDING",
  );
  if (!item || !isAdmin()) return;
  const payload = identityPayload(item),
    confirming = decision === "confirm";
  const candidate = state.athletes.find(
    (athlete) => athlete.id === item.candidateAthleteId,
  );
  openDialog(
    `<form id="review-form" data-id="${esc(id)}" data-decision="${decision}">${dialogHeader(confirming ? "Review this athlete match" : "Keep these identities separate", confirming ? "Confirm only when the incoming identity belongs to this athlete." : "Rejecting the suggestion does not delete the athlete profile.")}<div class="dialog-body"><div class="identity-block" style="margin-bottom:20px"><span>${esc(item.sourceSystem)} · Incoming identity</span><strong>${esc(payload.fullName || item.sourceAthleteId)}</strong><p>${payload.dob ? `Date of birth · ${esc(payload.dob)}` : "Date of birth not supplied"}</p></div>${confirming ? `<div class="field"><label for="review-athlete">Link to canonical athlete</label><select id="review-athlete" required><option value="">Select an athlete profile</option>${state.athletes.map((athlete) => `<option value="${esc(athlete.id)}"${athlete.id === candidate?.id ? " selected" : ""}>${esc(athlete.fullName)} · ${esc(athlete.squadName)}</option>`).join("")}</select></div><p class="dialog-description">Links are reversible. This action connects the vendor identity to the selected profile; it does not destructively merge athletes.</p>` : '<p class="dialog-description">The incoming identity remains unlinked. Any future data from this identity may need another review.</p>'}<div class="field"><label for="review-note">Review note <span class="muted" style="font-weight:400">(optional)</span></label><textarea id="review-note" maxlength="1000" placeholder="Record the context behind this decision…"></textarea></div><p id="review-error" class="field-error" role="alert" hidden></p>${state.demo ? '<p class="import-demo-notice">Demo decisions only change the sample queue in this browser session.</p>' : ""}</div><div class="dialog-footer"><button type="button" class="button secondary" data-action="close-dialog">Cancel</button><button type="submit" id="submit-review" class="button ${confirming ? "primary" : "danger"}">${icon(confirming ? "link-2" : "x")}${confirming ? "Confirm athlete link" : "Keep separate"}</button></div></form>`,
  );
}
async function submitReview(event) {
  event.preventDefault();
  if (!isAdmin()) return;
  const form = event.target,
    id = form.dataset.id,
    decision = form.dataset.decision;
  const version = dialogVersion,
    button = $("#submit-review");
  button.disabled = true;
  const json = { note: $("#review-note").value.trim() || null };
  if (decision === "confirm") json.athleteId = $("#review-athlete").value;
  try {
    await state.client.request(
      `/api/links/quarantine/${encodeURIComponent(id)}/${decision}`,
      { method: "POST", json },
    );
    state.quarantine = state.quarantine.filter((item) => item.id !== id);
    updateReviewBadge();
    if (version === dialogVersion) closeDialog();
    if (state.route === "review") renderPage();
    toast(
      state.demo
        ? `Demo identity ${decision === "confirm" ? "linked" : "kept separate"}. No live records were changed.`
        : decision === "confirm"
          ? "Athlete identity linked. The decision was recorded in the audit log."
          : "Identities kept separate. The decision was recorded in the audit log.",
    );
  } catch (error) {
    if (version === dialogVersion && $("#review-error")) {
      $("#review-error").textContent = error.message;
      $("#review-error").hidden = false;
      button.disabled = false;
    }
  }
}

function showHelp() {
  openDialog(
    `${dialogHeader("A little context goes a long way", "Your guide to the SDIP performance workspace.")}<div class="dialog-body"><div class="help-sections"><div class="help-section"><span class="help-symbol">${icon("activity")}</span><div><h3>Training load, in one view</h3><p>Catapult sessions supply player load, distance, high-speed running, and velocity. Summary totals follow the API’s rolling time window; charts group activity into UTC days.</p></div></div><div class="help-section"><span class="help-symbol">${icon("heart-pulse")}</span><div><h3>Look at the signals together</h3><p>ACWR compares the latest 7-day mean with the preceding 21-day mean. ForceDecks adds RSI-mod, peak force, and asymmetry. Flags provide context, not a diagnosis.</p></div></div><div class="help-section"><span class="help-symbol">${icon("link-2")}</span><div><h3>A person makes the final call</h3><p>Ambiguous athlete identities wait in the administrator’s review queue. Confirmed links can be reversed; athlete profiles are never destructively merged.</p></div></div><div class="help-section"><span class="help-symbol">${icon("shield-check")}</span><div><h3>Your role sets your view</h3><p>Coaches and practitioners work within their squad. Analysts have read-only access. Identity decisions and connector controls are administrator-only.</p></div></div><div class="help-shortcuts"><kbd>${/Mac|iPhone|iPad/.test(navigator.platform) ? "⌘" : "Ctrl"} K</kbd><span>Search the workspace</span><span class="spacer"></span><kbd>Esc</kbd><span>Close a dialog</span></div></div></div><div class="dialog-footer"><a class="button secondary" href="https://github.com/Sehaan-1/sports-data-integration-platform#readme" target="_blank" rel="noopener noreferrer">Read the documentation${icon("arrow-up-right")}</a>${state.demo ? '<button class="button primary" type="button" data-action="close-dialog">Back to workspace</button>' : '<a class="button primary" href="/swagger-ui.html" target="_blank" rel="noopener">Open API reference</a>'}</div>`,
    "wide",
  );
}
function showWorkloadInfo() {
  openDialog(
    `${dialogHeader("Reading your workload data")}<div class="dialog-body"><div class="help-sections"><div class="help-section"><span class="help-symbol">${icon("chart-line")}</span><div><h3>Daily load and the previous period</h3><p>The orange line shows the selected squad’s player load or distance. The dashed line aligns the preceding period by day. Days without GPS records show zero recorded load; missing ForceDecks tests remain gaps.</p></div></div><div class="help-section"><span class="help-symbol">${icon("clock")}</span><div><h3>Rolling totals, UTC days</h3><p>Summary totals come from the API’s rolling time window. Daily charts use calendar-day aggregates in UTC. A partial day at the rolling window boundary can make their totals differ.</p></div></div><div class="help-section"><span class="help-symbol">${icon("info")}</span><div><h3>ACWR is context</h3><p>Values from 0.8 to 1.3 are labeled balanced, above 1.3 elevated, and above 1.5 high load. Below 0.8 is low load. No baseline means the chronic mean is unavailable. These descriptive flags do not predict injury.</p></div></div></div></div><div class="dialog-footer"><button class="button primary" type="button" data-action="close-dialog">Back to the data</button></div>`,
  );
}
function showSourceDetails(vendor) {
  const name = vendor === "VALD" ? "VALD ForceDecks" : "Catapult";
  openDialog(
    `${dialogHeader(`${name} connector`, "Incremental sync, with a reliable data trail.")}<div class="dialog-body"><div class="help-sections"><div class="help-section"><span class="help-symbol">${icon("refresh-cw")}</span><div><h3>A watermark tracks progress</h3><p>Each successful sync moves the watermark forward. An overlap window catches late-arriving records. A failed sync leaves the watermark unchanged, so data can be retried safely.</p></div></div><div class="help-section"><span class="help-symbol">${icon("shield-check")}</span><div><h3>Server-managed credentials</h3><p>Vendor credentials and optional automatic scheduling are configured on the backend. The browser never asks for or stores a vendor API key.</p></div></div><div class="help-section"><span class="help-symbol">${icon("file-spreadsheet")}</span><div><h3>Files are an option, too</h3><p>Staff can import CSV or XLSX exports with a column-mapping profile. Row errors are quarantined, and every real import keeps its lineage.</p></div></div></div>${state.demo ? '<p class="import-demo-notice">This preview uses synthetic data. “Simulate sync” does not contact a vendor.</p>' : ""}</div><div class="dialog-footer"><button class="button secondary" type="button" data-action="close-dialog">Close details</button>${canImport() ? '<button class="button primary" type="button" data-action="open-import">Import a file</button>' : ""}</div>`,
  );
}
async function syncSource(button) {
  if (!isAdmin()) return;
  const vendor = button.dataset.vendor;
  button.disabled = true;
  button.innerHTML = `${icon("refresh-cw")}Syncing…`;
  try {
    const result = await state.client.request(
      `/api/connectors/${encodeURIComponent(vendor)}/sync`,
      { method: "POST" },
    );
    if (!state.me) return;
    if (result.status === "DISABLED")
      toast(
        `${vendor} is disabled in the backend configuration. Enable the connector on the server before syncing.`,
        true,
      );
    else if (result.status === "DEGRADED")
      toast(
        `${vendor} could not complete the sync. Its watermark has not advanced. Check the backend connector logs.`,
        true,
      );
    else
      toast(
        state.demo
          ? "Demo sync completed. No vendor was contacted."
          : `${vendor} sync completed: ${num(result.recordsWritten)} records written, ${num(result.recordsQuarantined)} quarantined.`,
      );
    await loadWorkspace({ force: true });
  } catch (error) {
    toast(error.message, true);
  } finally {
    if (button.isConnected) {
      button.disabled = false;
      button.innerHTML = `${icon("refresh-cw")}${state.demo ? "Simulate sync" : "Sync now"}`;
    }
  }
}
function showPreferences() {
  openDialog(
    `<form id="preferences-form">${dialogHeader("Make the workspace yours", "Display preferences are saved in this browser.")}<div class="dialog-body"><div class="preference-row"><div><strong>Distance units</strong><p>Apply to charts, metrics, and athlete tables.</p></div><label class="sr-only" for="preference-distance">Distance units</label><select id="preference-distance"><option value="km"${state.distanceUnit === "km" ? " selected" : ""}>Kilometers (km)</option><option value="m"${state.distanceUnit === "m" ? " selected" : ""}>Meters (m)</option></select></div><div class="preference-row"><div><strong>Table density</strong><p>Choose a little more space, or see more rows.</p></div><label class="sr-only" for="preference-density">Table density</label><select id="preference-density"><option value="comfortable"${!state.compact ? " selected" : ""}>Comfortable</option><option value="compact"${state.compact ? " selected" : ""}>Compact</option></select></div></div><div class="dialog-footer"><button class="button secondary" type="button" data-action="close-dialog">Cancel</button><button class="button primary" type="submit">Save preferences</button></div></form>`,
  );
}
function showWorkspace() {
  const squads =
    isAdmin() && state.squads.length
      ? state.squads
      : [
          ...new Map(
            state.athletes.map((athlete) => [
              athlete.squadId,
              { id: athlete.squadId, name: athlete.squadName },
            ]),
          ).values(),
        ];
  openDialog(
    `${dialogHeader("Your performance workspace")}<div class="dialog-body"><div class="workspace-info"><span class="workspace-monogram">S<span></span></span><div><h3>SDIP workspace</h3><p>${state.demo ? "Isolated demo · Synthetic athlete data" : esc(state.me.email)}</p></div></div><dl class="athlete-detail-grid"><div><dt>Your role</dt><dd>${esc(roleLabels[state.me.role] || state.me.role)}</dd></div><div><dt>Data access</dt><dd>${state.me.squadId ? "Assigned squad only" : "All authorized squads"}</dd></div></dl><h3 class="drawer-section-title">Jump to a squad</h3><div class="workspace-squad-list"><button class="workspace-squad-button" type="button" data-action="workspace-squad" data-id="all">${icon("users")}${isAdmin() ? "All squads" : "My squad"}<small>${state.athletes.length} athletes</small></button>${squads.map((squad) => `<button class="workspace-squad-button" type="button" data-action="workspace-squad" data-id="${esc(squad.id)}">${icon("flag")}${esc(squad.name)}<small>${state.athletes.filter((athlete) => athlete.squadId === squad.id).length} athletes</small></button>`).join("")}</div></div><div class="dialog-footer"><button class="button secondary" type="button" data-action="close-dialog">Back to workspace</button></div>`,
  );
}
function showCommand() {
  if (!state.loaded) return;
  openDialog(
    `<div class="dialog-header"><h2 id="dialog-title" class="sr-only">Search the workspace</h2><div class="command-search-wrap">${icon("search")}<label class="sr-only" for="command-search">Search pages or athletes</label><input id="command-search" type="search" placeholder="Search pages or athletes…" autocomplete="off" autofocus></div><button class="icon-button" type="button" data-action="close-dialog" aria-label="Close search">${icon("x")}</button></div><div class="command-results" id="command-results"></div><div class="dialog-footer" style="padding-top:9px;padding-bottom:9px;justify-content:space-between;color:#a2a9b3;font-size:8px"><span>↑ ↓ to navigate</span><span>Enter to open · Esc to close</span></div>`,
    "command-dialog",
  );
  updateCommandResults("");
  $("#command-search").focus();
}
function updateCommandResults(query) {
  const term = query.trim().toLocaleLowerCase();
  const matches = Object.entries(pages).filter(
    ([, page]) =>
      (!page.admin || isAdmin()) &&
      `${page.name} ${page.title}`.toLocaleLowerCase().includes(term),
  );
  const athletes = state.athletes
    .filter((athlete) =>
      `${athlete.fullName} ${athlete.squadName}`
        .toLocaleLowerCase()
        .includes(term),
    )
    .slice(0, term ? 8 : 4);
  $("#command-results").innerHTML =
    `${matches.length ? `<p class="command-group-label">WORKSPACE</p>${matches.map(([route, page]) => `<button class="command-result" type="button" data-action="command-navigate" data-page="${route}">${icon(page.icon)}${esc(page.name)}${icon("arrow-up-right", "small")}</button>`).join("")}` : ""}${athletes.length ? `<p class="command-group-label">ATHLETE PROFILES</p>${athletes.map((athlete) => `<button class="command-result" type="button" data-action="open-athlete" data-id="${esc(athlete.id)}">${icon("circle-user-round")}${esc(athlete.fullName)}<small>${esc(athlete.squadName)}</small></button>`).join("")}` : ""}${!matches.length && !athletes.length ? '<p class="command-empty">No pages or athletes match that search.</p>' : ""}`;
}
function showActivity() {
  const willOpen = $("#activity-popover").hidden;
  closePopovers();
  if (!willOpen) return;
  const queue = state.quarantine.filter((item) => item.status === "PENDING");
  $("#activity-popover").innerHTML =
    `<div class="popover-heading">Workspace activity<button class="icon-button" type="button" data-action="close-popovers" aria-label="Close activity">${icon("x")}</button></div>${isAdmin() && queue.length ? `<button type="button" class="activity-item" data-action="navigate" data-page="review"><span class="activity-symbol">${icon("link-2")}</span><span class="activity-copy"><strong>${queue.length} ${queue.length === 1 ? "identity needs" : "identities need"} a second look</strong><p>Review suggested athlete matches.</p><small>Awaiting administrator review</small></span></button>` : ""}${state.batches
      .slice(0, 3)
      .map(
        (batch) =>
          `<button type="button" class="activity-item" data-action="batch-detail" data-id="${esc(batch.id)}"><span class="activity-symbol">${icon(batch.status === "COMPLETED" ? "check-circle-2" : "file-clock")}</span><span class="activity-copy"><strong>${esc(batch.sourceSystem)} ${batch.simulated ? "demo preview" : "import"}</strong><p>${batch.simulated ? "Simulated in this browser" : `${num(batch.rowsWritten)} rows written · ${num(batch.rowsSkipped)} skipped`}</p><small>${relativeTime(batch.startedAt)}</small></span></button>`,
      )
      .join(
        "",
      )}${!queue.length && !state.batches.length ? '<div class="command-empty">No activity is available in this view yet.</div>' : ""}<button type="button" class="activity-foot" data-action="navigate" data-page="history">View import history →</button>`;
  $("#activity-popover").hidden = false;
  $("#activity-button").setAttribute("aria-expanded", "true");
}
function selectSquad(id) {
  state.squad = id;
  state.model = buildWorkspace(
    state.athletes,
    state.teamRows,
    state.loads,
    state.days,
    state.squad,
    state.today,
  );
  $("#squad-select").value = id;
  renderPage();
}

const mobileMedia = matchMedia("(max-width: 900px)");
function updateMobileNav() {
  const open = document.body.classList.contains("nav-open");
  $("#sidebar").inert = mobileMedia.matches && !open;
  if (!mobileMedia.matches) {
    document.body.classList.remove("nav-open");
    $("#sidebar").removeAttribute("role");
    $("#sidebar").removeAttribute("aria-modal");
    $("#open-mobile-nav").setAttribute("aria-expanded", "false");
  }
}
function openMobileNav() {
  document.body.classList.add("nav-open");
  $("#sidebar").inert = false;
  $("#sidebar").setAttribute("role", "dialog");
  $("#sidebar").setAttribute("aria-modal", "true");
  $("#open-mobile-nav").setAttribute("aria-expanded", "true");
  $('.nav-item[aria-current="page"]')?.focus();
}
function closeMobileNav() {
  const wasOpen = document.body.classList.contains("nav-open");
  document.body.classList.remove("nav-open");
  $("#sidebar").removeAttribute("role");
  $("#sidebar").removeAttribute("aria-modal");
  $("#open-mobile-nav").setAttribute("aria-expanded", "false");
  updateMobileNav();
  if (wasOpen && mobileMedia.matches) $("#open-mobile-nav").focus();
}

const rosterSearch = debounce((value) => {
  state.rosterQuery = value;
  updateRosterTable();
}, 100);
const commandSearch = debounce((value) => {
  if ($("#command-results")) updateCommandResults(value);
}, 70);

// Delegation keeps controls working after a page or table is re-rendered.
document.addEventListener("click", (event) => {
  const control = event.target.closest("[data-action]");
  if (control && !control.disabled) {
    const { action, id } = control.dataset;
    const actions = {
      navigate: () => navigate(control.dataset.page),
      "command-navigate": () => {
        closeDialog();
        navigate(control.dataset.page);
      },
      "close-dialog": closeDialog,
      "close-popovers": closePopovers,
      "dismiss-toast": () => {
        clearTimeout(toastTimer);
        $("#toast-region").innerHTML = "";
      },
      refresh: () => loadWorkspace({ force: true }),
      "refresh-and-close": () => {
        closeDialog();
        return loadWorkspace({ force: true });
      },
      "open-import": showImport,
      "open-athlete": () => openAthlete(id),
      "batch-detail": () => openBatch(id),
      help: showHelp,
      "workload-info": showWorkloadInfo,
      "chart-metric": () => {
        state.chartMetric = control.dataset.metric;
        clearCharts();
        renderOverview();
        $(
          `[data-action="chart-metric"][data-metric="${state.chartMetric}"]`,
        )?.focus({ preventScroll: true });
      },
      "roster-status": () => {
        state.rosterStatus = control.dataset.status;
        renderAthletes();
        $(
          `[data-action="roster-status"][data-status="${state.rosterStatus}"]`,
        )?.focus({ preventScroll: true });
      },
      "sort-roster": () => {
        state.rosterSort = control.dataset.sort;
        updateRosterTable();
      },
      "import-source": () => {
        const profile = state.profiles.find(
          (value) => value.sourceSystem === control.dataset.vendor,
        );
        if (profile) {
          $("#mapping-profile").value = profile.name;
          syncImportSource();
        }
      },
      "clear-import-file": () => {
        importSelectionVersion++;
        importFile = null;
        importRows = 0;
        importValidating = false;
        $("#selected-file").hidden = true;
        $("#import-file").value = "";
        setImportError("");
        updateImportButton();
      },
      "import-done": () => {
        closeDialog();
        navigate("history");
      },
      "download-roster": () => downloadRoster(control),
      "review-decision": () => showReviewDecision(id, control.dataset.decision),
      "sync-source": () => syncSource(control),
      "source-details": () => showSourceDetails(control.dataset.vendor),
      "workspace-squad": () => {
        closeDialog();
        selectSquad(id);
        navigate("overview");
      },
      "athlete-fatigue": () => {
        state.fatigueId = id;
        if (!state.model.roster.some((athlete) => athlete.id === id))
          selectSquad("all");
        closeDialog();
        navigate("fatigue");
      },
    };
    try {
      const result = actions[action]?.();
      if (result?.catch) result.catch((error) => toast(error.message, true));
    } catch (error) {
      toast(error.message, true);
    }
  }
  if (!event.target.closest(".account-wrap")) {
    $("#account-menu").hidden = true;
    $("#account-button").setAttribute("aria-expanded", "false");
  }
  if (!event.target.closest("#activity-popover, #activity-button")) {
    $("#activity-popover").hidden = true;
    $("#activity-button").setAttribute("aria-expanded", "false");
  }
  const account = event.target.closest("[data-demo-account]");
  if (account) {
    const credentials = {
      coach: ["coach@sdip.local", "Coach#123"],
      admin: ["admin@sdip.local", "Admin#123"],
      physio: ["physio@sdip.local", "Physio#123"],
      analyst: ["analyst@sdip.local", "Analyst#123"],
    }[account.dataset.demoAccount];
    if (credentials) {
      $("#login-email").value = credentials[0];
      $("#login-password").value = credentials[1];
      $(".login-submit").focus();
    }
  }
});
document.addEventListener("input", (event) => {
  if (event.target.id === "roster-search") rosterSearch(event.target.value);
  if (event.target.id === "command-search") commandSearch(event.target.value);
});
document.addEventListener("change", (event) => {
  const { id, value } = event.target;
  if (id === "squad-select") selectSquad(value);
  if (id === "period-select") {
    state.days = Number(value);
    state.loaded = false;
    renderPage();
    loadWorkspace().catch((error) => toast(error.message, true));
  }
  if (id === "fatigue-athlete") {
    state.fatigueId = value;
    clearCharts();
    pageVersion++;
    renderFatigue();
  }
  if (id === "history-status") {
    state.historyFilter = value;
    updateHistoryTable();
  }
  if (id === "mapping-profile") syncImportSource();
  if (id === "import-file") selectImportFile(event.target.files[0]);
  if (id === "import-squad") updateImportButton();
});
document.addEventListener("submit", (event) => {
  if (event.target.id === "file-import-form") submitImport(event);
  if (event.target.id === "review-form") submitReview(event);
  if (event.target.id === "preferences-form") {
    event.preventDefault();
    state.distanceUnit = $("#preference-distance").value === "m" ? "m" : "km";
    state.compact = $("#preference-density").value === "compact";
    let saved = true;
    try {
      localStorage.setItem("sdip.distance-unit", state.distanceUnit);
      localStorage.setItem(
        "sdip.density",
        state.compact ? "compact" : "comfortable",
      );
    } catch {
      saved = false;
    }
    document.body.classList.toggle("compact", state.compact);
    closeDialog();
    renderPage();
    toast(
      saved
        ? "Display preferences saved in this browser."
        : "Preferences applied for this session. Browser storage is unavailable.",
    );
  }
});
document.addEventListener("keydown", (event) => {
  const editing =
    /INPUT|TEXTAREA|SELECT/.test(event.target.tagName) ||
    event.target.isContentEditable;
  if (
    (event.metaKey || event.ctrlKey) &&
    event.key.toLowerCase() === "k" &&
    state.me
  ) {
    event.preventDefault();
    if (!$("#app-dialog").open || $("#command-search")) showCommand();
  }
  if (
    event.key === "/" &&
    !editing &&
    !$("#app-dialog").open &&
    state.route === "athletes"
  ) {
    event.preventDefault();
    $("#roster-search")?.focus();
  }
  if (event.key === "Escape" && !$("#app-dialog").open) {
    closePopovers();
    closeMobileNav();
  }
  if (
    $("#command-search") &&
    $("#app-dialog").open &&
    ["ArrowDown", "ArrowUp"].includes(event.key)
  ) {
    const results = $$(".command-result");
    if (!results.length) return;
    event.preventDefault();
    const index = results.indexOf(document.activeElement);
    const next =
      event.key === "ArrowDown"
        ? (index + 1) % results.length
        : index <= 0
          ? results.length - 1
          : index - 1;
    results[next].focus();
  }
  if (
    event.key === "Tab" &&
    mobileMedia.matches &&
    document.body.classList.contains("nav-open") &&
    !$("#app-dialog").open
  ) {
    const controls = $$("a, button, select, input", $("#sidebar")).filter(
      (element) =>
        element.getClientRects().length &&
        !element.disabled &&
        !element.closest("[hidden]"),
    );
    const first = controls[0],
      last = controls.at(-1);
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    }
    if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
});

$(".skip-link").addEventListener("click", (event) => {
  event.preventDefault();
  const target = $("#workspace").hidden
    ? $("#login-email")
    : $("#main-content");
  target.focus({ preventScroll: true });
  target.scrollIntoView({ block: "start", behavior: "instant" });
});
$("#app-dialog").addEventListener("cancel", (event) => {
  event.preventDefault();
  closeDialog();
});
$("#app-dialog").addEventListener("click", (event) => {
  if (event.target !== $("#app-dialog")) return;
  const rect = event.target.getBoundingClientRect();
  if (
    event.clientX < rect.left ||
    event.clientX > rect.right ||
    event.clientY < rect.top ||
    event.clientY > rect.bottom
  )
    closeDialog();
});
$("#login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  $("#login-error").hidden = true;
  const button = $(".login-submit");
  button.disabled = true;
  button.textContent = "Signing in…";
  try {
    const pair = await state.client.request("/api/auth/login", {
      method: "POST",
      auth: false,
      json: {
        email: $("#login-email").value.trim(),
        password: $("#login-password").value,
      },
    });
    state.client.setTokens(pair);
    await enterWorkspace();
    history.replaceState(null, "", `${location.pathname}#/${state.route}`);
    $("#login-password").value = "";
  } catch (error) {
    $("#login-error").textContent = error.message;
    $("#login-error").hidden = false;
  } finally {
    button.disabled = false;
    button.innerHTML = `Sign in to workspace${icon("arrow-right")}`;
  }
});
$("#toggle-password").addEventListener("click", () => {
  const show = $("#login-password").type === "password";
  $("#login-password").type = show ? "text" : "password";
  $("#toggle-password").setAttribute(
    "aria-label",
    show ? "Hide password" : "Show password",
  );
  $("#toggle-password").innerHTML = icon(show ? "eye-off" : "eye");
});
$("#explore-demo").addEventListener("click", () => {
  location.href = "dashboard.html?demo=1";
});
$("#import-button").addEventListener("click", showImport);
$("#export-button").addEventListener("click", showExport);
$("#refresh-button").addEventListener("click", () =>
  loadWorkspace({ force: true }).catch((error) => toast(error.message, true)),
);
$("#help-button").addEventListener("click", showHelp);
$("#top-help-button").addEventListener("click", showHelp);
$("#command-button").addEventListener("click", showCommand);
$("#workspace-switcher").addEventListener("click", showWorkspace);
$("#preferences-button").addEventListener("click", showPreferences);
$("#sidebar-sources").addEventListener("click", () =>
  isAdmin() ? navigate("sources") : showHelp(),
);
$("#activity-button").addEventListener("click", showActivity);
$("#account-button").addEventListener("click", () => {
  const open = $("#account-menu").hidden;
  closePopovers();
  $("#account-menu").hidden = !open;
  $("#account-button").setAttribute("aria-expanded", String(open));
});
$("#logout-button").addEventListener("click", () => {
  if (state.demo) {
    location.href = "dashboard.html?signin=1";
    return;
  }
  state.client.clearTokens();
  history.replaceState(null, "", "dashboard.html");
  showLogin();
  $("#login-email").focus();
});
$("#open-mobile-nav").addEventListener("click", openMobileNav);
$("#close-mobile-nav").addEventListener("click", closeMobileNav);
$("#nav-scrim").addEventListener("click", closeMobileNav);
mobileMedia.addEventListener("change", updateMobileNav);
window.addEventListener("hashchange", () => {
  rosterSearch.cancel();
  commandSearch.cancel();
  state.route = routeFromHash();
  closeMobileNav();
  closePopovers();
  renderPage();
  if (state.me) {
    $("#main-content").focus({ preventScroll: true });
    window.scrollTo({ top: 0, behavior: "instant" });
  }
});
setInterval(() => {
  if (state.refreshAt && state.me)
    $("#updated-label").textContent =
      Date.now() - state.refreshAt.getTime() < 60_000
        ? "Updated just now"
        : `Updated ${relativeTime(state.refreshAt.toISOString()).toLowerCase()}`;
}, 60_000);

boot().catch((error) =>
  showLogin(`The workspace could not open. ${error.message}`),
);

