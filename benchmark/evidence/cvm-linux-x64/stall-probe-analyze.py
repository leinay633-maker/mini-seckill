"""Episodes where every pool connection is busy (10 Hz) next to MySQL, OS, JVM safepoint and access-log signals.

Usage: python -I stall-probe-analyze.py <probe-dir>
"""
import datetime
import glob
import json
import re
import sys
from pathlib import Path

SAFEPOINT = re.compile(r'^\[([^\]]+)\].*Safepoint "([^"]+)", Time since last: \d+ ns, Reaching safepoint: (\d+) ns, '
                       r'(?:Cleanup: \d+ ns, )?At safepoint: (\d+) ns, Total: (\d+) ns')
PAUSE = re.compile(r'^\[([^\]]+)\].*GC\(\d+\) (Pause [^0-9]*?)\s.*?([0-9.]+)ms$')


def stamp(text):
    return datetime.datetime.strptime(text, '%Y-%m-%dT%H:%M:%S.%f%z').timestamp()


def main(root):
    root = Path(root)
    pool = [json.loads(l) for l in (root / 'pool-10hz.jsonl').read_text().splitlines() if l.strip()]
    pool = [p for p in pool if 'pending' in p]
    win = json.loads(next(root.glob('*-step/*-r*-window.json')).read_text())
    t0, t1 = win['t0'], win['t1']
    inside = [p for p in pool if t0 <= p['t'] <= t1]
    # MySQL 10 Hz
    head, *rows = (root / 'mysql-10hz.tsv').read_text().splitlines()
    cols = head.split()
    my = []
    for r in rows:
        f = r.split('\t')
        if len(f) != len(cols):
            continue
        my.append({c: (float(v) if v not in ('NULL', '') else None) for c, v in zip(cols, f)})
    osr = [json.loads(l) for l in (root / 'os-10hz.jsonl').read_text().splitlines() if l.strip()]
    sps, pauses = [], []
    for path in root.glob('jvm-*.log'):
        for line in path.read_text(errors='replace').splitlines():
            m = SAFEPOINT.match(line)
            if m:
                sps.append((stamp(m.group(1)), m.group(2), int(m.group(3)) / 1e6, int(m.group(4)) / 1e6, int(m.group(5)) / 1e6))
            m = PAUSE.match(line)
            if m:
                pauses.append((stamp(m.group(1)), m.group(2).strip(), float(m.group(3))))
    acc = []
    for path in root.glob('access*'):
        for line in path.read_text(errors='replace').splitlines():
            f = line.split()
            if len(f) >= 4 and f[0].isdigit():
                acc.append((int(f[0]) / 1000.0, int(f[1]) / 1000.0, f[3]))
    acc.sort()
    print('window %.0fs pool samples=%d mysql=%d os=%d safepoints=%d gc_pauses=%d access=%d' % (
        t1 - t0, len(inside), len(my), len(osr), len(sps), len(pauses), len(acc)))
    if sps:
        worst = sorted(s for s in sps if t0 <= s[0] <= t1)
        reach = sorted(worst, key=lambda s: -s[2])[:5]
        tot = sorted(worst, key=lambda s: -s[4])[:5]
        print('top reaching-safepoint ms:', [(round(s[0] - t0, 1), s[1], round(s[2], 1)) for s in reach])
        print('top total-safepoint ms:', [(round(s[0] - t0, 1), s[1], round(s[4], 1)) for s in tot])
    # episodes: all connections busy with waiters
    eps, cur = [], None
    for p in inside:
        busy = p['pending'] > 0 and p['active'] >= p['total']
        if busy and cur is None:
            cur = [p['t'], p['t'], p['pending'], p['done'] - p['t']]
        elif busy:
            cur[1] = p['t']; cur[2] = max(cur[2], p['pending']); cur[3] = max(cur[3], p['done'] - p['t'])
        elif cur is not None:
            eps.append(cur); cur = None
    if cur:
        eps.append(cur)
    scrape = sorted(inside, key=lambda p: -(p['done'] - p['t']))[:5]
    print('slowest actuator scrapes (s into window, ms):', [(round(p['t'] - t0, 1), round(1000 * (p['done'] - p['t']))) for p in scrape])
    print('episodes (all busy + waiters): %d' % len(eps))

    def nearest(series, t):
        return min(series, key=lambda r: abs(r['t'] - t)) if series else None

    for start, end, peak, slow in eps:
        lo, hi = start - 1.0, end + 1.0
        m0, m1 = nearest(my, lo), nearest(my, hi)
        mw = [r for r in my if lo <= r['t'] <= hi]
        o0, o1 = nearest(osr, lo), nearest(osr, hi)
        def d(key, sub):
            return (o1[key][sub] - o0[key][sub]) / 1000.0 if o0 and o1 else None
        near_sp = [(round(s[0] - start, 2), s[1], round(s[2], 1), round(s[4], 1)) for s in sps if lo <= s[0] <= hi and s[4] > 5]
        near_gc = [(round(p[0] - start, 2), round(p[2], 1)) for p in pauses if lo <= p[0] <= hi]
        begun = [a for a in acc if start - 0.5 <= a[0] < end + 0.5]
        spans = [a for a in acc if a[0] < start - 0.05 and a[1] > start]
        long_spans = sorted((round(1000 * (a[1] - a[0])) for a in spans), reverse=True)[:3]
        per50 = {}
        for a in acc:
            if start - 1.0 <= a[0] < end + 1.0:
                k = int((a[0] - start) // 0.05)
                per50[k] = per50.get(k, 0) + 1
        burst = max(per50.items(), key=lambda kv: kv[1]) if per50 else None
        print('\n+%.1fs..+%.1fs peak_pending=%d scrape_ms=%d' % (start - t0, end - t0, peak, 1000 * slow))
        if mw:
            print('  mysql threads_running max=%s row_lock_waits max=%s dirty %s->%s ckpt_age max=%s (async %s) '
                  'Δwait_free=%s Δlog_waits=%s Δsync_flush_waits=%s' % (
                      max(r['threads_running'] for r in mw), max(r['row_lock_waits'] for r in mw),
                      m0['pages_dirty'], m1['pages_dirty'], max(r['ckpt_age'] for r in mw), m0['max_age_async'],
                      m1['wait_free'] - m0['wait_free'], m1['log_waits'] - m0['log_waits'],
                      m1['sync_flush_waits'] - m0['sync_flush_waits']))
        print('  PSI ms over ±1s: cpu some=%s mem some=%s full=%s io some=%s' % (
            d('cpu', 'some'), d('memory', 'some'), d('memory', 'full'), d('io', 'some')))
        print('  safepoints>5ms (dt, op, reach_ms, total_ms):', near_sp, ' gc:', near_gc)
        print('  requests begun ±0.5s=%d, busiest 50ms slot=%s, longest spans across start (ms)=%s' % (
            len(begun), (round(burst[0] * 0.05, 2), burst[1]) if burst else None, long_spans))


if __name__ == '__main__':
    main(sys.argv[1])
