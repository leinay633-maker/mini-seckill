#!/usr/bin/env bash
# One command from the checked-out PR: bash benchmark/native-linux/pool-budget-suite.sh start
# The whole experiment runs detached; no interactive polling is required.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
if [[ ${1:-} == plan ]]; then exec python3 "$HERE/pool-budget-suite.py" --plan; fi
[[ ${1:-} == start ]] || { echo "usage: $0 start | plan" >&2; exit 2; }
ROOT=${MS_ROOT:-/data/ms}
[[ -f $ROOT/env.sh ]] || { echo "missing $ROOT/env.sh (existing native installation required)" >&2; exit 2; }
. "$ROOT/env.sh"
command -v flock >/dev/null
mkdir -p "$ROOT/run" "$ROOT/results"
exec 9>"$ROOT/run/pool-budget-suite.lock"
flock -n 9 || { echo "another pool-budget suite holds the lock" >&2; exit 2; }
sha=$(git -C "$REPO" rev-parse HEAD)
ID=${BUDGET_RUN_ID:-pool-budget-$(date +%Y%m%d-%H%M%S)-${sha:0:8}-$$}
[[ $ID =~ ^[a-zA-Z0-9_.-]+$ ]] || { echo "invalid BUDGET_RUN_ID" >&2; exit 2; }
OUT=$ROOT/results/$ID
mkdir "$OUT"  # refuse collisions BEFORE any reset/build/stop
cp "$HERE/pool-budget-suite.py" "$OUT/worker.py"
# FD 9 is inherited by the worker and holds the flock for its entire lifetime.
PYTHONUNBUFFERED=1 nohup python3 "$OUT/worker.py" --repo "$REPO" --root "$ROOT" \
  --out "$OUT" --launcher-log "$ROOT/results/$ID.launcher.log" \
  > "$ROOT/results/$ID.launcher.log" 2>&1 < /dev/null &
pid=$!
printf '%s\n' "$pid" > "$OUT/worker.pid"
printf 'Started worker PID=%s\nEvidence directory: %s\nRun status: %s/DONE.json\nArchive (complete only with .sha256 sidecar): %s.tar.gz\n' "$pid" "$OUT" "$OUT" "$OUT"
