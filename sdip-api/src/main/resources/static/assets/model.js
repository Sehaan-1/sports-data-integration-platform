/** Pure domain helpers shared by the browser and Node's frontend tests. All windows use UTC,
 * matching the dashboard API. Workload flags are descriptive, not medical assessments. */
export const DAY_MS = 86_400_000;

export function dayKey(value = new Date()) {
  return new Date(value).toISOString().slice(0, 10);
}

export function shiftDay(day, amount) {
  return new Date(Date.parse(`${day}T00:00:00Z`) + amount * DAY_MS)
    .toISOString()
    .slice(0, 10);
}

export function dateWindow(days, today = dayKey()) {
  const count = Math.max(1, Math.min(180, Math.trunc(Number(days) || 7)));
  return { from: shiftDay(today, 1 - count), to: today, days: count };
}

export function enumerateDays(from, to) {
  const result = [];
  for (let day = from; day <= to; day = shiftDay(day, 1)) result.push(day);
  return result;
}

export function percentChange(current, previous) {
  return previous > 0 ? ((current - previous) / previous) * 100 : null;
}

export function workloadStatus(acwr) {
  if (acwr == null || !Number.isFinite(Number(acwr)))
    return { label: "No baseline", tone: "neutral", key: "none" };
  if (acwr > 1.5) return { label: "High load", tone: "danger", key: "high" };
  if (acwr > 1.3)
    return { label: "Elevated", tone: "warning", key: "elevated" };
  if (acwr < 0.8) return { label: "Low load", tone: "neutral", key: "low" };
  return { label: "Balanced", tone: "", key: "balanced" };
}

/** Uncoupled 7:21 day ACWR, with rest days counted as zero, as in DashboardQueryService. */
export function attachAcwr(records) {
  const byDay = new Map();
  records.forEach((record) =>
    byDay.set(
      record.day,
      (byDay.get(record.day) || 0) + Number(record.playerLoad || 0),
    ),
  );
  const mean = (day, start, end) => {
    let sum = 0;
    for (let offset = start; offset <= end; offset++)
      sum += byDay.get(shiftDay(day, offset)) || 0;
    return sum / (end - start + 1);
  };
  const round = (value) => Math.round(value * 1000) / 1000;
  return records.map((record) => {
    const acuteLoad = mean(record.day, -6, 0);
    const chronicLoad = mean(record.day, -27, -7);
    return {
      ...record,
      acuteLoad: round(acuteLoad),
      chronicLoad: round(chronicLoad),
      acwr: chronicLoad > 0 ? round(acuteLoad / chronicLoad) : null,
    };
  });
}

export function buildWorkspace(
  athletes,
  teamRows,
  loads,
  days = 7,
  squadId = "all",
  today = dayKey(),
) {
  const window = dateWindow(days, today);
  const previousWindow = {
    from: shiftDay(window.from, -window.days),
    to: shiftDay(window.from, -1),
  };
  const inCurrent = (row) => row.day >= window.from && row.day <= window.to;
  const inPrevious = (row) =>
    row.day >= previousWindow.from && row.day <= previousWindow.to;
  const scoped = athletes.filter(
    (athlete) => squadId === "all" || athlete.squadId === squadId,
  );
  const allowed = new Set(scoped.map((athlete) => athlete.id));
  const team = new Map(
    teamRows
      .filter((row) => allowed.has(row.athleteId))
      .map((row) => [row.athleteId, row]),
  );
  const sum = (rows, key) =>
    rows.reduce((total, row) => total + (Number(row[key]) || 0), 0);
  const previousRecords = [];
  const roster = scoped
    .map((athlete) => {
      const series = loads.get(athlete.id) || [];
      const current = series.filter(inCurrent);
      const previous = series.filter(inPrevious);
      previousRecords.push(...previous);
      const total = team.get(athlete.id);
      const latest = current.at(-1);
      const acwr = latest?.acwr ?? null;
      return {
        ...athlete,
        playerLoad: total ? Number(total.playerLoad) || 0 : 0,
        totalDistanceM: total ? Number(total.totalDistanceM) || 0 : 0,
        hsrM: total ? Number(total.hsrM) || 0 : 0,
        sessions: total ? Number(total.sessions) || 0 : 0,
        acwr,
        status: workloadStatus(acwr),
        lastActivity:
          series.filter((record) => record.day <= today).at(-1)?.day || null,
        days: current,
        series,
      };
    })
    .sort(
      (a, b) =>
        b.playerLoad - a.playerLoad || a.fullName.localeCompare(b.fullName),
    );
  const trend = enumerateDays(window.from, window.to).map((day, index) => {
    const previousDay = shiftDay(previousWindow.from, index);
    const current = roster.flatMap((athlete) =>
      athlete.days.filter((record) => record.day === day),
    );
    const previous = previousRecords.filter(
      (record) => record.day === previousDay,
    );
    return {
      day,
      playerLoad: sum(current, "playerLoad"),
      totalDistanceM: sum(current, "totalDistanceM"),
      previousLoad: sum(previous, "playerLoad"),
      previousDistanceM: sum(previous, "totalDistanceM"),
    };
  });
  const totals = {
    athletes: scoped.length,
    active: roster.filter((athlete) => athlete.sessions > 0).length,
    squads: new Set(scoped.map((athlete) => athlete.squadId)).size,
    playerLoad: sum(roster, "playerLoad"),
    distanceM: sum(roster, "totalDistanceM"),
    sessions: sum(roster, "sessions"),
    previousLoad: sum(previousRecords, "playerLoad"),
    previousDistanceM: sum(previousRecords, "totalDistanceM"),
  };
  return { roster, trend, totals, window };
}

export function filterRoster(
  roster,
  query = "",
  status = "all",
  sort = "load-desc",
) {
  const term = query.trim().toLocaleLowerCase();
  const result = roster.filter(
    (athlete) =>
      (!term ||
        `${athlete.fullName} ${athlete.squadName}`
          .toLocaleLowerCase()
          .includes(term)) &&
      (status === "all" || athlete.status.key === status),
  );
  if (sort === "name-asc")
    result.sort((a, b) => a.fullName.localeCompare(b.fullName));
  if (sort === "load-asc") result.sort((a, b) => a.playerLoad - b.playerLoad);
  if (sort === "load-desc") result.sort((a, b) => b.playerLoad - a.playerLoad);
  return result;
}

/** Protect spreadsheet exports from formula execution, including leading whitespace. */
export function csvCell(value) {
  let text = String(value ?? "");
  if (/^[\s]*[=+@-]/.test(text)) text = `'${text}`;
  return `"${text.replaceAll('"', '""')}"`;
}

export function toCsv(headers, rows) {
  return (
    "\uFEFF" +
    [headers, ...rows].map((row) => row.map(csvCell).join(",")).join("\r\n")
  );
}

/** Small RFC-4180 reader for the *local demo* CSV preview; real ingestion stays server-side. */
export function parseCsv(text) {
  const rows = [];
  let row = [],
    cell = "",
    quoted = false;
  const source = text.replace(/^\uFEFF/, "");
  for (let index = 0; index < source.length; index++) {
    const char = source[index];
    if (char === '"') {
      if (quoted && source[index + 1] === '"') {
        cell += '"';
        index++;
      } else if (quoted || cell === "") quoted = !quoted;
      else cell += char;
    } else if (char === "," && !quoted) {
      row.push(cell);
      cell = "";
    } else if ((char === "\r" || char === "\n") && !quoted) {
      if (char === "\r" && source[index + 1] === "\n") index++;
      row.push(cell);
      if (row.some((value) => value.trim())) rows.push(row);
      row = [];
      cell = "";
    } else cell += char;
  }
  if (quoted)
    throw new Error(
      "The CSV contains an unclosed quoted field. Check the file and try again.",
    );
  row.push(cell);
  if (row.some((value) => value.trim())) rows.push(row);
  return rows;
}

/** A bounded worker pool avoids hundreds of simultaneous per-athlete API requests. */
export async function mapConcurrent(values, worker, concurrency = 5) {
  const results = new Array(values.length);
  let cursor = 0;
  await Promise.all(
    Array.from({ length: Math.min(concurrency, values.length) }, async () => {
      while (cursor < values.length) {
        const index = cursor++;
        results[index] = await worker(values[index], index);
      }
    }),
  );
  return results;
}
