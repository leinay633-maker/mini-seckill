#!/usr/bin/env bash
# Sub-second "all pool connections busy" stalls: one long constant-rate step with 10 Hz Hikari, MySQL,
# PSI/steal samplers, JVM safepoint+GC log and a Tomcat access log (begin/end ms) on /dev/shm.
# Usage: stall-probe.sh <jar> [rate] [seconds]
set -euo pipefail
. /data/ms/env.sh
JAR=$1; RATE=${2:-2000}; SECS=${3:-480}
ID=stall-probe-$(date +%Y%m%d-%H%M%S); OUT=/data/ms/results/$ID; mkdir -p "$OUT"
ACC=/dev/shm/$ID-access; mkdir -p "$ACC"
COMMON=(--spring.datasource.hikari.maximum-pool-size=40 --spring.datasource.hikari.minimum-idle=8
  --spring.datasource.hikari.connection-timeout=2000 --seckill.capacity.activity-cache-ttl=250ms
  --seckill.capacity.initial-sending-enabled=true --seckill.mq-consumer.concurrent-consumers=8
  --seckill.mq-consumer.max-concurrent-consumers=32 --seckill.mq-consumer.prefetch-count=100
  --seckill.pool-budget.enabled=false
  --server.tomcat.accesslog.enabled=true --server.tomcat.accesslog.directory="$ACC"
  --server.tomcat.accesslog.prefix=access --server.tomcat.accesslog.rotate=false
  "--server.tomcat.accesslog.pattern=%{begin:msec}t %{end:msec}t %s %U")
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; bash /data/ms/bin/app.sh stop > "$OUT/stop.log" 2>&1 || true
  cp "$ACC"/access* "$OUT/" 2>/dev/null || true; rm -rf "$ACC"; }
trap cleanup EXIT
my() { mysql -uroot -proot -N -B "$@" 2>/dev/null; }
my -e "SET GLOBAL innodb_monitor_enable='module_log'; SET GLOBAL innodb_monitor_enable='module_buffer';"
my -e "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('innodb_redo_log_capacity','innodb_buffer_pool_size','innodb_io_capacity','innodb_io_capacity_max','innodb_max_dirty_pages_pct','innodb_adaptive_flushing','innodb_flush_method','innodb_page_cleaners','max_binlog_size','sync_binlog','innodb_flush_log_at_trx_commit')" > "$OUT/mysql-vars.txt"
cat /sys/kernel/mm/transparent_hugepage/enabled /sys/kernel/mm/transparent_hugepage/defrag > "$OUT/thp.txt" 2>&1 || true
JVM_OPTS="-Xms2g -Xmx2g -Xlog:gc*,safepoint:file=$OUT/jvm-%p.log:time,uptime,level,tags" \
  bash /data/ms/bin/app.sh start "$JAR" "${COMMON[@]}" > "$OUT/start.log" 2>&1
python3 - "$OUT/pool-10hz.jsonl" <<'PY' &
import json, re, sys, time, urllib.request
pat = re.compile(r'^hikaricp_connections(|_pending|_active|_idle)\{[^}]*\} ([0-9.eE+-]+)$', re.M)
with open(sys.argv[1], 'a', buffering=1) as out:
    while True:
        t = time.time()
        try:
            body = urllib.request.urlopen('http://localhost:18080/actuator/prometheus', timeout=2).read().decode()
            vals = {}
            for k, v in pat.findall(body):
                k = k.lstrip('_') or 'total'
                vals[k] = vals.get(k, 0) + float(v)
            out.write(json.dumps({'t': round(t, 3), 'done': round(time.time(), 3), **vals}) + '\n')
        except Exception as ex:
            out.write(json.dumps({'t': round(t, 3), 'err': type(ex).__name__}) + '\n')
        time.sleep(max(0.0, 0.1 - (time.time() - t)))
PY
pids+=($!)
python3 - "$OUT/os-10hz.jsonl" <<'PY' &
import json, sys, time
def psi(name):
    out = {}
    for line in open('/proc/pressure/' + name):
        kind, *fields = line.split()
        out[kind] = int(dict(f.split('=') for f in fields)['total'])
    return out
def steal():
    f = open('/proc/stat').readline().split()
    return int(f[8]), sum(int(x) for x in f[1:9])
with open(sys.argv[1], 'a', buffering=1) as out:
    while True:
        t = time.time()
        rec = {'t': round(t, 3), 'steal_total': steal()}
        for n in ('cpu', 'memory', 'io'):
            rec[n] = psi(n)
        out.write(json.dumps(rec) + '\n')
        time.sleep(max(0.0, 0.1 - (time.time() - t)))
PY
pids+=($!)
Q="SELECT UNIX_TIMESTAMP(NOW(6)),
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_running'),
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_current_waits'),
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_pages_dirty'),
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_wait_free'),
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_log_waits'),
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Com_commit'),
 (SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='log_lsn_checkpoint_age'),
 (SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='log_max_modified_age_async'),
 (SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='log_max_modified_age_sync'),
 (SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='buffer_flush_sync_waits'),
 (SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='buffer_flush_sync_total_pages');"
echo "t threads_running row_lock_waits pages_dirty wait_free log_waits com_commit ckpt_age max_age_async max_age_sync sync_flush_waits sync_flush_pages" > "$OUT/mysql-10hz.tsv"
( while true; do echo "$Q"; sleep 0.1; done ) | mysql -uroot -proot -N -B 2>/dev/null >> "$OUT/mysql-10hz.tsv" &
pids+=($!)
MS_RESULTS=$OUT DUR=$SECS WARMUP=15 bash /data/ms/bin/cap-step.sh "$ID-step" "$RATE" > "$OUT/ladder.log" 2>&1 || echo "ladder exit $?" >> "$OUT/ladder.log"
echo "done $OUT"
