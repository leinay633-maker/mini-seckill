#!/usr/bin/env bash
# 基线/复测统一压测批处理:只跑三场景,自动 reset、跑 k6、对账。
# 基线与阶段3复测调用同一脚本保证同参。
# 用法:./benchmark/run-suite.sh <label>   例:./benchmark/run-suite.sh baseline-mac
#   label 会成为结果文件前缀(baseline-mac / optimized-mac)
set -euo pipefail

LABEL="${1:-suite}"
BASE_URL="${BASE_URL:-http://localhost:18080}"
HERE="$(cd "$(dirname "$0")" && pwd)"
K6="${K6:-$HOME/bin/k6}"

# 场景参数(基线与复测必须一致)
UNIQUE_RATE=75;   UNIQUE_DUR=60s;  UNIQUE_STOCK=1000
DUP_RATE=75;      DUP_DUR=60s;     DUP_STOCK=1000;  DUP_USERS=1000
SELL_RATE=300;    SELL_DUR=30s;    SELL_STOCK=100

run_one() {
  local name="$1" mode="$2" rate="$3" dur="$4" stock="$5" userbase="$6" extra_users="$7"
  echo ""
  echo "########## 场景 $name (mode=$mode rate=$rate dur=$dur stock=$stock) ##########"
  bash "$HERE/reset-env.sh" "$stock" 1 1001 "$BASE_URL" >/dev/null 2>&1 || { echo "reset失败"; return 1; }
  # 额外 flush redis 保证初始态干净
  docker exec mini-seckill-redis redis-cli ping | grep -qx PONG
  docker exec mini-seckill-redis sh -c "redis-cli --scan --pattern 'seckill:rate:*' | xargs -r redis-cli del" >/dev/null 2>&1
  BASE_URL="$BASE_URL" K6="$K6" bash "$HERE/run-k6.sh" \
    --name "$LABEL-$name" --mode "$mode" --rate "$rate" --duration "$dur" \
    --stock "$stock" --user-base "$userbase" ${extra_users:+--duplicate-users "$extra_users"}
  bash "$HERE/run-verify.sh" "$LABEL-$name"
}

echo "==================== 压测套件 label=$LABEL 开始 ===================="
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')  BASE_URL=$BASE_URL"
echo "k6: $("$K6" version 2>/dev/null)"

# 场景1 unique 洪峰(对标旧 74.65 req/s)
run_one "unique" unique "$UNIQUE_RATE" "$UNIQUE_DUR" "$UNIQUE_STOCK" 70000000 ""
# 场景2 duplicate 防重(1000用户循环)
run_one "duplicate" duplicate "$DUP_RATE" "$DUP_DUR" "$DUP_STOCK" 80000000 "$DUP_USERS"
# 场景3 售罄尾部(小库存高压)
run_one "selltail" unique "$SELL_RATE" "$SELL_DUR" "$SELL_STOCK" 90000000 ""

echo ""
echo "==================== 套件完成 ===================="
echo "结果文件:"
ls -1 "$HERE/results/" | grep "$LABEL" | tail -20
