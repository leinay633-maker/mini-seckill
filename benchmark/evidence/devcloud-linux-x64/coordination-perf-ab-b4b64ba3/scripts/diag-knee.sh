#!/usr/bin/env bash
# Hold one rate for DUR seconds and capture thread dumps + MySQL state mid-run.
# Usage: DUR=40 diag-knee.sh <label> <rate>
set -euo pipefail
. /data/ms/env.sh
LABEL=$1; RATE=$2; DUR=${DUR:-40}
B=/data/ms/mini-seckill/benchmark
OUT=/data/ms/results/$LABEL; mkdir -p "$OUT"
PID=$(cat /data/ms/run/app.pid)
stock=$((RATE * DUR * 2))
bash "$B/reset-env.sh" "$stock" 1 1001 http://localhost:18080 > "$OUT/reset.log" 2>&1
BASE_URL=http://localhost:18080 bash "$B/run-k6.sh" --name "$LABEL" --mode unique --rate "$RATE" \
  --duration "${DUR}s" --stock "$stock" --user-base 300000000 --vus "$RATE" --max-vus 30000 > "$OUT/k6.log" 2>&1 &
kp=$!
for t in 15 25; do
  sleep 10
  jstack "$PID" > "$OUT/jstack-$t.txt"
  mysql -uroot -proot -e "SHOW FULL PROCESSLIST; SHOW ENGINE INNODB STATUS\G; SHOW GLOBAL STATUS LIKE 'Threads_running'; SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'; SHOW GLOBAL STATUS LIKE 'Innodb_log_waits'; SHOW GLOBAL STATUS LIKE 'Innodb_os_log_fsyncs'" > "$OUT/mysql-$t.txt" 2>/dev/null
  top -b -n1 -H -p "$PID" | head -40 > "$OUT/top-threads-$t.txt"
done
sleep 5
wait "$kp" || true
# Summarise http-nio worker threads by state and first application frame
python3 - "$OUT" <<'EOF'
import re, sys, collections, glob
for f in sorted(glob.glob(sys.argv[1] + "/jstack-*.txt")):
    blocks = open(f).read().split("\n\n")
    states = collections.Counter(); frames = collections.Counter()
    for b in blocks:
        if '"http-nio' not in b or "exec-" not in b:
            continue
        m = re.search(r"java.lang.Thread.State: (\w+)", b)
        st = m.group(1) if m else "?"
        states[st] += 1
        lines = [l.strip() for l in b.splitlines() if l.strip().startswith("at ")]
        top = lines[0][3:] if lines else "?"
        app = next((l[3:] for l in lines if "com.example" in l), "-")
        frames[(st, top.split("(")[0], app.split("(")[0])] += 1
    print("==", f, dict(states))
    for (st, top, app), n in frames.most_common(8):
        print(f"{n:4d} {st:13s} top={top}  app={app}")
EOF
