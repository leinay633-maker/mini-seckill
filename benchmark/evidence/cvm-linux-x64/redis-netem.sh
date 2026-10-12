#!/usr/bin/env bash
# Delay only Redis traffic on loopback (both directions of port 6379), to imitate Redis on another host.
# Usage: redis-netem.sh on <one-way-delay, e.g. 250us> | off | show
set -euo pipefail
case "${1:-show}" in
  on)
    command -v tc >/dev/null || dnf -y -q install iproute-tc
    modprobe sch_netem 2>/dev/null || true
    tc qdisc del dev lo root 2>/dev/null || true
    # Band 4 only receives the filtered Redis packets; everything else keeps the default prio bands.
    tc qdisc add dev lo root handle 1: prio bands 4 priomap 1 2 2 2 1 2 0 0 1 1 1 1 1 1 1 1
    tc qdisc add dev lo parent 1:4 handle 40: netem delay "$2"
    tc filter add dev lo parent 1:0 protocol ip prio 1 u32 match ip dport 6379 0xffff flowid 1:4
    tc filter add dev lo parent 1:0 protocol ip prio 1 u32 match ip sport 6379 0xffff flowid 1:4
    ;;
  off) tc qdisc del dev lo root 2>/dev/null || true ;;
  show) ;;
  *) echo "usage: $0 on <delay> | off | show" >&2; exit 2 ;;
esac
tc qdisc show dev lo
tc filter show dev lo 2>/dev/null | head -20
