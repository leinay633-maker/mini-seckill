"""1 Hz MySQL SUCCESS/count and /proc CPU sampler, Python 3.6+.
Timestamp after SQL observation, not before a potentially slow connection/query.
Failed observations remain null. CPU covers the measured tick interval.
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
            with open("/proc/%s/comm" % pid) as stream:
                comm = stream.read().strip()
            if comm not in totals:
                continue
            with open("/proc/%s/stat" % pid) as stream:
                fields = stream.read().rsplit(")", 1)[1].split()
            totals[comm] += int(fields[11]) + int(fields[12])
        except (OSError, IndexError):
            continue
    return totals


def orders():
    try:
        result = subprocess.run(
            ["mysql", "--connect-timeout=3", "-N", "-B", "-uminiseckill", "-pminiseckill", "mini_seckill",
             "-e", "select count(*), coalesce(sum(status=2),0) from seckill_order"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, universal_newlines=True, timeout=5)
        result.check_returncode()
        values = result.stdout.split()
        return int(values[0]), int(values[1])
    except Exception:
        return None, None


def main():
    with open(sys.argv[1], "a", buffering=1) as out:
        prev_t, prev = time.monotonic(), cpu_ticks()
        while True:
            time.sleep(1)
            started_at = time.time()
            now, cur = time.monotonic(), cpu_ticks()
            dt = now - prev_t
            cpu = {k: round((cur[k] - prev[k]) / TICK / dt * 100, 1) for k in NAMES}
            total, success = orders()
            out.write(json.dumps({"t": round(time.time(), 3), "started_at": started_at,
                                  "orders": total, "success": success, "cpu": cpu}) + "\n")
            prev_t, prev = now, cur


if __name__ == "__main__":
    main()
