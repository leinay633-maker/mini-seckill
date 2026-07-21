#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -eq 0 ]; then
  set -- "${BASE_URL:-http://localhost:8080}"
fi

for base_url in "$@"; do
  base_url="${base_url%/}"
  printf '\n== Redis recovery: %s ==\n' "$base_url"
  before="$(curl -fsS "$base_url/api/recovery/redis/status")"
  printf '%s\n' "$before"

  response="$(curl -fsS -X POST "$base_url/api/recovery/redis")"
  printf '%s\n' "$response"

  after="$(curl -fsS "$base_url/api/recovery/redis/status")"
  printf '%s\n' "$after"

  RECOVERY_RESPONSE="$response" RECOVERY_STATUS="$after" python3 - <<'PY'
import json
import os

response = json.loads(os.environ["RECOVERY_RESPONSE"])
status = json.loads(os.environ["RECOVERY_STATUS"])
response_data = response.get("data") or {}
status_data = status.get("data") or {}
recovered = response_data.get("recoveredSkuCount", 0)
recovering = status_data.get("recovering")
if recovered < 1:
    raise SystemExit(f"recovery did not rebuild any SKU: recoveredSkuCount={recovered}")
if recovering is not False:
    raise SystemExit(f"application still recovering after rebuild: recovering={recovering}")
PY
done
