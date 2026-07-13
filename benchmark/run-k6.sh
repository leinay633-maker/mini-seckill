#!/usr/bin/env bash
# Mac/Linux 版 k6 压测驱动:设置环境变量、跑 k6-order.js、落 summary.json + output.txt。
# 与 run-k6.ps1 等价。
# 用法:
#   ./benchmark/run-k6.sh --name baseline-unique --mode unique --rate 75 --duration 667s [--hidden-path] [--base-url http://localhost:18080]
set -euo pipefail

MODE="unique"; RATE="1000"; DURATION="30s"; VUS="1000"; MAX_VUS="5000"
STOCK="1000"; DUP_USERS="1000"; USER_BASE="10000000"; NAME=""
HIDDEN="false"; BASE_URL="${BASE_URL:-http://localhost:8080}"; USER_JWT=""
K6="${K6:-k6}"

while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="$2"; shift 2;;
    --mode) MODE="$2"; shift 2;;
    --rate) RATE="$2"; shift 2;;
    --duration) DURATION="$2"; shift 2;;
    --vus) VUS="$2"; shift 2;;
    --max-vus) MAX_VUS="$2"; shift 2;;
    --stock) STOCK="$2"; shift 2;;
    --duplicate-users) DUP_USERS="$2"; shift 2;;
    --user-base) USER_BASE="$2"; shift 2;;
    --hidden-path) HIDDEN="true"; shift 1;;
    --base-url) BASE_URL="$2"; shift 2;;
    --user-jwt) USER_JWT="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 1;;
  esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
RESULT_DIR="$HERE/results"
mkdir -p "$RESULT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
[ -z "$NAME" ] && NAME="$MODE-rate$RATE"
FULL="$STAMP-$NAME"
SUMMARY="$RESULT_DIR/$FULL-summary.json"
STDOUT="$RESULT_DIR/$FULL-output.txt"

export BASE_URL ACTIVITY_ID=1 SKU_ID=1001 MODE RATE DURATION VUS MAX_VUS STOCK
export DUPLICATE_USERS="$DUP_USERS" USER_BASE USE_HIDDEN_PATH="$HIDDEN" USER_JWT
# k6 handleSummary 里的 SUMMARY_JSON 路径相对 cwd,这里用绝对路径
export SUMMARY_JSON="$SUMMARY"

echo "Running k6: name=$FULL mode=$MODE rate=$RATE duration=$DURATION hiddenPath=$HIDDEN"
# 从项目根跑,保证 k6-order.js 里默认相对路径可用;summary 用绝对路径覆盖
( cd "$ROOT" && "$K6" run "$HERE/k6-order.js" 2>&1 | tee "$STDOUT" )
echo "stdout=$STDOUT"
echo "summary=$SUMMARY"
