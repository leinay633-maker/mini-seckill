#!/usr/bin/env bash
# Start/stop one app instance with the REPORT.md benchmark arguments.
# Usage: app.sh start <jar> [extra spring args...] | stop | status
set -euo pipefail
. /data/ms/env.sh
R=/data/ms
PIDF=$R/run/app.pid
JVM_OPTS=${JVM_OPTS:--Xms2g -Xmx2g}
case "${1:-status}" in
  start)
    jar=$2; shift 2
    [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null && { echo "app already running"; exit 1; }
    MINI_SECKILL_WORKER_ID=7 TZ=Asia/Shanghai nohup java $JVM_OPTS ${JDWP:-} -jar "$jar" \
      --server.port=18080 --spring.profiles.active=100k \
      --seckill.rate-limit.enabled=false --seckill.anti-brush.enabled=false \
      --seckill.dynamic-rate-limit.enabled=false "$@" > "$R/logs/app.log" 2>&1 &
    echo $! > "$PIDF"
    for _ in $(seq 1 120); do
      curl -fs http://localhost:18080/actuator/health >/dev/null 2>&1 && { echo "app up: $jar pid=$(cat "$PIDF")"; exit 0; }
      kill -0 "$(cat "$PIDF")" 2>/dev/null || { echo "app exited"; tail -40 "$R/logs/app.log"; exit 1; }
      sleep 1
    done
    echo "app health timeout"; exit 1 ;;
  stop)
    if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then
      kill "$(cat "$PIDF")"; for _ in $(seq 1 30); do kill -0 "$(cat "$PIDF")" 2>/dev/null || break; sleep 1; done
    fi
    rm -f "$PIDF"; echo "app stopped" ;;
  status)
    [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null && echo "app up pid=$(cat "$PIDF")" || echo "app down" ;;
esac
