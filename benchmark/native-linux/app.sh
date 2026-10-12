#!/usr/bin/env bash
# Native app instances; never discard a live PID or start a second instance after a slow stop.
# Default: one JVM on 18080. APP_INSTANCES=N starts N JVMs on 18080.. (worker IDs 7..), and
# APP_POOL_SIZES="14 13 13" gives each its own Hikari maximum (the args must not set one).
set -euo pipefail
R=${MS_ROOT:-/data/ms}
. "$R/env.sh"
JVM_OPTS=${JVM_OPTS:--Xms2g -Xmx2g}
N=${APP_INSTANCES:-1}
read -r -a SIZES <<< "${APP_POOL_SIZES:-}"
[[ $N =~ ^[1-9]$ ]] || { echo "APP_INSTANCES must be 1..9" >&2; exit 2; }
(( ${#SIZES[@]} == 0 || ${#SIZES[@]} == N )) || { echo "APP_POOL_SIZES needs $N sizes" >&2; exit 2; }
pidf() { if (( $1 == 0 )); then echo "$R/run/app.pid"; else echo "$R/run/app-$1.pid"; fi; }
logf() { if (( $1 == 0 )); then echo "$R/logs/app.log"; else echo "$R/logs/app-$1.log"; fi; }
port() { echo $((18080 + $1)); }
valid_pid() { [[ $1 =~ ^[1-9][0-9]*$ ]] && (( $1 > 1 )); }
alive() {
  valid_pid "$1" && kill -0 "$1" 2>/dev/null || return 1
  # A dead, unreaped JVM cannot overlap a new instance.
  [[ $(awk '{print $3}' "/proc/$1/stat" 2>/dev/null) != Z ]]
}
running() { local f; f=$(pidf "$1"); [[ -f $f ]] && alive "$(cat "$f")"; }
stop_one() {
  local f pid cmd p
  f=$(pidf "$1"); p=$(port "$1")
  [[ -f $f ]] || return 0
  pid=$(cat "$f")
  valid_pid "$pid" || { echo "invalid app PID; refusing" >&2; return 1; }
  if alive "$pid"; then
    # Guard a recycled/stale PID: only stop the expected benchmark Java app.
    cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline")
    [[ $cmd == *java* && $cmd == *"--server.port=$p"* ]] || {
      echo "PID $pid is not the benchmark app; refusing to signal" >&2; return 1;
    }
    kill "$pid"
    for _ in $(seq 1 45); do alive "$pid" || break; sleep 1; done
    if alive "$pid"; then
      echo "PID $pid still alive after TERM; retaining PID file, refusing overlap" >&2; return 1
    fi
  fi
  rm -f "$f"
}
case "${1:-status}" in
  start)
    jar=$2; shift 2
    [[ -f $jar ]] || { echo "missing jar: $jar" >&2; exit 1; }
    for ((i = 0; i < N; i++)); do
      if running "$i"; then echo "app already running" >&2; exit 1; fi
      if curl -fs --max-time 2 "http://localhost:$(port "$i")/actuator/health" >/dev/null 2>&1; then
        echo "port $(port "$i") already serves an app without our live PID; refusing" >&2; exit 1
      fi
    done
    for ((i = 0; i < N; i++)); do
      extra=()
      (( ${#SIZES[@]} )) && extra=("--spring.datasource.hikari.maximum-pool-size=${SIZES[$i]}")
      MINI_SECKILL_WORKER_ID=$((7 + i)) TZ=Asia/Shanghai nohup java $JVM_OPTS ${JDWP:-} -jar "$jar" \
        --server.port="$(port "$i")" --spring.profiles.active=100k \
        --seckill.rate-limit.enabled=false --seckill.anti-brush.enabled=false \
        --seckill.dynamic-rate-limit.enabled=false "$@" ${extra[@]+"${extra[@]}"} > "$(logf "$i")" 2>&1 &
      echo $! > "$(pidf "$i")"
    done
    for ((i = 0; i < N; i++)); do
      up=0
      for _ in $(seq 1 120); do
        running "$i" || { echo "app exited"; tail -40 "$(logf "$i")"; exit 1; }
        if curl -fs --max-time 2 "http://localhost:$(port "$i")/actuator/health" >/dev/null 2>&1; then up=1; break; fi
        sleep 1
      done
      (( up )) || { echo "app health timeout" >&2; exit 1; }
      echo "app up: $jar pid=$(cat "$(pidf "$i")") port=$(port "$i")"
    done ;;
  stop)
    # Every instance with a PID file, whatever APP_INSTANCES says now.
    rc=0
    for f in "$R"/run/app.pid "$R"/run/app-[1-9].pid; do
      [[ -f $f ]] || continue
      i=0; [[ $f == */app-*.pid ]] && i=${f##*/app-} && i=${i%.pid}
      stop_one "$i" || rc=1
    done
    (( rc == 0 )) || exit 1
    echo "app stopped" ;;
  status)
    any=0
    for f in "$R"/run/app.pid "$R"/run/app-[1-9].pid; do
      [[ -f $f ]] && alive "$(cat "$f")" && { echo "app up pid=$(cat "$f") ($f)"; any=1; }
    done
    (( any )) || echo "app down" ;;
  *) echo "usage: app.sh start <jar> [spring args] | stop | status" >&2; exit 2 ;;
esac
