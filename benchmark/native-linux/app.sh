#!/usr/bin/env bash
# One native app; never discard a live PID or start a second instance after a slow stop.
set -euo pipefail
R=${MS_ROOT:-/data/ms}
. "$R/env.sh"
PIDF=$R/run/app.pid
JVM_OPTS=${JVM_OPTS:--Xms2g -Xmx2g}
valid_pid() { [[ $1 =~ ^[1-9][0-9]*$ ]] && (( $1 > 1 )); }
alive() {
  valid_pid "$1" && kill -0 "$1" 2>/dev/null || return 1
  # A dead, unreaped JVM cannot overlap a new instance.
  [[ $(awk '{print $3}' "/proc/$1/stat" 2>/dev/null) != Z ]]
}
case "${1:-status}" in
  start)
    jar=$2; shift 2
    [[ -f $jar ]] || { echo "missing jar: $jar" >&2; exit 1; }
    if [[ -f $PIDF ]] && alive "$(cat "$PIDF")"; then echo "app already running" >&2; exit 1; fi
    if curl -fs --max-time 2 http://localhost:18080/actuator/health >/dev/null 2>&1; then
      echo "port 18080 already serves an app without our live PID; refusing" >&2; exit 1
    fi
    MINI_SECKILL_WORKER_ID=7 TZ=Asia/Shanghai nohup java $JVM_OPTS ${JDWP:-} -jar "$jar" \
      --server.port=18080 --spring.profiles.active=100k \
      --seckill.rate-limit.enabled=false --seckill.anti-brush.enabled=false \
      --seckill.dynamic-rate-limit.enabled=false "$@" > "$R/logs/app.log" 2>&1 &
    echo $! > "$PIDF"
    for _ in $(seq 1 120); do
      alive "$(cat "$PIDF")" || { echo "app exited"; tail -40 "$R/logs/app.log"; exit 1; }
      if curl -fs --max-time 2 http://localhost:18080/actuator/health >/dev/null 2>&1; then
        echo "app up: $jar pid=$(cat "$PIDF")"; exit 0
      fi
      sleep 1
    done
    echo "app health timeout" >&2; exit 1 ;;
  stop)
    if [[ -f $PIDF ]]; then
      pid=$(cat "$PIDF")
      valid_pid "$pid" || { echo "invalid app PID; refusing" >&2; exit 1; }
      if alive "$pid"; then
        # Guard a recycled/stale PID: only stop the expected benchmark Java app.
        cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline")
        [[ $cmd == *java* && $cmd == *'--server.port=18080'* ]] || {
          echo "PID $pid is not the benchmark app; refusing to signal" >&2; exit 1;
        }
        kill "$pid"
        for _ in $(seq 1 45); do alive "$pid" || break; sleep 1; done
        if alive "$pid"; then
          echo "PID $pid still alive after TERM; retaining PID file, refusing overlap" >&2; exit 1
        fi
      fi
      rm -f "$PIDF"
    fi
    echo "app stopped" ;;
  status)
    if [[ -f $PIDF ]] && alive "$(cat "$PIDF")"; then echo "app up pid=$(cat "$PIDF")"; else echo "app down"; fi ;;
  *) echo "usage: app.sh start <jar> [spring args] | stop | status" >&2; exit 2 ;;
esac
