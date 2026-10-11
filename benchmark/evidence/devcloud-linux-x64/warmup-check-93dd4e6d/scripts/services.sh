#!/usr/bin/env bash
# Native stand-ins for the docker-compose.yml services (mysql:8.0, redis:7, rabbitmq:3-management).
# Usage: services.sh start|stop|restart|status [mysql|redis|rabbitmq ...]
set -euo pipefail
. /data/ms/env.sh
R=/data/ms
REPO=$R/mini-seckill
mkdir -p "$R/run" "$R/logs" "$R/data"

MYSQLD=$R/opt/mysql-bin/mysqld   # copy without file capabilities; the dnf binary cannot exec in this container
MYDATA=$R/data/mysql
MYLOG=$R/logs/mysql              # mysqld runs as user mysql, so its log/pid dir must be mysql-owned
SOCK=/var/lib/mysql/mysql.sock    # default client socket, so `mysql -u... db` in benchmark scripts works unchanged

wait_for() { # seconds, description, command...
  local n=$1 what=$2; shift 2
  for _ in $(seq 1 "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  echo "timeout waiting for $what" >&2; return 1
}
port_up() { ss -lnt | grep -q ":$1 "; }

mysql_start() {
  port_up 3306 && { echo "mysql already running"; return; }
  local fresh=0
  mkdir -p "$MYLOG" && chown mysql:mysql "$MYLOG"
  if [ ! -d "$MYDATA/mysql" ]; then
    mkdir -p "$MYDATA" && chown -R mysql:mysql "$MYDATA"
    "$MYSQLD" --no-defaults --initialize-insecure --user=mysql --basedir=/usr --lc-messages-dir=/usr/share/mysql --datadir="$MYDATA" --log-error="$MYLOG/mysqld-init.err"
    fresh=1
  fi
  mkdir -p /var/lib/mysql && chown mysql:mysql /var/lib/mysql
  TZ=Asia/Shanghai nohup "$MYSQLD" --no-defaults --user=mysql --basedir=/usr --lc-messages-dir=/usr/share/mysql --datadir="$MYDATA" --socket="$SOCK" \
    --port=3306 --bind-address=127.0.0.1 --pid-file="$MYLOG/mysqld.pid" --log-error="$MYLOG/mysqld.err" \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci --max-connections=300 \
    >/dev/null 2>&1 &
  if [ $fresh = 1 ]; then
    wait_for 60 mysql mysqladmin --socket="$SOCK" -uroot ping
    mysql --socket="$SOCK" -uroot <<'SQL'
ALTER USER 'root'@'localhost' IDENTIFIED BY 'root';
CREATE DATABASE IF NOT EXISTS mini_seckill;
CREATE USER IF NOT EXISTS 'miniseckill'@'%' IDENTIFIED BY 'miniseckill';
CREATE USER IF NOT EXISTS 'miniseckill'@'localhost' IDENTIFIED BY 'miniseckill';
GRANT ALL ON mini_seckill.* TO 'miniseckill'@'%';
GRANT ALL ON mini_seckill.* TO 'miniseckill'@'localhost';
SQL
    mysql --socket="$SOCK" -uroot -proot mini_seckill < "$REPO/sql/init.sql"
  fi
  wait_for 60 mysql mysqladmin --socket="$SOCK" -uroot -proot ping
  echo "mysql started"
}
mysql_stop() {
  port_up 3306 || { echo "mysql not running"; return; }
  mysqladmin --socket="$SOCK" -uroot -proot shutdown
  echo "mysql stopped"
}

redis_start() {
  port_up 6379 && { echo "redis already running"; return; }
  mkdir -p "$R/data/redis"
  redis-server --port 6379 --bind 127.0.0.1 --daemonize yes --dir "$R/data/redis" \
    --pidfile "$R/run/redis.pid" --logfile "$R/logs/redis.log"
  wait_for 30 redis redis-cli ping
  echo "redis started"
}
redis_stop() {
  port_up 6379 || { echo "redis not running"; return; }
  redis-cli shutdown || true
  echo "redis stopped"
}

rabbit_env() {
  local etc=$R/opt/rabbitmq/etc/rabbitmq
  mkdir -p "$etc" "$R/data/rabbitmq" "$R/logs/rabbitmq"
  cat > "$etc/rabbitmq-env.conf" <<EOF
NODENAME=rabbit@localhost
MNESIA_BASE=$R/data/rabbitmq
LOG_BASE=$R/logs/rabbitmq
EOF
  [ -f "$etc/enabled_plugins" ] || rabbitmq-plugins enable --offline rabbitmq_management >/dev/null
}
rabbitmq_start() {
  port_up 5672 && { echo "rabbitmq already running"; return; }
  rabbit_env
  TZ=Asia/Shanghai rabbitmq-server -detached
  wait_for 90 rabbitmq rabbitmq-diagnostics -q check_port_connectivity
  echo "rabbitmq started"
}
rabbitmq_stop() {
  port_up 5672 || { echo "rabbitmq not running"; return; }
  rabbitmqctl -q shutdown
  echo "rabbitmq stopped"
}

status() {
  for p in mysql:3306 redis:6379 rabbitmq:5672; do
    if port_up "${p#*:}"; then echo "${p%%:*} up"; else echo "${p%%:*} down"; fi
  done
}

action=${1:-status}; shift || true
svcs=("$@"); [ ${#svcs[@]} -eq 0 ] && svcs=(mysql redis rabbitmq)
case "$action" in
  start)   for s in "${svcs[@]}"; do "${s}_start"; done ;;
  stop)    for s in "${svcs[@]}"; do "${s}_stop"; done ;;
  restart) for s in "${svcs[@]}"; do "${s}_stop"; "${s}_start"; done ;;
  status)  status ;;
  *) echo "usage: $0 start|stop|restart|status [mysql|redis|rabbitmq]" >&2; exit 2 ;;
esac
