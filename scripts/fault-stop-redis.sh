#!/usr/bin/env bash
set -euo pipefail

CONTAINER="${1:-mini-seckill-redis}"
docker stop "$CONTAINER"
printf 'Redis 容器已停止: %s\n' "$CONTAINER"
printf '等待应用健康检查进入恢复态后,再运行 recovery-check.sh。\n'
