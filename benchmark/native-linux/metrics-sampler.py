"""1 Hz side sampler for capacity retests: Hikari pool, admission stage timers, activity cache,
Tomcat threads (app Prometheus endpoint) and RabbitMQ queue depth (management HTTP API).

Runs for a whole A/B session, independent of cap-step.sh; align with a step by the 't' range of
that step's *-samples.jsonl. rabbitmqctl is avoided on purpose: each call costs ~9 s of CPU.
Metrics the running jar does not expose are simply absent from the record.

Usage: python3 metrics-sampler.py <out.jsonl>
"""
import base64
import json
import re
import sys
import time
import urllib.request

APP = "http://localhost:18080/actuator/prometheus"
MQ = "http://localhost:15672/api/queues/%2F/mini.seckill.order.queue"
KEEP = re.compile(r"^(hikaricp_connections(_pending|_active|_idle|_timeout_total"
                  r"|_acquire_seconds_(count|sum|max)|_usage_seconds_(count|sum|max))"
                  r"|seckill_capacity_stage_seconds_(count|sum|max)"
                  r"|seckill_activity_cache_total"
                  r"|tomcat_threads_(busy|current)_threads"
                  r"|jvm_threads_live_threads)\b")
AUTH = "Basic " + base64.b64encode(b"guest:guest").decode()


def scrape_app():
    out = {}
    with urllib.request.urlopen(APP, timeout=2) as r:
        for line in r.read().decode().splitlines():
            if not KEEP.match(line):
                continue
            name, _, value = line.rpartition(" ")
            name = re.sub(r'application="[^"]*",?|pool="[^"]*",?', "", name).replace("{}", "")
            out[name] = float(value)
    return out


def scrape_mq():
    req = urllib.request.Request(MQ, headers={"Authorization": AUTH})
    with urllib.request.urlopen(req, timeout=2) as r:
        q = json.loads(r.read().decode())
    return {"ready": q.get("messages_ready"), "unacked": q.get("messages_unacknowledged"),
            "publish_rate": q.get("message_stats", {}).get("publish_details", {}).get("rate"),
            "ack_rate": q.get("message_stats", {}).get("ack_details", {}).get("rate")}


def main():
    out = open(sys.argv[1], "a", buffering=1)
    while True:
        rec = {"t": round(time.time(), 3)}
        try:
            rec["app"] = scrape_app()
        except Exception as ex:  # app restarting between variants
            rec["app_err"] = type(ex).__name__
        try:
            rec["mq"] = scrape_mq()
        except Exception as ex:
            rec["mq_err"] = type(ex).__name__
        out.write(json.dumps(rec) + "\n")
        time.sleep(max(0.0, 1.0 - (time.time() - rec["t"])))


if __name__ == "__main__":
    main()
