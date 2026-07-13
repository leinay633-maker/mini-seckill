#!/usr/bin/env bash
# 采集一次压测前后的 MySQL 只读指标,用于对比动态限流/订单查询的数据库压力。
# 用法: bash benchmark/capture-db-metrics.sh <label>
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "用法: bash benchmark/capture-db-metrics.sh <label>" >&2
  exit 2
fi

LABEL="$1"
case "$LABEL" in
  ""|*[!A-Za-z0-9_.-]*)
    echo "label 只能包含字母、数字、点、下划线和短横线: $LABEL" >&2
    exit 2
    ;;
esac

HERE="$(cd "$(dirname "$0")" && pwd)"
RESULT_DIR="$HERE/results"
mkdir -p "$RESULT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULT_DIR/$STAMP-$LABEL-db-metrics.txt"

{
  echo "===== MySQL DB metrics: $LABEL ====="
  echo "captured_at_host=$(date '+%Y-%m-%d %H:%M:%S %z')"
  docker exec -i mini-seckill-mysql mysql \
    -uroot -proot --batch --raw mini_seckill <<'SQL'
SELECT NOW() AS captured_at;

SHOW GLOBAL STATUS
WHERE Variable_name IN ('Com_select', 'Com_insert', 'Com_update');

SELECT
    'seckill_rate_limit_rule activity+sku' AS target,
    DIGEST_TEXT,
    COUNT_STAR,
    SUM_TIMER_WAIT
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'mini_seckill'
  AND DIGEST_TEXT LIKE 'SELECT%'
  AND DIGEST_TEXT LIKE '%FROM%seckill_rate_limit_rule%WHERE%activity_id%sku_id%LIMIT%'

UNION ALL

SELECT
    'seckill_order activity+user+sku' AS target,
    DIGEST_TEXT,
    COUNT_STAR,
    SUM_TIMER_WAIT
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'mini_seckill'
  AND DIGEST_TEXT LIKE 'SELECT%'
  AND DIGEST_TEXT LIKE '%FROM%seckill_order%WHERE%activity_id%user_id%sku_id%LIMIT%';
SQL
} | tee "$OUT"

echo "metrics saved to $OUT"
