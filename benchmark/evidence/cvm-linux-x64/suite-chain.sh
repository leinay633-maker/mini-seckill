#!/usr/bin/env bash
# Detached suites one after another: bucket ablation, one vs three JVMs, unsharded correctness.
# Each suite holds its own lock; the next one starts only after the previous archive sidecar exists.
set -euo pipefail
. /data/ms/env.sh
cd /data/ms/mini-seckill
BASE=$1
LOG=/data/ms/results/suite-chain-$(date +%Y%m%d-%H%M%S).log
export JVM_OPTS="-Xms2g -Xmx2g -Xlog:gc:file=/data/ms/logs/gc-%p.log:time,uptime"
wait_for() { until [[ -f $1.tar.gz.sha256 || -f $1/PACK_FAILED.txt ]]; do sleep 20; done; }
run_budget() { # matrix
  local out
  out=$(BUDGET_BASE_SHA=$BASE BUDGET_MATRIX=$1 BUDGET_RATES="1000 2000 3000 3500 4000 4500 5000" DISK_DEVICE=vdb \
        bash benchmark/native-linux/pool-budget-suite.sh start | tee -a "$LOG" | sed -n 's/^Evidence directory: //p')
  [[ -n $out ]] || { echo "launch failed: $1" >> "$LOG"; exit 1; }
  echo "$(date '+%F %T') started matrix=$1 out=$out" >> "$LOG"
  wait_for "$out"
  echo "$(date '+%F %T') finished matrix=$1" >> "$LOG"
}
run_budget buckets
run_budget multi
out=$(COORD_REDIS_BUCKETS=0 bash benchmark/native-linux/coordination-suite.sh start | tee -a "$LOG" | sed -n 's/^Evidence: //p')
echo "$(date '+%F %T') started coordination unsharded out=$out" >> "$LOG"
wait_for "$out"
echo "$(date '+%F %T') chain done" >> "$LOG"
