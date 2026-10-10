"""Bounded native-Linux observations. Python 3.6+, standard library only.
Database/MQ observation failures are unknown, NEVER an empty queue.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request


def write_json(path, value):
    path = Path(path)
    tmp = path.with_name(path.name + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.replace(str(tmp), str(path))


def mysql(sql, root=False):
    # Same local disposable database/user as native-linux's existing scripts.
    command = ['mysql', '--connect-timeout=3', '-N', '-B',
               '-uroot' if root else '-uminiseckill',
               '-proot' if root else '-pminiseckill', 'mini_seckill', '-e', sql]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            universal_newlines=True, timeout=8)
    if result.returncode:
        raise RuntimeError('mysql failed: ' + result.stderr[-1000:])
    return result.stdout


def queue():
    auth = base64.b64encode(b'guest:guest').decode('ascii')
    req = urllib.request.Request('http://localhost:15672/api/queues/%2F/mini.seckill.order.queue',
                                 headers={'Authorization': 'Basic ' + auth})
    with urllib.request.urlopen(req, timeout=3) as response:
        value = json.loads(response.read().decode())
    # Absent management counters during startup are not evidence of emptiness.
    return {k: int(value[k]) for k in ('messages_ready', 'messages_unacknowledged')}


def state():
    rec = {'started_at': time.time()}
    try:
        sql = ('SELECT COUNT(*),COALESCE(SUM(status NOT IN (2,6,7)),0),'
               'COALESCE(SUM(status=2),0),COALESCE(SUM(status=6),0),COALESCE(SUM(status=7),0) '
               'FROM seckill_message; '
               'SELECT COUNT(*),COALESCE(SUM(status=2),0) FROM seckill_order;')
        lines = mysql(sql).splitlines()
        values = [int(v) for v in lines[0].split() + lines[1].split()]
        keys = ('messages', 'nonterminal', 'consumed', 'timeout', 'dead', 'orders', 'success')
        if len(values) != len(keys):
            raise ValueError('incomplete SQL observation')
        rec['db'] = dict(zip(keys, values))
    except Exception as ex:
        rec['db_error'] = str(ex)
    try:
        rec['mq'] = queue()
    except Exception as ex:
        rec['mq_error'] = str(ex)
    rec['t'] = time.time()
    return rec


def empty(rec):
    return ('db' in rec and 'mq' in rec and rec['db']['nonterminal'] == 0
            and rec['mq']['messages_ready'] == 0 and rec['mq']['messages_unacknowledged'] == 0)


def drain(prefix, maximum, quiet=5.0):
    """Require >=5s stable DB totals AND zero pending/ready/unacked; bounded wall deadline."""
    deadline = time.monotonic() + maximum
    previous, since = None, None
    final = None
    with open(str(prefix) + '-drain.jsonl', 'w', buffering=1) as out:
        while time.monotonic() < deadline:
            final = state()
            out.write(json.dumps(final) + '\n')
            counts = final.get('db')
            if empty(final) and counts == previous:
                if since is not None and time.monotonic() - since >= quiet:
                    result = {'drained': True, 'finished_at': time.time(), 'final': final,
                              'quiet_seconds': quiet, 'deadline_seconds': maximum}
                    write_json(str(prefix) + '-drain.json', result)
                    return True
            else:
                since = time.monotonic() if empty(final) else None
            previous = counts if empty(final) else None
            time.sleep(min(1.0, max(0, deadline - time.monotonic())))
    write_json(str(prefix) + '-drain.json', {'drained': False, 'finished_at': time.time(),
               'final': final, 'quiet_seconds': quiet, 'deadline_seconds': maximum})
    return False


def final_snapshot(prefix):
    rec = state()
    try:
        sql = (
            'SELECT COUNT(*) FROM (SELECT activity_id,user_id,sku_id FROM seckill_order '
            'GROUP BY activity_id,user_id,sku_id HAVING COUNT(*)>1) d; '
            'SELECT COUNT(*)-COUNT(DISTINCT order_id) FROM seckill_order; '
            'SELECT COALESCE(SUM(available_stock),0) FROM sku_stock_segment; '
            'SELECT COALESCE(SUM(available_stock),0) FROM sku_stock;')
        values = [int(v) for v in mysql(sql).split()]
        if len(values) != 4:
            raise ValueError('incomplete final counters')
        rec['checks'] = dict(zip(('duplicate_groups', 'duplicate_order_ids',
                                 'segment_available', 'stock_available'), values))
        raw = mysql('SELECT status,COUNT(*) FROM seckill_message GROUP BY status; '
                    'SELECT request_id,status,retry_count,last_error FROM seckill_message '
                    'WHERE status<>2 ORDER BY id LIMIT 100;')
        Path(str(prefix) + '-message-states.txt').write_text(raw, encoding='utf-8')
    except Exception as ex:
        rec['checks_error'] = str(ex)
    write_json(str(prefix) + '-final.json', rec)


def diagnostics(out, pid):
    """Separate, labelled probe: scheduled vs actual capture times are both retained."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    for target in (15, 25):
        time.sleep(max(0, target - (time.monotonic() - start)))
        timing = {'target_seconds': target, 'started_at': time.time(),
                  'actual_elapsed_seconds': time.monotonic() - start}
        commands = {
            'jstack': ['jstack', str(pid)],
            'mysql': ['mysql', '--connect-timeout=3', '-uroot', '-proot', '-e',
                      "SHOW FULL PROCESSLIST; SHOW ENGINE INNODB STATUS\\G; "
                      "SHOW GLOBAL STATUS LIKE 'Threads_running'; "
                      "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'; "
                      "SHOW GLOBAL STATUS LIKE 'Innodb_log_waits'"],
            'top': ['top', '-b', '-n1', '-H', '-p', str(pid)]}
        for name, command in commands.items():
            with open(str(out / ('%s-%s.txt' % (name, target))), 'w') as dest:
                try:
                    result = subprocess.run(command, stdout=dest, stderr=subprocess.STDOUT, timeout=10)
                    timing[name + '_exit'] = result.returncode
                except Exception as ex:
                    dest.write(str(ex) + '\n')
                    timing[name + '_error'] = str(ex)
        timing['finished_at'] = time.time()
        write_json(out / ('capture-%s.json' % target), timing)



def reset_stopped(repo, root):
    """Remove only the existing benchmark fixture, with the JVM stopped first.
    Especially important after an undrained baseline: do not let a new listener
    race reset.sql on requeued old deliveries. Existing cap-step still does the
    normal stock init/warmup once the next JVM is healthy.
    """
    pidfile = Path(root) / 'run' / 'app.pid'
    if pidfile.exists():
        raise RuntimeError('refusing cold reset while app PID file exists')
    deadline = time.monotonic() + 20
    while True:
        observed = queue()
        if observed['messages_unacknowledged'] == 0:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError('unacked still present after app stop; do not reset live work')
        time.sleep(1)
    # Reuse the repository's existing benchmark reset command. This helper
    # adds a stop/unacked barrier, not a second implementation of destructive IO.
    subprocess.run(['bash', str(Path(repo) / 'benchmark' / 'reset-env.sh'), '--cold-only'],
                   check=True, timeout=60)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['drain', 'final', 'state', 'diagnostic', 'window', 'reset-stopped'])
    parser.add_argument('args', nargs='*')
    args = parser.parse_args()
    if args.mode == 'reset-stopped':
        reset_stopped(*args.args)
    if args.mode == 'drain':
        return 0 if drain(args.args[0], int(args.args[1])) else 20
    if args.mode == 'final':
        final_snapshot(args.args[0])
    if args.mode == 'state':
        write_json(args.args[0], state())
    if args.mode == 'diagnostic':
        diagnostics(args.args[0], int(args.args[1]))
    if args.mode == 'window':
        path, rate, duration, start, end, rc, stock = args.args
        write_json(path, {'rate': int(rate), 'duration': int(duration), 't0': float(start),
                         't1': float(end), 'k6_exit': int(rc), 'initial_stock': int(stock),
                         'load_window': 'launch t0 .. t0+60s; sample-based estimate'})
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
