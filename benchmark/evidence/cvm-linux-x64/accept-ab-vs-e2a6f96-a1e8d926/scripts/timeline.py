"""Per-second timeline for one step: orders/s, Hikari pending/active, mean usage ms, disk write await.
Usage: python3 timeline.py <samples.jsonl> <metrics.jsonl> <disk.jsonl> [seconds]"""
import json, sys
samples = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
metrics = [json.loads(l) for l in open(sys.argv[2]) if l.strip()]
disk = [json.loads(l) for l in open(sys.argv[3]) if l.strip()] if sys.argv[3] != "-" else []
n = int(sys.argv[4]) if len(sys.argv) > 4 else 30
t0 = samples[0]["t"]
def near(rows, t):
    best = min(rows, key=lambda r: abs(r["t"] - t)) if rows else None
    return best if best and abs(best["t"] - t) < 1.0 else None
prev_m = None
print("s\torders/s\tpending\tactive\tusage_ms\tacq_ms\tdisk_w_await\tdisk_util\tjava%\tmysqld%")
for i in range(1, min(n, len(samples))):
    s, p = samples[i], samples[i - 1]
    m = near(metrics, s["t"]); d = near(disk, s["t"])
    a = m.get("app", {}) if m else {}
    usage = acq = ""
    if prev_m and a and "app" in prev_m:
        pa = prev_m["app"]
        dc = a.get("hikaricp_connections_usage_seconds_count", 0) - pa.get("hikaricp_connections_usage_seconds_count", 0)
        if dc > 0:
            usage = round((a["hikaricp_connections_usage_seconds_sum"] - pa["hikaricp_connections_usage_seconds_sum"]) / dc * 1000, 2)
            acq = round((a["hikaricp_connections_acquire_seconds_sum"] - pa["hikaricp_connections_acquire_seconds_sum"]) / dc * 1000, 2)
    prev_m = m if m else prev_m
    print("\t".join(str(x) for x in [round(s["t"] - t0), (s["orders"] or 0) - (p["orders"] or 0), a.get("hikaricp_connections_pending", ""),
          a.get("hikaricp_connections_active", ""), usage, acq, d["w_await_ms"] if d else "", d["util"] if d else "",
          s["cpu"]["java"], s["cpu"]["mysqld"]]))
