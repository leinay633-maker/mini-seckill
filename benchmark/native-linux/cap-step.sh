#!/usr/bin/env bash
# 60-second native ladder; drain by durable state AND MQ, never client queued count alone.
# MS_ROOT/MS_REPO/MS_BIN allow a suite-local immutable instrumentation snapshot.
set -euo pipefail
ROOT=${MS_ROOT:-/data/ms}
. "$ROOT/env.sh"
if (( $# < 2 )) || [[ ! $1 =~ ^[a-zA-Z0-9_.-]+$ ]]; then
  echo "usage: DUR=60 cap-step.sh <label> <rate> [rate ...]" >&2; exit 2
fi
LABEL=$1; shift
DUR=${DUR:-60}; DRAIN_MAX=${DRAIN_MAX:-600}; WARMUP=${WARMUP:-0}; WARMUP_RATE=${WARMUP_RATE:-1000}
[[ $DUR =~ ^[1-9][0-9]*$ && $DRAIN_MAX =~ ^[1-9][0-9]*$ && $WARMUP =~ ^[0-9]+$ && $WARMUP_RATE =~ ^[1-9][0-9]*$ ]] || exit 2
for rate in "$@"; do [[ $rate =~ ^[1-9][0-9]*$ ]] || exit 2; done
B=${MS_REPO:-$ROOT/mini-seckill}/benchmark
BIN=${MS_BIN:-$ROOT/bin}
OUT=${MS_RESULTS:-$ROOT/results}/$LABEL
mkdir -p "$OUT"
sp=; dp=
cleanup() {
  for p in "$sp" "$dp"; do
    if [[ -n $p ]]; then kill "$p" 2>/dev/null || true; wait "$p" 2>/dev/null || true; fi
  done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
{
  echo "label=$LABEL dur=${DUR}s rates=$* started=$(date '+%F %T')"
  echo "nproc=$(nproc)"
  lscpu
  k6 version
  java -version 2>&1
} > "$OUT/env.txt"
# Untimed load on the freshly started JVM, so the first measured step does not include JIT
# compilation and Hikari growing from minimum-idle. Not a ladder step: no window, report row
# or stop decision, and the first step resets only after this load has drained.
if (( WARMUP > 0 )); then
  prefix="$OUT/$LABEL-warmup"
  [[ ! -e $prefix-reset.log ]] || { echo "warm-up already exists: $prefix" >&2; exit 2; }
  stock=$((WARMUP_RATE * WARMUP * 2))
  echo "== warm-up rate=$WARMUP_RATE ${WARMUP}s stock=$stock $(date '+%T')"
  bash "$B/reset-env.sh" "$stock" 1 1001 http://localhost:18080 > "$prefix-reset.log" 2>&1
  redis-cli --scan --pattern 'seckill:rate:*' | xargs -r redis-cli del > /dev/null
  rc=0
  BASE_URL=http://localhost:18080 bash "$B/run-k6.sh" --name "$LABEL-warmup" --mode unique \
    --rate "$WARMUP_RATE" --duration "${WARMUP}s" --stock "$stock" --user-base 100000000 \
    --vus $(( WARMUP_RATE > 2000 ? WARMUP_RATE : 2000 )) --max-vus 30000 > "$prefix-k6.log" 2>&1 || rc=$?
  shopt -s nullglob
  summaries=("$B"/results/*-"$LABEL-warmup"-summary.json)
  if (( ${#summaries[@]} == 1 )); then cp "${summaries[0]}" "$prefix-summary.json"; fi
  drained=0
  python3 "$BIN/pool-budget-observe.py" drain "$prefix" "$DRAIN_MAX" || drained=$?
  if (( drained != 0 )); then echo "drain not proven after warm-up" >&2; exit 20; fi
  if (( rc != 0 && rc != 99 )); then echo "k6 infrastructure failure during warm-up: $rc" >&2; exit 21; fi
fi
for rate in "$@"; do
  name="$LABEL-r$rate"; prefix="$OUT/$name"
  [[ ! -e $prefix-reset.log ]] || { echo "step already exists: $prefix" >&2; exit 2; }
  stock=$((rate * DUR * 2))
  echo "== step rate=$rate stock=$stock $(date '+%T')"
  bash "$B/reset-env.sh" "$stock" 1 1001 http://localhost:18080 > "$prefix-reset.log" 2>&1
  redis-cli --scan --pattern 'seckill:rate:*' | xargs -r redis-cli del > /dev/null
  python3 "$BIN/pool-budget-observe.py" state "$prefix-before.json"
  python3 "$BIN/sampler.py" "$prefix-samples.jsonl" > "$prefix-sampler.log" 2>&1 & sp=$!
  if [[ ${CAP_DIAGNOSTIC:-0} == 1 ]]; then
    python3 "$BIN/pool-budget-observe.py" diagnostic "$prefix-diag" "$(cat "$ROOT/run/app.pid")" \
      > "$prefix-diag.log" 2>&1 & dp=$!
  fi
  t0=$(date +%s.%N)
  vus=$(( rate > 2000 ? rate : 2000 ))
  rc=0
  BASE_URL=http://localhost:18080 bash "$B/run-k6.sh" --name "$name" --mode unique --rate "$rate" \
    --duration "${DUR}s" --stock "$stock" --user-base $((200000000 + rate * 1000)) \
    --vus "$vus" --max-vus 30000 > "$prefix-k6.log" 2>&1 || rc=$?
  t1=$(date +%s.%N)
  python3 "$BIN/pool-budget-observe.py" window "$prefix-window.json" "$rate" "$DUR" "$t0" "$t1" "$rc" "$stock"
  shopt -s nullglob
  summaries=("$B"/results/*-"$name"-summary.json)
  if (( ${#summaries[@]} != 1 )); then echo "missing/ambiguous k6 summary: $name" >&2; exit 21; fi
  cp "${summaries[0]}" "$prefix-summary.json"
  # Quiescence is additional correctness evidence, not a new admission stop threshold.
  drained=0
  python3 "$BIN/pool-budget-observe.py" drain "$prefix" "$DRAIN_MAX" || drained=$?
  t2=$(date +%s.%N)
  python3 "$BIN/pool-budget-observe.py" final "$prefix"
  sleep 2
  cleanup; sp=; dp=
  bash "$B/run-verify.sh" "$name" > "$prefix-verify-run.log" 2>&1 || echo "verify failed: $name" >&2
  verifies=("$B"/results/*-"$name"-verify.txt)
  if (( ${#verifies[@]} == 1 )); then cp "${verifies[0]}" "$prefix-verify.txt"; fi
  python3 "$BIN/cap-report.py" "$prefix" "$rate" "$t0" "$t1" "$t2" | tee -a "$OUT/steps.tsv"
  # Never reset under undrained deliveries. The suite will stop this app first,
  # archive this case, then start the next independent case.
  if (( drained != 0 )); then echo "drain not proven: $name" >&2; exit 20; fi
  if (( rc != 0 && rc != 99 )); then echo "k6 infrastructure failure: $rc" >&2; exit 21; fi
  if python3 "$BIN/cap-report.py" --stop "$prefix" "$rate" "$t0" "$t1" "$t2"; then
    echo "stop: knee reached at rate=$rate"; break
  fi
done
echo "done $(date '+%F %T')"
