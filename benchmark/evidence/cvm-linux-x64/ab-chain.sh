#!/usr/bin/env bash
# Launch release A/B suites one after another (each is a detached worker holding the suite lock).
# Usage: RATES="..." bash ab-chain.sh <base-sha> [<base-sha> ...]
set -euo pipefail
. /data/ms/env.sh
cd /data/ms/mini-seckill
LOG=/data/ms/results/ab-chain-$(date +%Y%m%d-%H%M%S).log
for base in "$@"; do
  out=$(BUDGET_BASE_SHA=$base BUDGET_MATRIX=ab-shared BUDGET_RATES="$RATES" DISK_DEVICE=vdb \
        bash benchmark/native-linux/pool-budget-suite.sh start | tee -a "$LOG" | sed -n 's/^Evidence directory: //p')
  [[ -n $out ]] || { echo "launch failed for $base" >> "$LOG"; exit 1; }
  echo "$(date '+%F %T') started base=$base out=$out" >> "$LOG"
  until [[ -f $out.tar.gz.sha256 || -f $out/PACK_FAILED.txt ]]; do sleep 20; done
  echo "$(date '+%F %T') finished base=$base status=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['status'])" "$out/DONE.json")" >> "$LOG"
done
echo "$(date '+%F %T') chain done" >> "$LOG"
