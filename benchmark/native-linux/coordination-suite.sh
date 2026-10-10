#!/usr/bin/env bash
# Dedicated existing PR #3 native fixture only. Detached worker; no Docker / network-fault tools.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
if [[ ${1:-} == plan ]]; then exec python3 "$HERE/coordination-suite.py" --plan; fi
[[ ${1:-} == start ]] || { echo "usage: $0 start | plan" >&2; exit 2; }
ROOT=${MS_ROOT:-/data/ms}
[[ -f $ROOT/env.sh ]] || { echo "missing existing native fixture $ROOT/env.sh" >&2; exit 2; }
. "$ROOT/env.sh"
command -v flock >/dev/null
mkdir -p "$ROOT/run" "$ROOT/results"
exec 8>"$ROOT/run/coordination-suite.lock"
flock -n 8 || { echo "another coordination suite holds the lock" >&2; exit 2; }
# Cooperates with the PR #3 launcher; refuse overlapping destructive resets.
exec 9>"$ROOT/run/pool-budget-suite.lock"
flock -n 9 || { echo "pool-budget suite is active" >&2; exit 2; }
sha=$(git -C "$REPO" rev-parse HEAD)
ID=${COORD_RUN_ID:-coordination-$(date +%Y%m%d-%H%M%S)-${sha:0:8}-$$}
[[ $ID =~ ^[a-zA-Z0-9_.-]+$ ]] || { echo "invalid COORD_RUN_ID" >&2; exit 2; }
OUT=$ROOT/results/$ID
mkdir "$OUT" # collision must fail before build, stop, migration or reset
cp "$HERE/coordination-suite.py" "$OUT/worker.py"
# The worker inherits both advisory locks. Child JVMs do not inherit them.
PYTHONUNBUFFERED=1 nohup python3 "$OUT/worker.py" --repo "$REPO" --root "$ROOT" --out "$OUT" \
  --launcher-log "$ROOT/results/$ID.launcher.log" > "$ROOT/results/$ID.launcher.log" 2>&1 < /dev/null &
pid=$!
printf '%s\n' "$pid" > "$OUT/worker.pid"
printf 'Started PID=%s\nEvidence: %s\nFinal status: %s/DONE.json\nArchive: %s.tar.gz (complete only with .sha256 sidecar)\n' "$pid" "$OUT" "$OUT" "$OUT"
