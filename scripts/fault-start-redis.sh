#!/usr/bin/env bash
set -euo pipefail

CONTAINER="${1:-mini-seckill-redis}"
docker start "$CONTAINER"
printf 'Redis 容器已启动: %s\n' "$CONTAINER"
printf '请确认应用已进入恢复态后运行 recovery-check.sh 重建库存。\n'
