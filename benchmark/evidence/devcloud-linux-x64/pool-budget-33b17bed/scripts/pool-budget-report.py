"""Reproducible full-population report. Missing evidence stays unknown; never filter bad runs.
60s success is a sampling estimate (launch window), not a long-run capacity claim.
"""
import argparse
import hashlib
import json
from pathlib import Path


def load(path, fallback=None):
    try:
        return json.loads(Path(path).read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return fallback


def jsonl(path):
    records = []
    try:
        with open(str(path), encoding='utf-8') as stream:
            for line in stream:
                try:
                    records.append(json.loads(line))
                except ValueError:
                    pass  # crash-truncated lines remain in the original file
    except OSError:
        pass
    return records


def disk_window(records, start, duration):
    window = [r for r in records if start <= r.get('t', 0) < start + duration]
    # Count observed wall-second buckets, not lines (duplicate samples must not inflate coverage).
    buckets = {}
    for rec in window:
        bucket = int(rec['t'] - start)
        if isinstance(rec.get('w_await_ms'), (int, float)):
            buckets[bucket] = max(buckets.get(bucket, 0), rec['w_await_ms'])
    coverage = len(buckets) / duration
    stalls = sum(v > 2 for v in buckets.values())
    return {'coverage': round(coverage, 3), 'stall_seconds': stalls,
            'class': 'unknown' if coverage < .9 else ('noisy' if stalls >= 5 else 'low-stall')}


def admission_pass(metrics, exit_code):
    def val(key, field):
        return metrics.get(key, {}).get('values', {}).get(field)
    iters, errors = val('iterations', 'count'), val('system_error_rate', 'rate')
    dropped = val('dropped_iterations', 'count') or 0
    if iters is None or errors is None or exit_code not in (0, 99):
        return None
    if iters <= 0:
        return False
    return errors <= .01 and dropped / (iters + dropped) <= .01


def correctness(final, drain, queued, initial_stock):
    if not final or not drain or queued is None or initial_stock is None:
        return None
    if any(k not in final for k in ('db', 'mq', 'checks')):
        return None
    d, m, c = final['db'], final['mq'], final['checks']
    return (drain.get('drained') is True and d['nonterminal'] == 0 and d['timeout'] == 0
            and d['dead'] == 0 and d['messages'] == d['consumed'] == d['success'] == d['orders']
            and d['success'] >= queued and c['duplicate_groups'] == c['duplicate_order_ids'] == 0
            and m['messages_ready'] == m['messages_unacknowledged'] == 0
            and c['segment_available'] >= 0 and initial_stock - c['segment_available'] == d['success'])


def pool_statistics(records, expected):
    result = {}
    def mean(vals):
        return round(sum(vals) / len(vals), 3) if vals else None
    names = set(p for r in records for p in r.get('pools', {}))
    for name in sorted(names):
        points = [r['pools'][name] for r in records if name in r.get('pools', {})]
        row = {}
        for gauge in ('active', 'pending', 'idle', 'max'):
            vals = [p['hikaricp_connections_' + gauge] for p in points
                    if 'hikaricp_connections_' + gauge in p]
            row[gauge + '_mean'] = mean(vals)
            row[gauge + '_peak'] = max(vals) if vals else None
        for timer in ('acquire', 'usage'):
            ck, sk = ('hikaricp_connections_%s_seconds_%s' % (timer, f) for f in ('count', 'sum'))
            valid = [p for p in points if ck in p and sk in p]
            if len(valid) >= 2 and valid[-1][ck] >= valid[0][ck] and valid[-1][sk] >= valid[0][sk]:
                count, seconds = valid[-1][ck] - valid[0][ck], valid[-1][sk] - valid[0][sk]
                row[timer + '_count_delta'] = count
                row[timer + '_ms'] = round(seconds * 1000 / count, 3) if count else None
            else:
                row[timer + '_count_delta'] = row[timer + '_ms'] = None
        vals = [p['hikaricp_connections_timeout_total'] for p in points if 'hikaricp_connections_timeout_total' in p]
        row['timeout_delta'] = vals[-1] - vals[0] if len(vals) >= 2 and vals[-1] >= vals[0] else None
        result[name] = row
    observations = [{p: v['hikaricp_connections_max'] for p, v in r.get('pools', {}).items()
                     if 'hikaricp_connections_max' in v} for r in records]
    complete = [v for v in observations if len(v) == len(expected)]
    # Split uses stable explicit pool names; shared Boot names can vary by JVM.
    valid = None
    if any(len(v) > len(expected) for v in observations):
        valid = False
    elif complete:
        valid = all((v == expected if len(expected) > 1 else list(v.values()) == [40])
                    for v in complete) and not any(len(v) > len(expected) for v in observations)
    return result, valid


def summarize(root):
    root = Path(root)
    manifest = load(root / 'manifest.json', {})
    disk = jsonl(root / 'disk.jsonl')
    metrics = jsonl(root / 'metrics.jsonl')
    rows = []
    for case in manifest.get('cases', []):
        folder = root / case['label']
        for rate in case['rates']:
            prefix = folder / ('%s-r%s' % (case['label'], rate))
            window = load(str(prefix) + '-window.json')
            row = {'case': case['label'], 'phase': case['phase'], 'variant': case['variant'],
                   'round': case['round'], 'rate': rate, 'case_exit': case.get('exit'),
                   'status': 'not-run', 'admission_pass': None, 'correctness': None}
            if window:
                t0, duration = window['t0'], window['duration']
                summary = load(str(prefix) + '-summary.json', {}).get('metrics', {})
                val = lambda k, f: summary.get(k, {}).get('values', {}).get(f)
                samples = [s for s in jsonl(str(prefix) + '-samples.jsonl') if t0 <= s['t'] < t0 + duration]
                success = [s['success'] for s in samples if s.get('success') is not None]
                observations = [r for r in metrics if t0 <= r['t'] < t0 + duration]
                expected = {'admission': 12, 'consumer': 28} if case['variant'] == 'split28' else (
                    {'admission': 20, 'consumer': 20} if case['variant'] == 'split20' else {'shared': 40})
                pools, budget = pool_statistics(observations, expected)
                final, drained = load(str(prefix) + '-final.json'), load(str(prefix) + '-drain.json')
                before = load(str(prefix) + '-before.json', {}).get('db', {}).get('success')
                observed_seconds = len(set(int(s['t'] - t0) for s in samples if s.get('success') is not None))
                success_rate = round((max(success) - before) / duration, 2) if success and before == 0 and observed_seconds / duration >= .9 else None
                mq = [r['mq'] for r in observations if r.get('mq', {}).get('ready') is not None]
                row.update({'status': 'observed', 'k6_exit': window['k6_exit'], 'iters': val('iterations', 'count'),
                            'dropped': val('dropped_iterations', 'count') or 0,
                            'sys_err': val('system_error_rate', 'rate'), 'queued': val('order_queued', 'count'),
                            'http_p99_ms': val('http_req_duration', 'p(99)'),
                            'success_per_s_60s_estimate': success_rate,
                            'success_sample_coverage': round(observed_seconds / duration, 3),
                            'drained': drained.get('drained') if drained else None,
                            'drain_observed_tail_s': round(drained['finished_at'] - window['t1'], 2) if drained else None,
                            'mq_ready_peak': max(r['ready'] for r in mq) if mq else None,
                            'disk': disk_window(disk, t0, duration), 'pools': pools, 'pool_budget_valid': budget,
                            'admission_pass': admission_pass(summary, window['k6_exit']),
                            'correctness': correctness(final, drained, val('order_queued', 'count'), window['initial_stock']),
                            'final': final, 'window': window})
            rows.append(row)
    return rows


def write_report(root):
    root = Path(root)
    rows = summarize(root)
    (root / 'summary.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    text = ['# Consumer/pool-budget retest — all planned cells', '',
            'No performance conclusion is pre-filled. Diagnostic rows include jstack overhead and must not be pooled with A/B.',
            '60s SUCCESS/s is a sampling estimate; not long-run sustainable TPS. Drain tail includes a 5s quiescence guard.',
            'Disk grouping was fixed before this run: write await >2ms in >=5 observed seconds = noisy; <90% coverage = unknown.',
            'No row is excluded. Null means missing/unknown, not zero. Full pool/disk/final-state evidence is in summary.json.', '',
            '| Case | Rate | Status | Admission | Correctness | 40-budget | p99 ms | SUCCESS/s estimate | Drain tail s | Disk |',
            '|---|---:|---|---|---|---|---:|---:|---:|---|']
    for r in rows:
        text.append('| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |' % (
            r['case'], r['rate'], r['status'], r.get('admission_pass'), r.get('correctness'),
            r.get('pool_budget_valid'), r.get('http_p99_ms'), r.get('success_per_s_60s_estimate'),
            r.get('drain_observed_tail_s'), r.get('disk', {}).get('class', 'unknown')))
    (root / 'REPORT.md').write_text('\n'.join(text) + '\n', encoding='utf-8')
    return rows


def hash_manifest(root):
    root = Path(root)
    values = {}
    for path in sorted(root.rglob('*')):
        if path.is_file() and path != root / 'SHA256SUMS.json':
            h = hashlib.sha256()
            with path.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    h.update(block)
            values[str(path.relative_to(root))] = h.hexdigest()
    (root / 'SHA256SUMS.json').write_text(json.dumps(values, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('root')
    args = parser.parse_args()
    write_report(args.root)
    hash_manifest(args.root)
