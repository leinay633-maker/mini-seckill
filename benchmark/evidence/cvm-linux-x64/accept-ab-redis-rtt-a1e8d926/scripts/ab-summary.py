"""Summarise one A/B run: cap-step rows plus metrics-sampler aggregates over each step's load window.

The load window is [first sample, first sample + DUR]; sampler.py starts just before k6, so the
window may be shifted by up to ~1 s. Acquire/usage/stage means are delta(sum)/delta(count) over the
window, so they include every borrower (admission threads and consumers alike).

Usage: python3 ab-summary.py <results-dir> <prefix> <metrics.jsonl> [DUR]
  e.g. python3 ab-summary.py /data/ms/results ab-capacity-20261009-180011 \
       /data/ms/results/ab-capacity-20261009-180011-metrics.jsonl 60
"""
import glob
import json
import os
import sys

res_dir, prefix, metrics_path = sys.argv[1], sys.argv[2], sys.argv[3]
DUR = float(sys.argv[4]) if len(sys.argv) > 4 else 60.0

metrics = [json.loads(l) for l in open(metrics_path) if l.strip()]


def window(t0, t1):
    return [m for m in metrics if t0 <= m["t"] <= t1 and "app" in m]


def delta_mean_ms(rows, base):
    s0, s1 = rows[0]["app"].get(base + "_sum"), rows[-1]["app"].get(base + "_sum")
    c0, c1 = rows[0]["app"].get(base + "_count"), rows[-1]["app"].get(base + "_count")
    if None in (s0, s1, c0, c1) or c1 <= c0:
        return None
    return round((s1 - s0) / (c1 - c0) * 1000, 2)


def counter_delta(rows, key):
    a, b = rows[0]["app"].get(key), rows[-1]["app"].get(key)
    return None if a is None or b is None else int(b - a)


out = []
for d in sorted(glob.glob(os.path.join(res_dir, prefix + "-*-round*"))):
    if not os.path.isdir(d):
        continue
    label = os.path.basename(d)
    steps = {}
    if os.path.exists(os.path.join(d, "steps.tsv")):
        for line in open(os.path.join(d, "steps.tsv")):
            if line.strip():
                row = json.loads(line)
                steps[row["rate"]] = row
    for sp in sorted(glob.glob(os.path.join(d, label + "-r*-samples.jsonl"))):
        rate = int(sp.rsplit("-r", 1)[1].split("-")[0])
        samples = [json.loads(l) for l in open(sp) if l.strip()]
        if not samples:
            continue
        t0 = samples[0]["t"]
        t1 = t0 + DUR
        rec = {"run": label, "rate": rate}
        rec.update({k: steps.get(rate, {}).get(k) for k in (
            "iters", "dropped", "sys_err", "queued", "http_p95", "http_p99", "queue_p99",
            "orders_at_k6_end", "final_orders", "drain_tail_s")})
        load = [s for s in samples if t0 <= s["t"] <= t1]
        if len(load) >= 2 and load[-1]["success"] is not None and load[0]["success"] is not None:
            rec["success_per_s_load"] = round((load[-1]["success"] - load[0]["success"])
                                              / (load[-1]["t"] - load[0]["t"]), 1)
        rec["cpu_avg_java"] = round(sum(s["cpu"]["java"] for s in load) / max(len(load), 1))
        rec["cpu_avg_mysqld"] = round(sum(s["cpu"]["mysqld"] for s in load) / max(len(load), 1))
        w = window(t0, t1)
        if len(w) >= 2:
            pend = [m["app"].get("hikaricp_connections_pending", 0) for m in w]
            act = [m["app"].get("hikaricp_connections_active", 0) for m in w]
            rec["hikari_pending_avg"] = round(sum(pend) / len(pend), 1)
            rec["hikari_pending_max"] = int(max(pend))
            rec["hikari_active_avg"] = round(sum(act) / len(act), 1)
            rec["hikari_acquire_ms"] = delta_mean_ms(w, "hikaricp_connections_acquire_seconds")
            rec["hikari_usage_ms"] = delta_mean_ms(w, "hikaricp_connections_usage_seconds")
            rec["hikari_borrows"] = counter_delta(w, "hikaricp_connections_acquire_seconds_count")
            rec["hikari_timeouts"] = counter_delta(w, "hikaricp_connections_timeout_total")
            for stage in ("activity_lookup", "token_order_lookup", "message_insert", "initial_publish"):
                base = 'seckill_capacity_stage_seconds{stage="%s"}' % stage
                s0, s1 = w[0]["app"].get(base.replace("seconds{", "seconds_sum{")), \
                    w[-1]["app"].get(base.replace("seconds{", "seconds_sum{"))
                c0, c1 = w[0]["app"].get(base.replace("seconds{", "seconds_count{")), \
                    w[-1]["app"].get(base.replace("seconds{", "seconds_count{"))
                if None not in (s0, s1, c0, c1) and c1 > c0:
                    rec["stage_" + stage + "_ms"] = round((s1 - s0) / (c1 - c0) * 1000, 2)
                    rec["stage_" + stage + "_n"] = int(c1 - c0)
            for r in ("hit", "miss", "disabled"):
                v = counter_delta(w, 'seckill_activity_cache_total{result="%s"}' % r)
                if v is not None:
                    rec["cache_" + r] = v
        tail = [m for m in metrics if t0 <= m["t"] <= samples[-1]["t"] and "mq" in m]
        if tail:
            rec["mq_ready_max"] = max(m["mq"]["ready"] or 0 for m in tail)
            rec["mq_unacked_max"] = max(m["mq"]["unacked"] or 0 for m in tail)
        out.append(rec)

for rec in out:
    print(json.dumps(rec, ensure_ascii=False))
