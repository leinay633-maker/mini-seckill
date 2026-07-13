#!/usr/bin/env bash
# Mac/Linux 版压测后对账:跑 verify.sql 输出 MySQL 事实,附 Redis/队列摘要。
# 与 run-verify.ps1 等价。用法:./benchmark/run-verify.sh [name]
set -euo pipefail

NAME="${1:-verify}"
HERE="$(cd "$(dirname "$0")" && pwd)"
RESULT_DIR="$HERE/results"
mkdir -p "$RESULT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/$STAMP-$NAME-verify.txt"

{
  echo "===== MySQL verify.sql ====="
  docker exec -i mini-seckill-mysql mysql -uminiseckill -pminiseckill mini_seckill < "$HERE/verify.sql"
  echo
  echo "===== Redis stock keys (total + buckets) ====="
  # 不截断输出:在 pipefail 下提前关闭管道会让 docker exec 收到 SIGPIPE,
  # 从而跳过后续 RabbitMQ 对账。完整打印 total 与每个 bucket,便于复核分桶库存。
  docker exec mini-seckill-redis sh -c "redis-cli --scan --pattern 'seckill:stock:*' | sort | while IFS= read -r k; do printf '%s = %s\\n' \"\$k\" \"\$(redis-cli get \"\$k\")\"; done"
  echo
  echo "===== RabbitMQ queues ====="
  docker exec mini-seckill-rabbitmq rabbitmqctl list_queues name messages messages_ready messages_unacknowledged 2>/dev/null
} | tee "$OUT"

echo "verify saved to $OUT"
