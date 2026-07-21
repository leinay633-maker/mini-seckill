#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT="${1:-$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)}"

if [ "$#" -gt 1 ]; then
  echo "Usage: $0 [ROOT]" >&2
  exit 2
fi

required_paths=(
  "README.md"
  "REPORT.md"
  "LICENSE"
  "Dockerfile"
  ".dockerignore"
  ".github/workflows/ci.yml"
  "docker-compose.app-scale.yml"
  "docker-compose.nginx.yml"
  "docker-compose.monitoring.yml"
  "benchmark/BASELINE.md"
  "benchmark/reset-env.sh"
  "benchmark/run-k6.sh"
  "benchmark/run-suite.sh"
  "benchmark/run-verify.sh"
  "benchmark/capture-db-metrics.sh"
  "benchmark/k6-order.js"
  "benchmark/verify.sql"
  "scripts/check-evidence.sh"
  "scripts/assert-integration-tests-ran.sh"
  "scripts/k6-100k-spike.js"
  "src/main/java/com/example/miniseckill/common/GlobalExceptionHandler.java"
  "src/main/java/com/example/miniseckill/config/AsyncConfig.java"
  "src/main/java/com/example/miniseckill/config/ClientIpResolver.java"
  "src/main/java/com/example/miniseckill/config/RedisConfig.java"
  "src/main/java/com/example/miniseckill/config/SeckillProperties.java"
  "src/main/java/com/example/miniseckill/controller/SeckillController.java"
  "src/main/java/com/example/miniseckill/job/ConsumingMessageRecoveryJob.java"
  "src/main/java/com/example/miniseckill/job/OrderTimeoutJob.java"
  "src/main/java/com/example/miniseckill/mq/SeckillConsumer.java"
  "src/main/java/com/example/miniseckill/mq/SeckillProducer.java"
  "src/main/java/com/example/miniseckill/service/AsyncSeckillLogWriter.java"
  "src/main/java/com/example/miniseckill/service/OrderIdGenerator.java"
  "src/main/java/com/example/miniseckill/service/impl/DynamicRateLimitServiceImpl.java"
  "src/main/java/com/example/miniseckill/service/impl/OrderServiceImpl.java"
  "src/main/java/com/example/miniseckill/service/impl/SeckillServiceImpl.java"
  "src/main/java/com/example/miniseckill/service/impl/SnowflakeOrderIdGenerator.java"
  "src/main/resources/lua/compare_delete.lua"
  "src/main/resources/lua/rate_limit_sliding.lua"
  "src/main/resources/lua/seckill_stock_sharded.lua"
  "sql/init.sql"
  "sql/upgrade_v5.sql"
  "src/test/java/com/example/miniseckill/controller/SeckillControllerTest.java"
  "src/test/java/com/example/miniseckill/integration/SeckillConcurrencyIT.java"
  "src/test/java/com/example/miniseckill/integration/SkuStockMapperIT.java"
  "src/test/java/com/example/miniseckill/lua/RedisLuaScriptTest.java"
  "src/test/java/com/example/miniseckill/service/impl/DynamicRateLimitServiceImplTest.java"
  "src/test/java/com/example/miniseckill/service/impl/SnowflakeOrderIdGeneratorTest.java"
  "benchmark/evidence/mac-arm64/README.md"
  "benchmark/evidence/mac-arm64/baseline-unique-summary.json"
  "benchmark/evidence/mac-arm64/baseline-unique-verify.txt"
  "benchmark/evidence/mac-arm64/baseline-duplicate-summary.json"
  "benchmark/evidence/mac-arm64/baseline-duplicate-verify.txt"
  "benchmark/evidence/mac-arm64/baseline-selltail-summary.json"
  "benchmark/evidence/mac-arm64/baseline-selltail-verify.txt"
  "benchmark/evidence/mac-arm64/optimized-unique-summary.json"
  "benchmark/evidence/mac-arm64/optimized-unique-verify.txt"
  "benchmark/evidence/mac-arm64/optimized-duplicate-summary.json"
  "benchmark/evidence/mac-arm64/optimized-duplicate-verify.txt"
  "benchmark/evidence/mac-arm64/optimized-selltail-summary.json"
  "benchmark/evidence/mac-arm64/optimized-selltail-verify.txt"
  "benchmark/evidence/mac-arm64/baseline-all-on-summary.json"
  "benchmark/evidence/mac-arm64/baseline-all-on-verify.txt"
  "benchmark/evidence/mac-arm64/baseline-all-on-db-metrics.txt"
  "benchmark/evidence/mac-arm64/optimized-all-on-summary.json"
  "benchmark/evidence/mac-arm64/optimized-all-on-verify.txt"
  "benchmark/evidence/mac-arm64/optimized-all-on-db-metrics.txt"
  "benchmark/evidence/mac-arm64/async-on-unique-summary.json"
  "benchmark/evidence/mac-arm64/async-on-unique-verify.txt"
  "benchmark/evidence/mac-arm64/async-off-unique-summary.json"
  "benchmark/evidence/mac-arm64/async-off-unique-verify.txt"
  "benchmark/evidence/mac-arm64/runs/20260713-051520-optimized-mac-r1-unique-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-051623-optimized-mac-r1-duplicate-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-051726-optimized-mac-r1-selltail-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-052411-optimized-mac-r2-unique-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-052514-optimized-mac-r2-duplicate-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-052617-optimized-mac-r2-selltail-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-053744-optimized-mac-r3-unique-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-053846-optimized-mac-r3-unique-verify.txt"
  "benchmark/evidence/mac-arm64/runs/20260713-053848-optimized-mac-r3-duplicate-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-053951-optimized-mac-r3-selltail-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-061325-baseline-mac-r1-unique-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-061426-baseline-mac-r1-unique-verify.txt"
  "benchmark/evidence/mac-arm64/runs/20260713-061428-baseline-mac-r1-duplicate-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-061531-baseline-mac-r1-selltail-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-063350-baseline-mac-r2-unique-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-063454-baseline-mac-r2-duplicate-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-063557-baseline-mac-r2-selltail-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-064613-baseline-mac-r3-unique-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-064716-baseline-mac-r3-duplicate-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-064820-baseline-mac-r3-selltail-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-083147-optimized-mac-async-off-r1-unique-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-083248-optimized-mac-async-off-r1-unique-verify.txt"
  "benchmark/evidence/mac-arm64/runs/20260713-085312-optimized-mac-async-off-r2-unique-summary.json"
  "benchmark/evidence/mac-arm64/runs/20260713-093208-optimized-mac-async-off-r3-unique-summary.json"
)

missing=()
for path in "${required_paths[@]}"; do
  if [ ! -s "$ROOT/$path" ]; then
    missing+=("$path")
  fi
done

if [ "${#missing[@]}" -gt 0 ]; then
  echo "Missing required project or evidence files (${#missing[@]}):" >&2
  for path in "${missing[@]}"; do
    echo "  - $path" >&2
  done
  exit 1
fi

python3 - "$ROOT/benchmark/evidence/mac-arm64" <<'PY'
import json
import sys
from pathlib import Path

evidence_root = Path(sys.argv[1])
failures = []
summary_files = sorted(evidence_root.rglob("*-summary.json"))
if not summary_files:
    failures.append("no *-summary.json files found")

for path in summary_files:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        failures.append(f"{path.relative_to(evidence_root)}: invalid JSON ({error})")
        continue

    metrics = data.get("metrics")
    if not isinstance(metrics, dict):
        failures.append(f"{path.relative_to(evidence_root)}: missing metrics object")
        continue
    for metric in ("http_reqs", "http_req_duration", "system_error_rate"):
        if metric not in metrics:
            failures.append(f"{path.relative_to(evidence_root)}: missing metric {metric}")

if failures:
    print("Evidence JSON validation failed:", file=sys.stderr)
    for failure in failures:
        print(f"  - {failure}", file=sys.stderr)
    raise SystemExit(1)

print(f"Evidence JSON validation passed. summaries={len(summary_files)}")
PY

echo "Evidence check passed. Checked ${#required_paths[@]} files."
