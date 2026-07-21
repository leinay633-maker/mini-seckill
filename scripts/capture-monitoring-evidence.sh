#!/usr/bin/env bash
# 从 Prometheus 拉取一段压测窗口的指标和告警 JSON,不依赖 matplotlib。
set -euo pipefail

PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
OUTPUT_DIR="${1:-benchmark/results/monitoring-$(date +%Y%m%d-%H%M%S)}"
START="${START:-$(( $(date +%s) - 900 ))}"
END="${END:-$(date +%s)}"
STEP="${STEP:-15s}"
export START END STEP
mkdir -p "$OUTPUT_DIR"

query() {
  local name="$1" expression="$2"
  curl -fsS --get "$PROMETHEUS_URL/api/v1/query_range" \
    --data-urlencode "query=$expression" \
    --data-urlencode "start=$START" \
    --data-urlencode "end=$END" \
    --data-urlencode "step=$STEP" \
    > "$OUTPUT_DIR/$name.json"
}

query admission 'sum by (result) (rate(seckill_admission_total[1m]))'
query mq 'sum by (result) (rate(seckill_mq_total[1m]))'
query api-p95 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"/api/.*"}[1m])))'
query api-5xx 'sum(rate(http_server_requests_seconds_count{uri=~"/api/.*",status=~"5.."}[1m]))'
curl -fsS "$PROMETHEUS_URL/api/v1/alerts" > "$OUTPUT_DIR/alerts.json"

python3 - "$OUTPUT_DIR" <<'PY'
import json
import pathlib
import sys

out = pathlib.Path(sys.argv[1])
lines = [f"window_start={__import__('os').environ.get('START', '')}", f"window_end={__import__('os').environ.get('END', '')}"]
for path in sorted(out.glob("*.json")):
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError:
        lines.append(f"{path.name}: invalid JSON")
        continue
    status = payload.get("status")
    data = payload.get("data", {})
    if path.name == "alerts.json":
        alerts = data.get("alerts", []) if isinstance(data, dict) else []
        firing = [a.get("labels", {}).get("alertname", "unknown") for a in alerts if a.get("state") == "firing"]
        lines.append(f"alerts_firing={','.join(firing) if firing else 'none'}")
    else:
        results = data.get("result", []) if isinstance(data, dict) else []
        lines.append(f"{path.stem}: status={status} series={len(results)}")
(out / "summary.txt").write_text("\n".join(lines) + "\n")
PY

printf 'monitoring evidence saved to %s\n' "$OUTPUT_DIR"
