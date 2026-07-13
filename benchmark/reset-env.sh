#!/usr/bin/env bash
# Mac/Linux 版环境重置:清库表、清 Redis seckill key、purge MQ 队列、init+warmup 库存。
# 与 reset-env.ps1 等价。用法:./benchmark/reset-env.sh [stock] [activityId] [skuId] [baseUrl]
set -euo pipefail

STOCK="${1:-1000}"
ACTIVITY_ID="${2:-1}"
SKU_ID="${3:-1001}"
BASE_URL="${4:-http://localhost:8080}"
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "== reset mysql tables =="
docker exec -i mini-seckill-mysql mysql -uminiseckill -pminiseckill mini_seckill < "$HERE/reset.sql"

echo "== clear redis seckill keys =="
docker exec mini-seckill-redis redis-cli ping | grep -qx PONG
docker exec mini-seckill-redis sh -c "redis-cli --scan --pattern 'seckill:*' | xargs -r redis-cli del"

echo "== purge rabbitmq queues =="
docker exec mini-seckill-rabbitmq rabbitmqctl purge_queue mini.seckill.order.queue 2>/dev/null
docker exec mini-seckill-rabbitmq rabbitmqctl purge_queue mini.seckill.dead.queue 2>/dev/null

echo "== initialize stock through application =="
curl -fsS -X POST "$BASE_URL/api/seckill/init?activityId=$ACTIVITY_ID&skuId=$SKU_ID&stock=$STOCK" && echo
curl -fsS -X POST "$BASE_URL/api/seckill/warmup?activityId=$ACTIVITY_ID&skuId=$SKU_ID" && echo
curl -fsS "$BASE_URL/api/seckill/stock?activityId=$ACTIVITY_ID&skuId=$SKU_ID" && echo
