#!/usr/bin/env bash
# Reserve-script micro-benchmark on a throwaway redis-server (127.0.0.1:16390, no persistence).
# Prints Redis-side EVALSHA usec_per_call from INFO commandstats; network and client time excluded.
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd)
BIN=/data/ms/opt/redis/bin
REPO=/data/ms/mini-seckill
PORT=16390
CALLS=${CALLS:-5000}
ROUNDS=${ROUNDS:-3}
cli() { "$BIN/redis-cli" -p "$PORT" "$@"; }
if cli ping >/dev/null 2>&1; then echo "port $PORT already in use" >&2; exit 1; fi
mkdir -p "$D/redis"
"$BIN/redis-server" --port "$PORT" --bind 127.0.0.1 --save '' --appendonly no --dir "$D/redis" \
  --daemonize yes --logfile "$D/redis/redis.log" --pidfile "$D/redis/redis.pid"
trap 'cli shutdown nosave >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 50); do cli ping >/dev/null 2>&1 && break; sleep 0.1; done
cli info server | grep -E '^(redis_version|process_id|hz|arch_bits):' | tr -d '\r'
echo "repo_head=$(git -C "$REPO" rev-parse HEAD) calls=$CALLS rounds=$ROUNDS"

setup() {  # plenty: every bucket 100000; sparse: only bucket 127 has stock (scan walks ~64 buckets)
  cli flushall >/dev/null
  local args=() total=0 v
  for i in $(seq 0 127); do
    if [ "$1" = plenty ] || [ "$i" = 127 ]; then v=100000; else v=0; fi
    args+=("seckill:stock:1:1001:bucket:$i" "$v"); total=$((total + v))
  done
  cli mset "${args[@]}" >/dev/null
  cli set seckill:stock:1:1001 "$total" >/dev/null
  cli set seckill:coord:1:1001:version "128:v0" >/dev/null
}
bucketsum() {
  cli --raw mget $(for i in $(seq 0 127); do printf 'seckill:stock:1:1001:bucket:%d ' "$i"; done) \
    | awk '{s += $1} END {print s}'
}
run() {  # label script kind scenario round
  setup "$4"
  local sha before after piped stats
  sha=$(cli script load "$(cat "$2")")
  before=$(bucketsum)
  cli config resetstat >/dev/null
  piped=$(python3 "$D/gen.py" "$3" "$sha" "$CALLS" "$5" | cli --pipe 2>&1 | tail -1 | tr -d '\r')
  stats=$(cli info commandstats | grep '^cmdstat_evalsha:' | tr -d '\r')
  after=$(bucketsum)
  echo "$4 $1 round$5 reserved=$((before - after)) [$piped] $stats"
}
for scenario in plenty sparse; do
  for round in $(seq 1 "$ROUNDS"); do
    run current "$REPO/src/main/resources/lua/coord_reserve.lua" coord "$scenario" "$round"
    run mget "$D/reserve_mget.lua" coord "$scenario" "$round"
    run scan "$D/reserve_scan.lua" coord "$scenario" "$round"
    run sharded-baseline "$REPO/src/main/resources/lua/seckill_stock_sharded.lua" sharded "$scenario" "$round"
  done
done
