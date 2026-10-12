"""Every 2 s: RabbitMQ channel/connection churn totals from the management API (Python 3.6+, stdlib).
Usage: python3 churn-sampler.py <out.jsonl>
"""
import base64
import json
import sys
import time
import urllib.request

AUTH = 'Basic ' + base64.b64encode(b'guest:guest').decode()
with open(sys.argv[1], 'a', buffering=1) as out:
    while True:
        t = time.time()
        try:
            req = urllib.request.Request('http://localhost:15672/api/overview', headers={'Authorization': AUTH})
            with urllib.request.urlopen(req, timeout=2) as response:
                o = json.loads(response.read().decode())
            c = o.get('churn_rates', {})
            out.write(json.dumps({'t': round(t, 3), 'channel_created': c.get('channel_created'),
                                  'channel_closed': c.get('channel_closed'),
                                  'connection_created': c.get('connection_created'),
                                  'channels': o.get('object_totals', {}).get('channels'),
                                  'connections': o.get('object_totals', {}).get('connections')}) + '\n')
        except Exception as ex:
            out.write(json.dumps({'t': round(t, 3), 'err': type(ex).__name__}) + '\n')
        time.sleep(max(0.0, 2.0 - (time.time() - t)))
