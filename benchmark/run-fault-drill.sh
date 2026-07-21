#!/usr/bin/env bash
# 多实例 Redis 故障演练:持续压测、停止 Redis、恢复并逐实例重建库存,保留告警与时间线证据。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:80}"
K6="${K6:-$HOME/bin/k6}"
RATE="${RATE:-20}"
TOTAL_DURATION="${TOTAL_DURATION:-300s}"
FAILURE_AT="${FAILURE_AT:-60}"
RECOVERY_AFTER="${RECOVERY_AFTER:-180}"
STOCK="${STOCK:-5000}"
NAME="${NAME:-fault-multi}"
RESULT_DIR="$HERE/results"
mkdir -p "$RESULT_DIR"
TIMELINE="$RESULT_DIR/$(date +%Y%m%d-%H%M%S)-$NAME-timeline.txt"
ALERTS="$RESULT_DIR/$(date +%Y%m%d-%H%M%S)-$NAME-alerts.jsonl"
K6_PID=""
ALERT_PID=""

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$TIMELINE"
}

mysql_scalar() {
  docker exec -i mini-seckill-mysql mysql -N -B -uminiseckill -pminiseckill mini_seckill -e "$1" 2>/dev/null
}
wait_reconcile() {
  for _ in $(seq 1 24); do
    orders="$(mysql_scalar 'SELECT COUNT(*) FROM seckill_order WHERE activity_id=1 AND sku_id=1001 AND status=2')"
    sold="$(mysql_scalar 'SELECT sold_count FROM sku_stock WHERE activity_id=1 AND sku_id=1001')"
    total="$(docker exec mini-seckill-redis redis-cli GET seckill:stock:1:1001 2>/dev/null)"
    buckets="$(docker exec mini-seckill-redis sh -c "redis-cli --scan --pattern 'seckill:stock:1:1001:bucket:*' | while read k; do redis-cli get \"\$k\"; done" 2>/dev/null | paste -sd+ - | bc)"
    log "等待对账收敛 orders=$orders sold=$sold redis_total=$total bucket_sum=$buckets"
    [ "$orders" = "$sold" ] && [ "$total" = "$buckets" ] && return 0
    sleep 5
  done
  return 1
}

cleanup() {
  if [ -n "$ALERT_PID" ] && kill -0 "$ALERT_PID" 2>/dev/null; then kill "$ALERT_PID" 2>/dev/null || true; fi
  if [ -n "$K6_PID" ] && kill -0 "$K6_PID" 2>/dev/null; then kill "$K6_PID" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM

: > "$TIMELINE"
: > "$ALERTS"
log "故障演练开始 BASE_URL=$BASE_URL rate=$RATE total=$TOTAL_DURATION stock=$STOCK failure_at=${FAILURE_AT}s recovery_after=${RECOVERY_AFTER}s"
bash "$HERE/reset-env.sh" "$STOCK" 1 1001 "$BASE_URL" | tee -a "$TIMELINE"

(
  while true; do
    timestamp="$(date +%s)"
    if alerts="$(curl -fsS http://localhost:9090/api/v1/alerts 2>/dev/null)"; then
      printf '{"captured_at":%s,"alerts":%s}\n' "$timestamp" "$alerts" >> "$ALERTS"
    fi
    sleep 10
  done
) &
ALERT_PID=$!

log "启动 k6 故障压力"
SYSTEM_ERROR_THRESHOLD=1 BASE_URL="$BASE_URL" K6="$K6" bash "$HERE/run-k6.sh" \
  --name "$NAME" --mode unique --rate "$RATE" --duration "$TOTAL_DURATION" \
  --stock "$STOCK" --user-base 120000000 --max-vus 5000 >"$RESULT_DIR/$NAME-k6.log" 2>&1 &
K6_PID=$!

sleep "$FAILURE_AT"
log "停止 Redis"
bash "$ROOT/scripts/fault-stop-redis.sh" | tee -a "$TIMELINE"
sleep "$RECOVERY_AFTER"
log "启动 Redis"
bash "$ROOT/scripts/fault-start-redis.sh" | tee -a "$TIMELINE"
sleep "${RECOVERY_WAIT:-15}"

log "逐实例串行执行 Redis 恢复"
bash "$ROOT/scripts/recovery-check.sh" \
  http://localhost:8080 http://localhost:8081 http://localhost:8082 http://localhost:8083 \
  | tee -a "$TIMELINE"

log "等待 k6 完成"
wait "$K6_PID"
K6_PID=""
wait_reconcile || { log "120 秒内库存事实未收敛"; exit 1; }
log "执行最终对账"
bash "$HERE/run-verify.sh" "$NAME" | tee -a "$TIMELINE"
log "故障演练完成; alerts=$ALERTS timeline=$TIMELINE"
