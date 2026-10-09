"""1 Hz /proc/diskstats sampler for the data disk: per-second IOs, write await ms, util %.
Shared-host disk noise shows up here even when the container's own CPU is idle.
Usage: python3 disk-sampler.py <device e.g. vdc> <out.jsonl>
"""
import json, sys, time

dev, path = sys.argv[1], sys.argv[2]


def read():
    for line in open("/proc/diskstats"):
        f = line.split()
        if f[2] == dev:
            # reads, read_ms, writes, write_ms, io_ms
            return int(f[3]), int(f[6]), int(f[7]), int(f[10]), int(f[12])
    raise SystemExit("device not found: " + dev)


out = open(path, "a", buffering=1)
pt, p = time.time(), read()
while True:
    time.sleep(1)
    t, c = time.time(), read()
    dt = t - pt
    dw, dwm = c[2] - p[2], c[3] - p[3]
    dr, drm = c[0] - p[0], c[1] - p[1]
    out.write(json.dumps({"t": round(t, 3), "w_iops": round(dw / dt), "w_await_ms": round(dwm / dw, 2) if dw else 0,
                          "r_iops": round(dr / dt), "r_await_ms": round(drm / dr, 2) if dr else 0,
                          "util": round((c[4] - p[4]) / (dt * 10), 1)}) + "\n")
    pt, p = t, c
