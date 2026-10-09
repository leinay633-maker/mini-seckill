"""1 Hz sampler: MySQL order count and per-process CPU% (from /proc deltas) until killed.

Usage: python3 sampler.py <out.jsonl>
"""
import json
import os
import subprocess
import sys
import time

NAMES = ("java", "redis-server", "mysqld", "beam.smp", "k6")
TICK = os.sysconf("SC_CLK_TCK")


def cpu_ticks():
    totals = dict.fromkeys(NAMES, 0)
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            with open(f"/proc/{pid}/comm") as f:
                comm = f.read().strip()
            if comm not in totals:
                continue
            with open(f"/proc/{pid}/stat") as f:
                fields = f.read().rsplit(")", 1)[1].split()
            totals[comm] += int(fields[11]) + int(fields[12])  # utime + stime
        except (FileNotFoundError, ProcessLookupError, IndexError):
            continue
    return totals


def orders():
    try:
        out = subprocess.run(
            ["mysql", "-N", "-B", "-uminiseckill", "-pminiseckill", "mini_seckill",
             "-e", "select count(*), coalesce(sum(status=2),0) from seckill_order"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            universal_newlines=True, timeout=5).stdout.split()  # python 3.6 on this host
        return int(out[0]), int(out[1])
    except Exception:
        return None, None


def main():
    out = open(sys.argv[1], "a", buffering=1)
    prev_t, prev = time.time(), cpu_ticks()
    while True:
        time.sleep(1)
        now, cur = time.time(), cpu_ticks()
        dt = now - prev_t
        cpu = {k: round((cur[k] - prev[k]) / TICK / dt * 100, 1) for k in NAMES}
        total, success = orders()
        out.write(json.dumps({"t": round(now, 3), "orders": total, "success": success, "cpu": cpu}) + "\n")
        prev_t, prev = now, cur


if __name__ == "__main__":
    main()
