import { attachAcwr, dayKey, shiftDay, toCsv } from "./model.js";

// Entirely synthetic, deterministic fixtures. This adapter is only instantiated after an
// explicit ?demo=1 / “Explore demo” action. No network, credentials, or live writes are used.
export function createDemoClient(today = dayKey()) {
  const names = [
    "Arjun Mehta",
    "Vikram Singh",
    "Rohan Patil",
    "Karan Shah",
    "Aditya Rao",
    "Ishaan Kapoor",
    "Dev Malhotra",
    "Kabir Sethi",
    "Aarav Desai",
    "Nikhil Verma",
    "Rehan Khan",
    "Dhruv Joshi",
    "Samar Patel",
    "Yash Bhatia",
    "Neel Sinha",
    "Ayaan Das",
    "Vihaan Nair",
    "Ritvik Bose",
    "Aditi Sharma",
    "Ananya Roy",
    "Diya Mehra",
    "Riya Shah",
    "Tara Kapoor",
    "Meera Jain",
  ];
  const positions = [
    "Midfielder",
    "Defender",
    "Forward",
    "Midfielder",
    "Goalkeeper",
    "Forward",
    "Defender",
    "Midfielder",
    "Forward",
    "Defender",
    "Midfielder",
    "Goalkeeper",
    "Defender",
    "Forward",
    "Midfielder",
    "Defender",
    "Forward",
    "Midfielder",
    "Defender",
    "Midfielder",
    "Forward",
    "Goalkeeper",
    "Defender",
    "Forward",
  ];
  const uuid = (number) =>
    `de000000-0000-4000-8000-${String(number).padStart(12, "0")}`;
  const squads = [
    { id: uuid(101), name: "First team" },
    { id: uuid(102), name: "Development" },
  ];
  const athletes = names.map((fullName, index) => ({
    id: uuid(index + 1),
    fullName,
    position: positions[index],
    squadId: squads[index < 18 ? 0 : 1].id,
    squadName: squads[index < 18 ? 0 : 1].name,
    email: `${fullName.toLowerCase().replaceAll(" ", ".")}@athletes.example`,
    dob: `${1997 + (index % 7)}-${String(1 + (index % 12)).padStart(2, "0")}-${String(5 + (index % 20)).padStart(2, "0")}`,
    gender: index < 18 ? "MALE" : "FEMALE",
    provisional: false,
  }));
  const loadByAthlete = new Map();
  const testsByAthlete = new Map();
  athletes.forEach((athlete, index) => {
    const records = [];
    for (let ago = 210; ago >= 0; ago--) {
      const day = shiftDay(today, -ago);
      const weekday = new Date(`${day}T12:00:00Z`).getUTCDay();
      const pattern = [115, 340, 425, 285, 460, 350, 210][weekday];
      const variance = 1 + Math.sin(ago * 0.47 + index * 0.38) * 0.09;
      const weekProgress = ago < 7 ? 1.074 : 1;
      const personalProgress =
        ago < 7 && [2, 7, 11].includes(index) ? (index === 2 ? 1.52 : 1.25) : 1;
      const playerLoad = Math.round(
        pattern *
          (0.7 + (index % 7) * 0.065) *
          variance *
          weekProgress *
          personalProgress,
      );
      records.push({
        day,
        playerLoad,
        totalDistanceM: Math.round(playerLoad * (13.6 + (index % 4))),
        hsrM: Math.round(playerLoad * (1.2 + (index % 3) * 0.16)),
        maxVelocityKmh: +(
          27.8 +
          (index % 7) * 0.65 +
          Math.sin(ago) * 0.4
        ).toFixed(1),
      });
    }
    loadByAthlete.set(athlete.id, attachAcwr(records));
    const tests = [];
    for (let ago = 80; ago >= 0; ago--) {
      if (ago % 3 !== index % 3) continue;
      tests.push({
        day: shiftDay(today, -ago),
        testType: "CMJ",
        rsiModified: +(
          0.405 +
          (index % 6) * 0.022 +
          Math.sin(ago * 0.7) * 0.025
        ).toFixed(3),
        asymmetryPct: +(3.4 + (index % 7) * 0.65 + Math.sin(ago * 0.3)).toFixed(
          1,
        ),
        peakForceN: Math.round(1850 + index * 29 + Math.cos(ago) * 75),
      });
    }
    testsByAthlete.set(athlete.id, tests);
  });
  const now = new Date().getTime();
  const agoTime = (minutes) => new Date(now - minutes * 60_000).toISOString();
  const connectorState = [
    { vendor: "CATAPULT", lastSyncedAt: agoTime(4), updatedAt: agoTime(4) },
    { vendor: "VALD", lastSyncedAt: agoTime(12), updatedAt: agoTime(12) },
  ];
  const batches = [
    {
      id: uuid(301),
      batchType: "CONNECTOR",
      sourceSystem: "CATAPULT",
      fileName: null,
      status: "COMPLETED",
      rowsRead: 168,
      rowsWritten: 168,
      rowsSkipped: 0,
      triggeredBy: "Scheduled sync",
      startedAt: agoTime(4),
      finishedAt: agoTime(4),
      durationMs: 1240,
    },
    {
      id: uuid(302),
      batchType: "CONNECTOR",
      sourceSystem: "VALD",
      fileName: null,
      status: "COMPLETED",
      rowsRead: 48,
      rowsWritten: 48,
      rowsSkipped: 0,
      triggeredBy: "Scheduled sync",
      startedAt: agoTime(12),
      finishedAt: agoTime(12),
      durationMs: 864,
    },
    {
      id: uuid(303),
      batchType: "FILE_CSV",
      sourceSystem: "CATAPULT",
      fileName: "catapult_training_export.csv",
      status: "COMPLETED_WITH_SKIPS",
      rowsRead: 242,
      rowsWritten: 239,
      rowsSkipped: 3,
      triggeredBy: "demo@sdip.local",
      startedAt: agoTime(110),
      finishedAt: agoTime(110),
      durationMs: 2350,
    },
    {
      id: uuid(304),
      batchType: "FILE_XLSX",
      sourceSystem: "VALD",
      fileName: "forcedecks_cmj_results.xlsx",
      status: "COMPLETED",
      rowsRead: 72,
      rowsWritten: 72,
      rowsSkipped: 0,
      triggeredBy: "demo@sdip.local",
      startedAt: agoTime(340),
      finishedAt: agoTime(340),
      durationMs: 1900,
    },
    {
      id: uuid(305),
      batchType: "CONNECTOR",
      sourceSystem: "CATAPULT",
      fileName: null,
      status: "COMPLETED",
      rowsRead: 144,
      rowsWritten: 144,
      rowsSkipped: 0,
      triggeredBy: "Scheduled sync",
      startedAt: agoTime(1440),
      finishedAt: agoTime(1440),
      durationMs: 1160,
    },
    {
      id: uuid(306),
      batchType: "FILE_CSV",
      sourceSystem: "VALD",
      fileName: "weekly_strength_tests.csv",
      status: "COMPLETED",
      rowsRead: 96,
      rowsWritten: 96,
      rowsSkipped: 0,
      triggeredBy: "demo@sdip.local",
      startedAt: agoTime(1620),
      finishedAt: agoTime(1620),
      durationMs: 1420,
    },
  ];
  let quarantine = [
    {
      id: uuid(401),
      sourceSystem: "CATAPULT",
      sourceAthleteId: "CAT-1024",
      candidateAthleteId: athletes[0].id,
      score: 0.872,
      status: "PENDING",
      payload: JSON.stringify({
        fullName: "Arjun M.",
        dob: athletes[0].dob,
        candidateName: athletes[0].fullName,
        candidateDob: athletes[0].dob,
      }),
      createdAt: agoTime(35),
    },
    {
      id: uuid(402),
      sourceSystem: "VALD",
      sourceAthleteId: "VALD-2078",
      candidateAthleteId: athletes[1].id,
      score: 0.814,
      status: "PENDING",
      payload: JSON.stringify({
        fullName: "Vikram S.",
        dob: athletes[1].dob,
        candidateName: athletes[1].fullName,
        candidateDob: athletes[1].dob,
      }),
      createdAt: agoTime(72),
    },
    {
      id: uuid(403),
      sourceSystem: "CATAPULT",
      sourceAthleteId: "CAT-1086",
      candidateAthleteId: athletes[4].id,
      score: 0.766,
      status: "PENDING",
      payload: JSON.stringify({
        fullName: "Aditya R.",
        candidateName: athletes[4].fullName,
        candidateDob: athletes[4].dob,
      }),
      createdAt: agoTime(110),
    },
  ];
  const profiles = [
    {
      name: "catapult-session-csv",
      sourceSystem: "CATAPULT",
      entityType: "GPS",
    },
    { name: "vald-forcedecks-csv", sourceSystem: "VALD", entityType: "FORCE" },
    {
      name: "wellness-csv",
      sourceSystem: "CSV_LEGACY",
      entityType: "WELLNESS",
    },
  ];
  const within = (records, days) =>
    records.filter(
      (record) => record.day > shiftDay(today, -days) && record.day <= today,
    );
  const page = (rows) => ({
    content: rows,
    totalElements: rows.length,
    last: true,
    number: 0,
  });

  return {
    demo: true,
    async request(path, options = {}) {
      const url = new URL(path, "https://sdip.example");
      const route = url.pathname;
      const days = Math.min(366, Number(url.searchParams.get("days")) || 42);
      if (route === "/api/auth/me")
        return {
          userId: uuid(999),
          email: "demo@sdip.local",
          role: "ADMIN",
          squadId: "",
          displayName: "Demo administrator",
        };
      if (route === "/api/athletes") return page(athletes);
      if (route === "/api/admin/squads") return squads;
      if (route === "/api/dashboard/team/load")
        return athletes.map((athlete) => {
          const records = within(loadByAthlete.get(athlete.id), days);
          const sum = (key) =>
            records.reduce((total, record) => total + record[key], 0);
          return {
            athleteId: athlete.id,
            fullName: athlete.fullName,
            playerLoad: sum("playerLoad"),
            totalDistanceM: sum("totalDistanceM"),
            hsrM: sum("hsrM"),
            sessions: records.length,
          };
        });
      const athleteMatch = route.match(
        /^\/api\/dashboard\/athlete\/([^/]+)\/(load|fatigue-overlay)$/,
      );
      if (athleteMatch) {
        const athlete = athletes.find((value) => value.id === athleteMatch[1]);
        if (!athlete)
          throw new Error("This athlete is not in the demo workspace.");
        const result = {
          athleteId: athlete.id,
          athleteName: athlete.fullName,
          days: within(loadByAthlete.get(athlete.id), days),
        };
        if (athleteMatch[2] === "fatigue-overlay")
          result.forceTests = within(testsByAthlete.get(athlete.id), days);
        return result;
      }
      if (route === "/api/connectors/state") return connectorState;
      const syncMatch = route.match(/^\/api\/connectors\/([^/]+)\/sync$/);
      if (syncMatch && options.method === "POST") {
        const state = connectorState.find(
          (value) => value.vendor === syncMatch[1],
        );
        if (!state) throw new Error("Unknown data source.");
        await new Promise((resolve) => setTimeout(resolve, 700));
        state.lastSyncedAt = state.updatedAt = new Date().toISOString();
        return {
          vendor: state.vendor,
          status: "OK",
          recordsRead: 24,
          recordsWritten: 24,
          recordsQuarantined: 0,
        };
      }
      if (route === "/api/ingest/mapping-profiles") return profiles;
      if (route === "/api/ingest/batches") return page(batches);
      const batchMatch = route.match(
        /^\/api\/ingest\/batches\/([^/]+)(\/errors)?$/,
      );
      if (batchMatch) {
        const batch = batches.find((value) => value.id === batchMatch[1]);
        if (!batch)
          throw new Error("This sample batch is no longer available.");
        if (batchMatch[2])
          return batch.rowsSkipped
            ? [
                {
                  rowNumber: 42,
                  errorCode: "INVALID_DATE",
                  message:
                    "The session date could not be parsed. Expected an ISO date.",
                },
                {
                  rowNumber: 88,
                  errorCode: "RANGE_GUARD",
                  message:
                    "Total distance is outside the accepted metric range.",
                },
                {
                  rowNumber: 193,
                  errorCode: "IDENTITY_REQUIRED",
                  message: "No athlete name or source identity was present.",
                },
              ]
            : [];
        return batch;
      }
      if (route === "/api/ingest/files" && options.method === "POST") {
        const file = options.body.get("file");
        const profile = profiles.find(
          (value) => value.name === options.body.get("profile"),
        );
        if (!profile) throw new Error("Choose a mapping profile first.");
        if (!file) throw new Error("Choose a CSV or XLSX file first.");
        // We deliberately do not claim to validate vendor data or persist real athlete records.
        const batchId = crypto.randomUUID();
        batches.unshift({
          id: batchId,
          batchType: file.name.toLowerCase().endsWith(".xlsx")
            ? "FILE_XLSX"
            : "FILE_CSV",
          sourceSystem: profile.sourceSystem,
          fileName: file.name,
          status: "COMPLETED",
          rowsRead: options.demoRows || 0,
          rowsWritten: options.demoRows || 0,
          rowsSkipped: 0,
          startedAt: new Date().toISOString(),
          triggeredBy: "Demo simulation",
          durationMs: 0,
          simulated: true,
        });
        return { batchId, status: "SIMULATED" };
      }
      if (route === "/api/links/quarantine")
        return page(
          quarantine.filter(
            (value) =>
              value.status === (url.searchParams.get("status") || "PENDING"),
          ),
        );
      const resolveMatch = route.match(
        /^\/api\/links\/quarantine\/([^/]+)\/(confirm|reject)$/,
      );
      if (resolveMatch && options.method === "POST") {
        const item = quarantine.find(
          (value) => value.id === resolveMatch[1] && value.status === "PENDING",
        );
        if (!item)
          throw new Error("This sample identity has already been reviewed.");
        item.status = resolveMatch[2] === "confirm" ? "CONFIRMED" : "REJECTED";
        return { id: item.id, status: item.status };
      }
      throw new Error("This action is not available in the demo workspace.");
    },
    async download() {
      return new Blob(
        [
          toCsv(
            [
              "id",
              "full_name",
              "dob",
              "email",
              "gender",
              "provisional",
              "squad",
            ],
            athletes.map((athlete) => [
              athlete.id,
              athlete.fullName,
              athlete.dob,
              athlete.email,
              athlete.gender,
              athlete.provisional,
              athlete.squadName,
            ]),
          ),
        ],
        { type: "text/csv;charset=utf-8" },
      );
    },
  };
}
