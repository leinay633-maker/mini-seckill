"""Count threads blocked in Hikari ConcurrentBag.borrow by thread-name family.
Usage: python3 jstack-borrowers.py <jstack.txt[.gz]> ..."""
import collections, gzip, re, sys
for path in sys.argv[1:]:
    text = (gzip.open(path, "rt") if path.endswith(".gz") else open(path)).read()
    c = collections.Counter()
    for block in text.split("\n\n"):
        if "ConcurrentBag.borrow" not in block:
            continue
        m = re.match(r'"([^"]+)"', block.strip())
        name = m.group(1) if m else "?"
        fam = ("tomcat" if "http-nio" in name else "mq-consumer" if "ntContainer" in name
               else "rabbit-confirm" if "AMQP" in name or "rabbit" in name.lower() else re.sub(r"[-#]?\d+$", "", name))
        c[fam] += 1
    print(path, dict(c))
