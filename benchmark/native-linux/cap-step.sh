#!/usr/bin/env bash
# Step-load capacity probe for one running app instance.
# Each step: reset with stock = 2 x (rate x DUR) so every request takes the full admission path,
# constant arrival rate for DUR seconds, then wait for MySQL orders to catch up with queued count.
# Usage: DUR=60 cap-step.sh <label> <rate> [rate ...]
set -euo pipefail
. /data/ms/env.sh
LABEL=$1; shift
DUR=${DUR:-60}
DRAIN_MAX=${DRAIN_MAX:-600}
B=/data/ms/mini-seckill/benchmark
OUT=/data/ms/results/$LABEL
mkdir -p "$OUT"
q() { mysql -N -B -uminiseckill -pminiseckill mini_seckill -e "$1" 2>/dev/null; }

{
  echo "label=$LABEL dur=${DUR}s rates=$* started=$(date '+%F %T')"
  echo "jar=$(tr '\0' ' ' < /proc/$(cat /data/ms/run/app.pid)/cmdline)"
  echo "nproc=$(nproc) cfs_quota=$(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us 2>/dev/null) mem_limit=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null)"
  echo "cpu=$(lscpu | sed -n 's/^Model name: *//p')"
  k6 version; java -version 2>&1 | head -1; /data/ms/opt/mysql-bin/mysqld --version; redis-server --version; rabbitmqctl -q version 2>/dev/null || true
} > "$OUT/env.txt"

for rate in "$@"; do
  name="$LABEL-r$rate"
  stock=$((rate * DUR * 2))
  echo "== step rate=$rate stock=$stock $(date '+%T')"
  bash "$B/reset-env.sh" "$stock" 1 1001 http://localhost:18080 > "$OUT/$name-reset.log" 2>&1 || { echo "reset failed"; exit 1; }
  redis-cli --scan --pattern 'seckill:rate:*' | xargs -r redis-cli del > /dev/null
  python3 /data/ms/bin/sampler.py "$OUT/$name-samples.jsonl" &
  sp=$!
  t0=$(date +%s.%N)
  vus=$(( rate > 2000 ? rate : 2000 ))
  BASE_URL=http://localhost:18080 bash "$B/run-k6.sh" --name "$name" --mode unique --rate "$rate" \
    --duration "${DUR}s" --stock "$stock" --user-base $((200000000 + rate * 1000)) \
    --vus "$vus" --max-vus 30000 > "$OUT/$name-k6.log" 2>&1 || true
  t1=$(date +%s.%N)
  summary=$(ls -t "$B"/results/*-"$name"-summary.json | head -1)
  cp "$summary" "$OUT/$name-summary.json"
  queued=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['metrics'].get('order_queued',{}).get('values',{}).get('count',0))" "$summary")
  # wait for consumers to land every queued order (or give up after DRAIN_MAX seconds)
  for _ in $(seq 1 "$DRAIN_MAX"); do
    [ "$(q 'select count(*) from seckill_order')" -ge "$queued" ] && break
    sleep 1
  done
  t2=$(date +%s.%N)
  sleep 2
  kill "$sp" 2>/dev/null || true
  bash "$B/run-verify.sh" "$name" > "$OUT/$name-verify-run.log" 2>&1 || true
  cp "$(ls -t "$B"/results/*-"$name"-verify.txt | head -1)" "$OUT/$name-verify.txt" 2>/dev/null || true
  python3 /data/ms/bin/cap-report.py "$OUT/$name" "$rate" "$t0" "$t1" "$t2" | tee -a "$OUT/steps.tsv"
  if python3 /data/ms/bin/cap-report.py --stop "$OUT/$name" "$rate" "$t0" "$t1" "$t2"; then
    echo "stop: knee reached at rate=$rate"; break
  fi
done
echo "done $(date '+%F %T')"
