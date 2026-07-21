#!/usr/bin/env bash
set -euo pipefail

NODE="${1:-mini-seckill-redis-cluster-7000}"
PEER="${2:-mini-seckill-redis-cluster-7001}"

docker start "$NODE"
printf 'Redis Cluster 节点已启动: %s\n' "$NODE"
printf '等待节点重新加入 10 秒...\n'
sleep 10
docker exec "$PEER" redis-cli -p 7001 cluster info
docker exec "$PEER" redis-cli -p 7001 cluster nodes
