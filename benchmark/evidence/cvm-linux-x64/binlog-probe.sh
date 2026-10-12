#!/usr/bin/env bash
# Does a binlog rotation fill the Hikari pool? One 150 s step at a constant rate, FLUSH BINARY LOGS at
# fixed offsets from the window start, Hikari pending at 10 Hz, binlog file switches at 10 Hz.
# Usage: binlog-probe.sh <jar> [rate]
set -euo pipefail
. /data/ms/env.sh
JAR=$1; RATE=${2:-2000}
ID=binlog-probe-$(date +%Y%m%d-%H%M%S); OUT=/data/ms/results/$ID; mkdir -p "$OUT"
COMMON=(--spring.datasource.hikari.maximum-pool-size=40 --spring.datasource.hikari.minimum-idle=8
  --spring.datasource.hikari.connection-timeout=2000 --seckill.capacity.activity-cache-ttl=250ms
  --seckill.capacity.initial-sending-enabled=true --seckill.mq-consumer.concurrent-consumers=8
  --seckill.mq-consumer.max-concurrent-consumers=32 --seckill.mq-consumer.prefetch-count=100)
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; bash /data/ms/bin/app.sh stop > "$OUT/stop.log" 2>&1 || true; }
trap cleanup EXIT
mysql -uroot -proot -N -e "select @@max_binlog_size, @@binlog_expire_logs_seconds, @@sync_binlog" > "$OUT/mysql-vars.txt" 2>/dev/null
bash /data/ms/bin/app.sh start "$JAR" "${COMMON[@]}" > "$OUT/start.log" 2>&1
python3 - "$OUT/pool-10hz.jsonl" <<'PY' &
import json, re, sys, time, urllib.request
pat = re.compile(r'^hikaricp_connections_(pending|active)\{[^}]*\} ([0-9.eE+-]+)$', re.M)
with open(sys.argv[1], 'a', buffering=1) as out:
    while True:
        t = time.time()
        try:
            body = urllib.request.urlopen('http://localhost:18080/actuator/prometheus', timeout=1).read().decode()
            vals = {}
            for k, v in pat.findall(body):
                vals[k] = vals.get(k, 0) + float(v)
            out.write(json.dumps({'t': round(t, 3), **vals}) + '\n')
        except Exception as ex:
            out.write(json.dumps({'t': round(t, 3), 'err': type(ex).__name__}) + '\n')
        time.sleep(max(0.0, 0.1 - (time.time() - t)))
PY
pids+=($!)
( last=""; while true; do cur=$(ls /data/ms/data/mysql | grep -E '^binlog\.[0-9]+$' | tail -1 || true)
  if [[ $cur != "$last" ]]; then echo "$(date +%s.%N) $cur" >> "$OUT/binlog-switch.txt"; last=$cur; fi; sleep 0.1; done ) &
pids+=($!)
( while [[ ! -e $OUT/$ID-step/$ID-step-r$RATE-before.json ]]; do sleep 0.2; done
  sleep 20; for _ in 1 2 3 4; do echo "$(date +%s.%N) FLUSH" >> "$OUT/flush.txt"
  mysql -uroot -proot -e 'FLUSH BINARY LOGS' 2>/dev/null; sleep 30; done ) &
pids+=($!)
MS_RESULTS=$OUT DUR=150 WARMUP=15 bash /data/ms/bin/cap-step.sh "$ID-step" "$RATE" > "$OUT/ladder.log" 2>&1 || echo "ladder exit $?" >> "$OUT/ladder.log"
echo "done $OUT"
