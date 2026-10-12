#!/usr/bin/env bash
# Interleaved ablation of the two capacity switches on ONE jar: every round runs the four groups
# in turn, restarting the app each time with the same ladder and stop rule as ab-rounds.sh.
# Usage: ABL_RUN_ID=<new id> ablate-rounds.sh <jar-label> <rounds> <rate> [rate ...]
set -euo pipefail
if (( $# < 3 )) || [[ ! $2 =~ ^[1-9][0-9]*$ ]] || [[ -z ${ABL_RUN_ID:-} ]]; then
  echo "usage: ABL_RUN_ID=<id> $0 <jar-label> <rounds> <rate> [rate ...]" >&2
  exit 2
fi
. /data/ms/env.sh
JAR=/data/ms/jars/$1.jar; ROUNDS=$2; shift 2
[[ $ABL_RUN_ID =~ ^[a-zA-Z0-9_.-]+$ ]] && [[ -f $JAR ]] || { echo "bad run id or missing $JAR" >&2; exit 2; }
declare -A ARGS=(
  [both-off]="--seckill.capacity.activity-cache-ttl=0ms --seckill.capacity.initial-sending-enabled=false"
  [cache-only]="--seckill.capacity.activity-cache-ttl=250ms --seckill.capacity.initial-sending-enabled=false"
  [fuse-only]="--seckill.capacity.activity-cache-ttl=0ms --seckill.capacity.initial-sending-enabled=true"
  [both-on]="--seckill.capacity.activity-cache-ttl=250ms --seckill.capacity.initial-sending-enabled=true"
)
ORDER=(both-off cache-only fuse-only both-on)
for i in $(seq 1 "$ROUNDS"); do
  for g in "${ORDER[@]}"; do
    [[ ! -e /data/ms/results/abl-$ABL_RUN_ID-$g-round$i ]] || { echo "exists: abl-$ABL_RUN_ID-$g-round$i" >&2; exit 2; }
  done
done
cd /data/ms/bin
prev=""
# app.sh truncates logs/app.log on start; keep each group's log for stuck-message forensics.
save_log() { [[ -z $prev ]] || gzip -c /data/ms/logs/app.log > "$prev/app.log.gz"; }
trap save_log EXIT
for i in $(seq 1 "$ROUNDS"); do
  # Rotate the start position each round so no group always runs first after a cold host.
  for k in 0 1 2 3; do
    g=${ORDER[$(( (k + i - 1) % 4 ))]}
    out="/data/ms/results/abl-$ABL_RUN_ID-$g-round$i"
    mkdir "$out"
    sha256sum "$JAR" > "$out/jar.sha256"
    echo "${ARGS[$g]}" > "$out/app-args.txt"
    save_log
    prev=$out
    ./app.sh stop
    # shellcheck disable=SC2086
    ./app.sh start "$JAR" ${ARGS[$g]} || exit 1
    sleep 10
    DUR=60 ./cap-step.sh "abl-$ABL_RUN_ID-$g-round$i" "$@"
  done
done
echo "ablation done $(date '+%F %T')"
