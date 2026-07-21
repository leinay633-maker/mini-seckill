#!/usr/bin/env bash
# MQ 积压追赶演练:先关闭消费者灌入消息,再恢复消费者并记录清空时长。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:18080}"
RATE="${RATE:-75}"
DURATION="${DURATION:-60s}"
STOCK="${STOCK:-1000}"
IMAGE="${IMAGE:-mini-seckill:local}"
APP_CONTAINER="${APP_CONTAINER:-mini-seckill-backlog-app}"
DOCKER_NETWORK="${DOCKER_NETWORK:-mini-seckill_default}"
NAME="${NAME:-backlog-single}"
RESULT_DIR="$HERE/results"
mkdir -p "$RESULT_DIR"
TIMELINE="$RESULT_DIR/$(date +%Y%m%d-%H%M%S)-$NAME-timeline.txt"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$TIMELINE"; }
queue_ready() {
  docker exec mini-seckill-rabbitmq rabbitmqctl list_queues name messages_ready 2>/dev/null \
    | awk '$1 == "mini.seckill.order.queue" {print $2+0}'
}
consumer_count() {
  docker exec mini-seckill-rabbitmq rabbitmqctl list_consumers 2>/dev/null \
    | awk 'NR > 1 && $1 ~ /mini\.seckill\.order\.queue/ {count++} END {print count+0}'
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
wait_http() {
  local url="$1" deadline=$((SECONDS + 120))
  until curl -fsS "$url" >/dev/null; do
    if [ "$SECONDS" -ge "$deadline" ]; then return 1; fi
    sleep 2
  done
}
start_app() {
  local auto_startup="$1"
  local args=(--server.port=8080 --seckill.rate-limit.enabled=false --seckill.anti-brush.enabled=false --seckill.dynamic-rate-limit.enabled=false)
  if [ "$auto_startup" = false ]; then args+=(--seckill.mq-consumer.auto-startup=false); fi
  docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$APP_CONTAINER" --network "$DOCKER_NETWORK" -p 18080:8080 \
    -e JAVA_TOOL_OPTIONS='-Xms256m -Xmx512m' \
    -e MINI_SECKILL_WORKER_ID=7 \
    -e SPRING_DATASOURCE_URL='jdbc:mysql://mysql:3306/mini_seckill?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true' \
    -e SPRING_DATASOURCE_USERNAME=miniseckill -e SPRING_DATASOURCE_PASSWORD=miniseckill \
    -e SPRING_DATA_REDIS_HOST=redis -e SPRING_DATA_REDIS_PORT=6379 \
    -e SPRING_RABBITMQ_HOST=rabbitmq -e SPRING_RABBITMQ_PORT=5672 \
    -e SPRING_RABBITMQ_USERNAME=guest -e SPRING_RABBITMQ_PASSWORD=guest \
    "$IMAGE" "${args[@]}" >/dev/null
}
stop_app() { docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true; }
cleanup() { stop_app; }
trap cleanup EXIT INT TERM

: > "$TIMELINE"
log "P1 启动消费者关闭模式"
start_app false
wait_http "$BASE_URL/actuator/health"
log "重置环境"
bash "$HERE/reset-env.sh" "$STOCK" 1 1001 "$BASE_URL" | tee -a "$TIMELINE"
consumers="$(consumer_count)"
log "P1 消费者数量=$consumers"
if [ "$consumers" -ne 0 ]; then
  log "消费者未关闭,拒绝继续,请确认 seckill.mq-consumer.auto-startup 生效"
  exit 1
fi

log "P1 灌入消息 rate=$RATE duration=$DURATION"
BASE_URL="$BASE_URL" K6="${K6:-$HOME/bin/k6}" bash "$HERE/run-k6.sh" \
  --name "$NAME-p1" --mode unique --rate "$RATE" --duration "$DURATION" \
  --stock "$STOCK" --user-base 130000000 --max-vus 5000 | tee -a "$TIMELINE"
ready_before="$(queue_ready)"
log "P1 ready=$ready_before"
if [ "$ready_before" -le 0 ]; then
  log "没有形成消息积压,拒绝生成误导性证据"
  exit 1
fi

stop_app
log "P2 启动消费者并记录追赶"
P2_STARTED_AT="$(date +%s)"
start_app true
wait_http "$BASE_URL/actuator/health"
for _ in $(seq 1 600); do
  ready="$(queue_ready)"
  printf '%s ready=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$ready" >> "$TIMELINE"
  if [ "$ready" -eq 0 ]; then
    P2_FINISHED_AT="$(date +%s)"
    catchup_seconds=$((P2_FINISHED_AT - P2_STARTED_AT))
    log "追赶完成 ready=0 catchup_seconds=$catchup_seconds"
    break
  fi
  sleep 1
done
if [ "${catchup_seconds:-}" = "" ]; then
  log "600 秒内未清空积压"
  exit 1
fi

wait_reconcile || { log "120 秒内库存事实未收敛"; exit 1; }
bash "$HERE/run-verify.sh" "$NAME" | tee -a "$TIMELINE"
log "积压演练完成; ready_before=$ready_before catchup_seconds=$catchup_seconds timeline=$TIMELINE"
