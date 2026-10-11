#!/usr/bin/env bash
# Interleaved A/B: restart the app with each jar in turn, same ladder each time.
# Usage: AB_VARIANTS='capacity-<sha> new-deb719a' ab-rounds.sh <rounds> <rate> [rate ...]
# Omitting AB_VARIANTS preserves the historical pair. No workload/stop thresholds change.
set -euo pipefail
if (( $# < 2 )) || [[ ! $1 =~ ^[1-9][0-9]*$ ]]; then
  echo "usage: AB_VARIANTS='candidate baseline' $0 <rounds> <rate> [rate ...]" >&2
  exit 2
fi
. /data/ms/env.sh
ROUNDS=$1; shift
RUN_ID=${AB_RUN_ID:-}
[[ -z $RUN_ID || $RUN_ID =~ ^[a-zA-Z0-9_.-]+$ ]] || { echo "invalid AB_RUN_ID" >&2; exit 2; }
PREFIX="ab${RUN_ID:+-$RUN_ID}"
read -r -a variants <<< "${AB_VARIANTS:-new-deb719a base-68858ab}"
if (( ${#variants[@]} != 2 )) || [[ ${variants[0]} == "${variants[1]}" ]]; then
  echo "AB_VARIANTS must contain exactly two distinct jar labels" >&2
  exit 2
fi
for rate in "$@"; do
  [[ $rate =~ ^[1-9][0-9]*$ ]] || { echo "invalid rate: $rate" >&2; exit 2; }
done
for v in "${variants[@]}"; do
  [[ $v =~ ^[a-zA-Z0-9_.-]+$ ]] && [[ -f /data/ms/jars/$v.jar ]] || {
    echo "invalid label or missing jar: $v" >&2; exit 2;
  }
done
# Refuse to mix new samples with a previous run, before stopping any application.
for i in $(seq 1 "$ROUNDS"); do
  for v in "${variants[@]}"; do
    [[ ! -e /data/ms/results/$PREFIX-$v-round$i ]] || {
      echo "result directory exists; choose a new AB_RUN_ID: $PREFIX-$v-round$i" >&2; exit 2;
    }
  done
done
cd /data/ms/bin
for i in $(seq 1 "$ROUNDS"); do
  for v in "${variants[@]}"; do
    out="/data/ms/results/$PREFIX-$v-round$i"
    mkdir "$out"
    sha256sum "/data/ms/jars/$v.jar" > "$out/jar.sha256"
    ./app.sh stop
    ./app.sh start "/data/ms/jars/$v.jar" || exit 1
    sleep 10
    DUR=60 ./cap-step.sh "$PREFIX-$v-round$i" "$@"
  done
done
echo "ab done $(date '+%F %T')"
