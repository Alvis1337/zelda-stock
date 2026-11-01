#!/usr/bin/env bash
# Clears one retailer's cookies in the server's browser, then runs a check and
# shows that retailer's result. For a site whose cookies got flagged (Best Buy's
# ERR_HTTP2_PROTOCOL_ERROR). Run it by hand from a machine with kubectl access.
# Plain ASCII and braced variables, so it also runs on macOS's bash 3.2.
#
#   deploy/clear-site-cookies.sh                 # bestbuy.com, Best Buy
#   deploy/clear-site-cookies.sh target.com Target
set -euo pipefail

SITE=${1:-bestbuy.com}
RETAILER=${2:-Best Buy}
NS=zelda-stock
PORT=${PORT:-18080}

TOKEN=$(kubectl -n "${NS}" get secret zelda-stock -o jsonpath='{.data.API_TOKEN}' | base64 --decode)
kubectl -n "${NS}" port-forward deploy/zelda-stock-server "${PORT}:8080" >/dev/null 2>&1 &
PF=$!
RESUMED=0
cleanup() {
  # Never leave the scheduler paused, whatever happened.
  [ "${RESUMED}" = 1 ] || api POST /api/resume >/dev/null 2>&1 || true
  kill "${PF}" 2>/dev/null || true
}
trap cleanup EXIT

api() { curl -fsS -X "$1" -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  ${3:+-d "$3"} "http://127.0.0.1:${PORT}$2"; }
field() { python3 -c "import json,sys; v=json.load(sys.stdin).get('$1'); print('' if v is None else json.dumps(v))"; }

for _ in $(seq 30); do api GET /api/status >/dev/null 2>&1 && break; sleep 1; done
api GET /api/status >/dev/null || { echo "server not reachable through port-forward" >&2; exit 1; }

echo "Pausing the scheduler..."
api POST /api/pause >/dev/null
for _ in $(seq 120); do
  [ -z "$(api GET /api/status | field running)" ] && break
  echo "  waiting for the current run to stop..."; sleep 2
done

echo "Clearing cookies for ${SITE}..."
api POST /api/clear-cookies "{\"site\":\"${SITE}\"}"; echo

echo "Resuming and checking now..."
api POST /api/resume >/dev/null; RESUMED=1
BEFORE=$(api GET /api/status | field lastRun)
api POST /api/check >/dev/null
for _ in $(seq 100); do
  sleep 5
  S=$(api GET /api/status)
  [ -z "$(echo "${S}" | field running)" ] && [ "$(echo "${S}" | field lastRun)" != "${BEFORE}" ] && break
  echo "  checking $(echo "${S}" | field running | python3 -c 'import json,sys; r=sys.stdin.read().strip(); print(json.loads(r).get("progress","...") if r else "...")')..."
done

echo
echo "${RETAILER} now:"
api GET /api/status | python3 -c "
import json, sys
rows = [r for r in json.load(sys.stdin)['rows'] if r['retailer'] == sys.argv[1]]
for r in rows: print(f\"  {r['status']:13} [{r['where']}] {r['detail']}\")
ok = any(r['status'] in ('IN STOCK', 'OUT OF STOCK', 'NONE FOUND') for r in rows)
print('\nFixed: it reads normally again.' if ok else '\nStill not reading. Look at /api/shots or the noVNC view.')
sys.exit(0 if ok else 2)" "${RETAILER}"
