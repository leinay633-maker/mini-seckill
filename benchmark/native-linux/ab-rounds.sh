#!/usr/bin/env bash
# Interleaved A/B: restart the app with each jar in turn, same ladder each time.
# Usage: ab-rounds.sh <rounds> <rate> [rate ...]
set -uo pipefail
. /data/ms/env.sh
ROUNDS=$1; shift
cd /data/ms/bin
for i in $(seq 1 "$ROUNDS"); do
  for v in new-deb719a base-68858ab; do
    ./app.sh stop
    ./app.sh start "/data/ms/jars/$v.jar" || exit 1
    sleep 10
    DUR=60 ./cap-step.sh "ab-$v-round$i" "$@"
  done
done
echo "ab done $(date '+%F %T')"
