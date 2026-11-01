#!/usr/bin/env bash
# Calls the stock server's API through kubectl port-forward, with the API token
# from the cluster. Plain ASCII and braced variables so it runs on macOS bash 3.2.
#
#   deploy/zs-api.sh GET /api/status
#   deploy/zs-api.sh POST /api/test-alert '{"kind":"in_stock","retailer":"Target","url":"https://www.target.com/p/-/A-12345"}'
#   deploy/zs-api.sh GET /api/shots/bestbuy.png > bestbuy.png
set -euo pipefail

METHOD=${1:?usage: zs-api.sh METHOD PATH [JSON]}
URLPATH=${2:?usage: zs-api.sh METHOD PATH [JSON]}
BODY=${3:-}
NS=zelda-stock
PORT=${PORT:-18081}

TOKEN=$(kubectl -n "${NS}" get secret zelda-stock -o jsonpath='{.data.API_TOKEN}' | base64 --decode)
kubectl -n "${NS}" port-forward deploy/zelda-stock-server "${PORT}:8080" >/dev/null 2>&1 &
PF=$!
trap 'kill "${PF}" 2>/dev/null || true' EXIT
for _ in $(seq 30); do curl -s -o /dev/null "http://127.0.0.1:${PORT}/healthz" && break; sleep 1; done

curl -sS -X "${METHOD}" -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  ${BODY:+-d "${BODY}"} "http://127.0.0.1:${PORT}${URLPATH}"
if [ -t 1 ]; then echo; fi
