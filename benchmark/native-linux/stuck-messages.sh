#!/usr/bin/env bash
# Dump non-CONSUMED messages and their app-log history.
. /data/ms/env.sh
q() { mysql -N -B -uminiseckill -pminiseckill mini_seckill -e "$1" 2>/dev/null; }
mysql -uminiseckill -pminiseckill mini_seckill -e "show columns from seckill_message" 2>/dev/null | cut -f1 | tr '\n' ' '; echo
mysql -uminiseckill -pminiseckill mini_seckill -e "select * from seckill_message where status<>2 order by id" 2>/dev/null
for r in $(q "select request_id from seckill_message where status<>2"); do
  echo "=== $r"
  grep -F "$r" /data/ms/logs/app.log | cut -c12-23,60-400
  q "select count(*) from seckill_order where user_id=(select user_id from seckill_message where request_id='$r')"
done
q "select type,status,count(*) from seckill_compensation group by type,status" 2>/dev/null || true
