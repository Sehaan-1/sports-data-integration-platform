#!/usr/bin/env bash
# Loads the bundled demo data into a running SDIP instance as the A-Team coach.
# Usage: ./demo/load-demo.sh [base-url]   (default http://localhost:8080)
set -euo pipefail
BASE="${1:-http://localhost:8080}"
HERE="$(cd "$(dirname "$0")" && pwd)"

TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d '{"email":"coach@sdip.local","password":"Coach#123"}' | python3 -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")

for spec in "catapult-sessions.csv catapult-session-csv" "vald-forcedecks.csv vald-forcedecks-csv"; do
  set -- $spec
  echo "uploading $1 ..."
  curl -sf -X POST "$BASE/api/ingest/files" -H "Authorization: Bearer $TOKEN" \
      -F "file=@$HERE/$1" -F "profile=$2" | python3 -m json.tool
  sleep 3
done

echo
echo "== batches =="
curl -sf "$BASE/api/ingest/batches?size=10&sort=startedAt,asc" -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import json,sys
for b in json.load(sys.stdin)['content']:
    print(f\"{b['sourceSystem']:9} {b['status']:22} read={b['rowsRead']:3} written={b['rowsWritten']:3} skipped={b['rowsSkipped']:3}\")"
echo
echo "== team load (42d) =="
curl -sf "$BASE/api/dashboard/team/load?days=42" -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import json,sys
for r in json.load(sys.stdin):
    print(f\"{r['fullName']:15} sessions={r['sessions']} load={r['playerLoad']:.0f} distance={r['totalDistanceM']:.0f}m\")"
echo
echo "Demo loaded. Open $BASE/dashboard.html (coach@sdip.local / Coach#123)"
