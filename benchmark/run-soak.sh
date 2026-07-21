#!/usr/bin/env bash
# 单实例低速长跑:记录 JVM、连接池和消息表增长,不把单机容量外推为生产容量。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:18080}"
RATE="${RATE:-10}"
DURATION="${DURATION:-45m}"
STOCK="${STOCK:-30000}"
IMAGE="${IMAGE:-mini-seckill:local}"
APP_CONTAINER="${APP_CONTAINER:-mini-seckill-soak-app}"
COMPOSE_PROJECT="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' mini-seckill-mysql 2>/dev/null || true)"
DOCKER_NETWORK="${DOCKER_NETWORK:-${COMPOSE_PROJECT:-mini-seckill}_default}"
NAME="${NAME:-soak-single}"
RESULT_DIR="$HERE/results"
mkdir -p "$RESULT_DIR"
TIMELINE="$RESULT_DIR/$(date +%Y%m%d-%H%M%S)-$NAME-timeline.txt"
METRICS="$RESULT_DIR/$(date +%Y%m%d-%H%M%S)-$NAME-metrics.csv"
K6_PID=""

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$TIMELINE"; }
stop_app() { docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true; }
cleanup() {
  [ -n "$K6_PID" ] && kill -0 "$K6_PID" 2>/dev/null && kill "$K6_PID" 2>/dev/null || true
  stop_app
}
trap cleanup EXIT INT TERM
wait_http() {
  local url="$1" deadline=$((SECONDS + 120))
  until curl -fsS "$url" >/dev/null; do
    [ "$SECONDS" -ge "$deadline" ] && return 1
    sleep 2
  done
}
metric() {
  local pattern="$1"
  curl -fsS "$BASE_URL/actuator/prometheus" 2>/dev/null \
    | awk -v pattern="$pattern" '$0 ~ pattern && $0 !~ /^#/ {print $NF; exit}'
}
message_count() {
  docker exec -i mini-seckill-mysql mysql -N -B -uminiseckill -pminiseckill mini_seckill \
    -e "SELECT COUNT(*) FROM seckill_message WHERE activity_id=1 AND sku_id=1001" 2>/dev/null || printf 'NA'
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
    [ "$orders" = "$sold" ] && [ -n "$total" ] && [ -n "$buckets" ] && [ "$total" = "$buckets" ] && return 0
    sleep 5
  done
  return 1
}

: > "$TIMELINE"
printf 'epoch_seconds,jvm_heap_used,hikari_active,hikari_pending,seckill_message_rows\n' > "$METRICS"
stop_app
log "启动应用"
docker run -d --name "$APP_CONTAINER" --network "$DOCKER_NETWORK" -p 18080:8080 \
  -e JAVA_TOOL_OPTIONS='-Xms256m -Xmx512m' -e MINI_SECKILL_WORKER_ID=7 \
  -e SPRING_DATASOURCE_URL='jdbc:mysql://mysql:3306/mini_seckill?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true' \
  -e SPRING_DATASOURCE_USERNAME=miniseckill -e SPRING_DATASOURCE_PASSWORD=miniseckill \
  -e SPRING_DATA_REDIS_HOST=redis -e SPRING_DATA_REDIS_PORT=6379 \
  -e SPRING_RABBITMQ_HOST=rabbitmq -e SPRING_RABBITMQ_PORT=5672 \
  -e SPRING_RABBITMQ_USERNAME=guest -e SPRING_RABBITMQ_PASSWORD=guest \
  "$IMAGE" --server.port=8080 >/dev/null
wait_http "$BASE_URL/actuator/health"
log "重置环境 stock=$STOCK"
bash "$HERE/reset-env.sh" "$STOCK" 1 1001 "$BASE_URL" | tee -a "$TIMELINE"
log "启动 k6 rate=$RATE duration=$DURATION"
BASE_URL="$BASE_URL" K6="${K6:-$HOME/bin/k6}" bash "$HERE/run-k6.sh" \
  --name "$NAME" --mode unique --rate "$RATE" --duration "$DURATION" \
  --stock "$STOCK" --user-base 140000000 --max-vus 5000 >"$RESULT_DIR/$NAME-k6.log" 2>&1 &
K6_PID=$!

while kill -0 "$K6_PID" 2>/dev/null; do
  now="$(date +%s)"
  printf '%s,%s,%s,%s,%s\n' "$now" "$(metric 'jvm_memory_used_bytes.*area="heap"')" \
    "$(metric 'hikaricp_connections_active')" "$(metric 'hikaricp_connections_pending')" "$(message_count)" >> "$METRICS"
  sleep 60
done
wait "$K6_PID"
K6_PID=""
wait_reconcile || { log "120 秒内库存事实未收敛"; exit 1; }
log "k6 完成,执行最终对账"
bash "$HERE/run-verify.sh" "$NAME" | tee -a "$TIMELINE"
log "soak 完成; metrics=$METRICS timeline=$TIMELINE"
