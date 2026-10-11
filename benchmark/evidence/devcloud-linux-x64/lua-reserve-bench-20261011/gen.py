"""Emit a RESP command stream for one reserve-script micro-benchmark (Python 3.6, stdlib only).

Usage: python3 gen.py <coord|sharded> <sha> <calls> <seed>
Key names and the start bucket follow RedisKeyUtil and InventoryCoordinator (activity 1, sku 1001,
128 buckets). Each coordination call gets a fresh user whose owner key holds the requestId, and the
in-flight entry is removed afterwards so the hash and ZSET stay small, as with settled reservations.
"""
import random
import sys
import uuid

A, S, N = 1, 1001, 128
BUCKETS = ['seckill:stock:%d:%d:bucket:%d' % (A, S, i) for i in range(N)]
TOTAL = 'seckill:stock:%d:%d' % (A, S)
VERSION = 'seckill:coord:%d:%d:version' % (A, S)
INFLIGHT = 'seckill:coord:%d:%d:inflight' % (A, S)
DEADLINES = 'seckill:coord:%d:%d:deadlines' % (A, S)
LEASE = 'seckill:lock:reconcile:%d:%d' % (A, S)


def resp(*args):
    out = ['*%d\r\n' % len(args)]
    for arg in args:
        data = str(arg)
        out.append('$%d\r\n%s\r\n' % (len(data.encode()), data))
    return ''.join(out)


def main(kind, sha, calls, seed):
    rnd = random.Random(int(seed))
    write = sys.stdout.write
    for _ in range(int(calls)):
        user = rnd.randrange(1, 2 ** 31)
        start = user % N  # Long.hashCode of a value below 2^31 is the value itself
        if kind == 'sharded':
            write(resp('EVALSHA', sha, N, *(BUCKETS + [start])))
            continue
        rid = str(uuid.UUID(int=rnd.getrandbits(128)))
        owner = 'seckill:user:%d:%d:sku:%d' % (A, user, S)
        write(resp('SET', owner, rid))
        write(resp('EVALSHA', sha, 6 + N, TOTAL, VERSION, INFLIGHT, DEADLINES, LEASE, owner,
                   *(BUCKETS + [rid, user, 600000, '%d:%s' % (N, rid), start])))
        write(resp('HDEL', INFLIGHT, rid))
        write(resp('ZREM', DEADLINES, rid))
        write(resp('DEL', owner))


if __name__ == '__main__':
    main(*sys.argv[1:])
