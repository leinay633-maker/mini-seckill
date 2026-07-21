#!/usr/bin/env bash
set -euo pipefail

NODE="${1:-mini-seckill-redis-cluster-7000}"
BASE_URL="${2:-http://localhost:8090}"

echo 'Redis Cluster info:'
docker exec "$NODE" redis-cli -p 7000 cluster info
echo 'Redis Cluster nodes:'
docker exec "$NODE" redis-cli -p 7000 cluster nodes
echo 'Cluster app recovery status:'
curl -fsS "$BASE_URL/api/recovery/redis/status"
printf '\n'
