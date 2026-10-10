"""Markdown tables for a capacity retest run: every step, with reconciliation and disk-stall seconds.

Joins ab-summary.py output (JSONL on disk), the verify files and a disk-sampler JSONL. A step "passes"
by the cap-report.py stop rule (system error rate <= 1% and dropped <= 1%). Disk stall seconds are
load-window seconds whose data-disk write await exceeded 2 ms; "-" means no disk sample existed.

Usage: python3 retest-table.py <results-dir> <prefix> <summary.jsonl> <disk.jsonl|-> [DUR]
"""
import glob
import json
import os
import sys

res, prefix, summ_path, disk_path = sys.argv[1:5]
DUR = float(sys.argv[5]) if len(sys.argv) > 5 else 60.0
disk = [json.loads(l) for l in open(disk_path) if l.strip()] if disk_path != "-" else []
rows = [json.loads(l) for l in open(summ_path) if l.strip()]


def verify(run, rate):
    v = os.path.join(res, run, "%s-r%d-verify.txt" % (run, rate))
    s = os.path.join(res, run, "%s-r%d-summary.json" % (run, rate))
    if not os.path.exists(v):
        return "verify 缺失"
    queued = json.load(open(s))["metrics"].get("order_queued", {}).get("values", {}).get("count", 0)
    f = {}
    statuses = []
    for line in open(v):
        c = line.rstrip("\n").split("\t")
        if c[0] == "messages_by_status":
            statuses.append((int(c[1]), int(c[3])))
        elif c[0] in ("orders", "duplicate_order_groups", "order_id_uniqueness"):
            f[c[0]] = c
    orders = int(f["orders"][1])
    dup = int(f["duplicate_order_groups"][1]) + int(f["order_id_uniqueness"][3])
    other = ",".join("%d:%d" % s for s in statuses if s[0] != 2)
    rel = "=" if orders == queued else (">" if orders > queued else "<")
    return "订单%s入队 重复%d%s" % (rel, dup, (" 非终态 " + other) if other else "")


def stall(t0):
    if not disk:
        return "-"
    w = [x for x in disk if t0 <= x["t"] <= t0 + DUR]
    return str(sum(1 for x in w if x["w_await_ms"] > 2)) if w else "-"


def t0_of(run, rate):
    p = os.path.join(res, run, "%s-r%d-samples.jsonl" % (run, rate))
    with open(p) as fh:
        return json.loads(fh.readline())["t"]


print("| 运行 | 到达率/s | 通过 | 完成迭代 | 丢弃 | 系统错误率 | HTTP p99 ms | 入队 p99 ms | 负载期 SUCCESS/s | 停流追平 s "
      "| Hikari 等待均值/峰值 | 借连接均值 ms | 持有均值 ms | 借连接/迭代 | 队列 ready 峰值 | 磁盘卡顿 s | 对账 |")
print("|" + "---|" * 17)
for r in rows:
    run, rate = r["run"], r["rate"]
    iters, dropped, err = r.get("iters"), r.get("dropped"), r.get("sys_err")
    ok = "否" if iters is None else ("是" if err <= 0.01 and dropped / (iters + dropped) <= 0.01 else "否")
    per_iter = round(r["hikari_borrows"] / iters, 2) if r.get("hikari_borrows") and iters else "-"
    print("| %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s/%s | %s | %s | %s | %s | %s | %s |" % (
        run[len(prefix) + 1:], rate, ok, iters, dropped, err, r.get("http_p99"), r.get("queue_p99"),
        r.get("success_per_s_load"), r.get("drain_tail_s"), r.get("hikari_pending_avg"), r.get("hikari_pending_max"),
        r.get("hikari_acquire_ms"), r.get("hikari_usage_ms"), per_iter, r.get("mq_ready_max"),
        stall(t0_of(run, rate)), verify(run, rate)))

stage_rows = [r for r in rows if any(k.startswith("stage_") for k in r)]
if stage_rows:
    print()
    print("| 运行 | 到达率/s | activity_lookup 次数/均值 ms | token_order_lookup | message_insert | initial_publish | 缓存 hit/miss/disabled |")
    print("|" + "---|" * 7)
    for r in stage_rows:
        cell = lambda s: "%s / %s" % (r.get("stage_%s_n" % s, "-"), r.get("stage_%s_ms" % s, "-"))
        print("| %s | %d | %s | %s | %s | %s | %s/%s/%s |" % (
            r["run"][len(prefix) + 1:], r["rate"], cell("activity_lookup"), cell("token_order_lookup"),
            cell("message_insert"), cell("initial_publish"), r.get("cache_hit", "-"), r.get("cache_miss", "-"),
            r.get("cache_disabled", "-")))
