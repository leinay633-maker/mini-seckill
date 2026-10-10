"""Classify Tomcat worker threads in jstack dumps: Hikari borrow by call site, idle in pool, other.
Usage: python3 jstack-classify.py <jstack.txt[.gz]> ..."""
import collections, gzip, sys
for path in sys.argv[1:]:
    text = (gzip.open(path, "rt") if path.endswith(".gz") else open(path)).read()
    c = collections.Counter()
    for block in text.split("\n\n"):
        if '"http-nio-' not in block or "exec-" not in block:
            continue
        if "ConcurrentBag.borrow" in block:
            if "assertRunning" in block:
                c["hikari:activity"] += 1
            elif "hasAcceptedOrFinishedOrder" in block:
                c["hikari:token_order_lookup"] += 1
            elif "SeckillProducer" in block:
                c["hikari:producer_send"] += 1
            elif "placeOrder" in block:
                c["hikari:message_insert"] += 1
            else:
                c["hikari:other"] += 1
        elif "TaskQueue" in block or "LinkedBlockingQueue.poll" in block:
            c["idle_in_pool"] += 1
        elif "socketRead" in block or "Net.poll" in block or "SocketDispatcher.read" in block:
            c["executing_io(sql/redis)"] += 1
        else:
            c["other"] += 1
    print(path, sum(c.values()), dict(sorted(c.items())))
