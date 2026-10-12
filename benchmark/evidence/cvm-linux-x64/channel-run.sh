#!/usr/bin/env bash
# After suite-chain.sh finishes: channel-cache matrix with a 2 s RabbitMQ churn sampler alongside.
set -euo pipefail
. /data/ms/env.sh
BASE=$1; CHAIN_LOG=$2
until grep -q 'chain done' "$CHAIN_LOG"; do sleep 20; done
cd /data/ms/mini-seckill
git fetch -q origin && git checkout -q -B feature/bucket-ablation origin/feature/bucket-ablation
export JVM_OPTS="-Xms2g -Xmx2g -Xlog:gc:file=/data/ms/logs/gc-%p.log:time,uptime"
out=$(BUDGET_BASE_SHA=$BASE BUDGET_MATRIX=channels BUDGET_RATES="2000 3000 3500 4000 4500 5000" DISK_DEVICE=vdb \
      bash benchmark/native-linux/pool-budget-suite.sh start | sed -n 's/^Evidence directory: //p')
echo "$(date '+%F %T') started channels out=$out head=$(git rev-parse HEAD)"
python3 /data/ms/churn-sampler.py "$out-churn.jsonl" & cs=$!
until [[ -f $out.tar.gz.sha256 || -f $out/PACK_FAILED.txt ]]; do sleep 20; done
kill $cs
echo "$(date '+%F %T') channels done"
