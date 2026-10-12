"""Summarise one capacity step as a TSV row; with --stop, exit 0 if the step crossed the knee.

Usage: cap-report.py [--stop] <out-prefix> <rate> <t_start> <t_k6_end> <t_drained>
"""
import json
import sys

stop_mode = sys.argv[1] == "--stop"
args = sys.argv[2:] if stop_mode else sys.argv[1:]
prefix, rate, t0, t1, t2 = args[0], int(args[1]), float(args[2]), float(args[3]), float(args[4])

m = json.load(open(prefix + "-summary.json"))["metrics"]
val = lambda k: m.get(k, {}).get("values", {})  # k6 v2 summary nests values
get = lambda k, f: val(k).get(f, 0) or 0
iters = get("iterations", "count")
dropped = get("dropped_iterations", "count")
queued = get("order_queued", "count")
err = get("system_error_rate", "rate")
http = val("http_req_duration")
qrd = val("queue_response_duration")

samples = [json.loads(l) for l in open(prefix + "-samples.jsonl")]
load = [s for s in samples if t0 <= s["t"] <= t1]
peak_cpu = {k: max((s["cpu"][k] for s in load), default=0) for k in ("java", "redis-server", "mysqld", "beam.smp", "k6")}
avg_cpu = {k: round(sum(s["cpu"][k] for s in load) / max(len(load), 1)) for k in peak_cpu}
orders_at_k6_end = max((s["orders"] or 0 for s in load), default=0)
final_orders = max((s["orders"] or 0 for s in samples), default=0)
drain_tail = round(t2 - t1, 1)
landed_rate = round(final_orders / max(t2 - t0, 1e-9), 1)

if stop_mode:
    sys.exit(0 if (err > 0.01 or (iters and dropped / (iters + dropped) > 0.01)) else 1)

row = {
    "rate": rate, "iters": iters, "dropped": dropped, "sys_err": round(err, 4), "queued": queued,
    "http_p95": round(http.get("p(95)", 0), 2), "http_p99": round(http.get("p(99)", 0), 2),
    "queue_p99": round(qrd.get("p(99)", 0), 2),
    "orders_at_k6_end": orders_at_k6_end, "final_orders": final_orders,
    "drain_tail_s": drain_tail, "avg_landed_per_s": landed_rate,
    "cpu_avg%": avg_cpu, "cpu_peak%": peak_cpu,
}
print(json.dumps(row, ensure_ascii=False))
