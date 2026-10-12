"""1 Hz Prometheus + MQ sampler; Python 3.6+.
'app' retains aggregate keys for older reports; 'pools' preserves physical-pool
labels. Sum connection counts/counter deltas, not mean per-pool latencies.
"""
import base64
import json
import os
import re
import sys
import time
import urllib.request

APP = 'http://localhost:18080/actuator/prometheus'
# Several instances (comma-separated base URLs): counters are summed and pools are labelled by port.
APPS = [u for u in os.environ.get('MS_APP_URLS', '').split(',') if u]
MQ = 'http://localhost:15672/api/queues/%2F/mini.seckill.order.queue'
KEEP = re.compile(r'^(hikaricp_connections(?:_[a-z_]+)?|jdbc_connections_[a-z_]+'
                  r'|seckill_capacity_stage_seconds_(?:count|sum|max)|seckill_activity_cache_total'
                  r'|seckill_mq_total|seckill_order_total|tomcat_threads_(?:busy|current)_threads'
                  r'|jvm_threads_live_threads)\b')
AUTH = 'Basic ' + base64.b64encode(b'guest:guest').decode()
LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')


def parse_prometheus(text):
    app, pools = {}, {}
    for line in text.splitlines():
        if not KEEP.match(line):
            continue
        sample, _, value = line.rpartition(' ')
        value = float(value)
        metric = sample.split('{', 1)[0]
        labels = dict(LABEL.findall(sample))
        labels.pop('application', None)
        pool = labels.pop('pool', None)
        if pool is not None and metric.startswith('hikaricp_'):
            pools.setdefault(pool, {})[metric] = value
        suffix = ('{' + ','.join('%s="%s"' % item for item in sorted(labels.items())) + '}') if labels else ''
        key = metric + suffix
        if pool is None:
            app[key] = value
        elif metric.endswith('_seconds_max'):
            app[key] = max(app.get(key, 0), value)
        else:
            app[key] = app.get(key, 0) + value
    return {'app': app, 'pools': pools}


def merge_instances(scraped):
    """scraped: [(port, parse_prometheus result)] from the instances that answered."""
    app, pools = {}, {}
    for port, one in scraped:
        for name, values in one['pools'].items():
            pools[port if len(one['pools']) == 1 else port + '/' + name] = values
        for key, value in one['app'].items():
            if key.split('{', 1)[0].endswith('_seconds_max'):
                app[key] = max(app.get(key, 0), value)
            else:
                app[key] = app.get(key, 0) + value
    return {'app': app, 'pools': pools, 'instances': len(scraped)}


def scrape_app():
    if not APPS:
        with urllib.request.urlopen(APP, timeout=2) as response:
            return parse_prometheus(response.read().decode())
    scraped = []
    for base in APPS:
        try:
            with urllib.request.urlopen(base.rstrip('/') + '/actuator/prometheus', timeout=2) as response:
                scraped.append((base.rstrip('/').rsplit(':', 1)[-1], parse_prometheus(response.read().decode())))
        except Exception:
            continue  # an instance that is not running in this case
    if not scraped:
        raise RuntimeError('no app instance answered')
    return merge_instances(scraped)


def scrape_mq():
    req = urllib.request.Request(MQ, headers={'Authorization': AUTH})
    with urllib.request.urlopen(req, timeout=2) as response:
        q = json.loads(response.read().decode())
    return {'ready': q.get('messages_ready'), 'unacked': q.get('messages_unacknowledged'),
            'publish_rate': q.get('message_stats', {}).get('publish_details', {}).get('rate'),
            'ack_rate': q.get('message_stats', {}).get('ack_details', {}).get('rate')}


def main():
    with open(sys.argv[1], 'a', buffering=1) as out:
        while True:
            started = time.monotonic()
            rec = {'schema': 2, 'started_at': round(time.time(), 3)}
            try:
                rec.update(scrape_app())
                rec['app_t'] = time.time()
            except Exception as ex:
                rec['app_err'] = type(ex).__name__
            try:
                rec['mq'] = scrape_mq()
                rec['mq_t'] = time.time()
            except Exception as ex:
                rec['mq_err'] = type(ex).__name__
            rec['t'] = round(time.time(), 3)
            out.write(json.dumps(rec) + '\n')
            time.sleep(max(0.0, 1.0 - (time.monotonic() - started)))


if __name__ == '__main__':
    main()
