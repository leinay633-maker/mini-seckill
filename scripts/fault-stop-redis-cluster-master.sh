#!/usr/bin/env bash
set -euo pipefail

NODE="${1:-mini-seckill-redis-cluster-7000}"
PEER="${2:-mini-seckill-redis-cluster-7001}"

docker stop "$NODE"
printf 'Redis Cluster 节点已停止: %s\n' "$NODE"
printf '等待故障转移 20 秒...\n'
sleep 20
docker exec "$PEER" redis-cli -p 7001 cluster info
docker exec "$PEER" redis-cli -p 7001 cluster nodes
