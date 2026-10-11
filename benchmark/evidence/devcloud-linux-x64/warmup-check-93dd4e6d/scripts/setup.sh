#!/usr/bin/env bash
# Install JDK 17, Maven, k6, MySQL 8.0, Redis 7, RabbitMQ 3.13 under /data/ms (no Docker available).
set -euo pipefail
R=/data/ms
mkdir -p "$R"/{opt,dl,src}
cd "$R/dl"
log() { echo "[$(date '+%F %T')] $*"; }
gh_asset() { # repo, grep pattern -> first matching browser_download_url
  curl -fsSL "https://api.github.com/repos/$1/releases?per_page=30" | grep -o '"browser_download_url": *"[^"]*"' | cut -d'"' -f4 | grep -E "$2" | head -1
}

if [ ! -x "$R/opt/jdk17/bin/java" ]; then
  log "JDK 17"
  curl -fsSL -o jdk.tgz 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk'
  mkdir -p "$R/opt/jdk17" && tar xzf jdk.tgz -C "$R/opt/jdk17" --strip-components=1
fi

if [ ! -x "$R/opt/maven/bin/mvn" ]; then
  log "Maven 3.9.9"
  curl -fsSL -o maven.tgz https://mirrors.tencent.com/nexus/repository/maven-public/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz
  mkdir -p "$R/opt/maven" && tar xzf maven.tgz -C "$R/opt/maven" --strip-components=1
fi
mkdir -p ~/.m2
if [ ! -f ~/.m2/settings.xml ]; then
  cat > ~/.m2/settings.xml <<'EOF'
<settings><mirrors><mirror><id>tencent</id><mirrorOf>central</mirrorOf>
<url>https://mirrors.tencent.com/nexus/repository/maven-public/</url></mirror></mirrors></settings>
EOF
fi

if [ ! -x "$R/opt/k6/k6" ]; then
  log "k6"
  url=https://github.com/grafana/k6/releases/download/v2.3.0/k6-v2.3.0-linux-amd64.tar.gz
  curl -fsSL -o k6.tgz "$url"
  mkdir -p "$R/opt/k6" && tar xzf k6.tgz -C "$R/opt/k6" --strip-components=1
fi

if ! command -v mysqld >/dev/null; then
  log "MySQL 8.0 (dnf)"
  dnf -y -q install mysql-server mysql
fi

if [ ! -x "$R/opt/redis/bin/redis-server" ]; then
  log "Redis 7.2 (source build)"
  command -v gcc >/dev/null || dnf -y -q install gcc make
  curl -fsSL -o redis.tgz https://github.com/redis/redis/archive/refs/tags/7.2.7.tar.gz
  rm -rf "$R/src/redis" && mkdir -p "$R/src/redis" && tar xzf redis.tgz -C "$R/src/redis" --strip-components=1
  make -s -C "$R/src/redis" -j16 BUILD_TLS=no >/dev/null
  make -s -C "$R/src/redis" PREFIX="$R/opt/redis" install >/dev/null
fi

if ! command -v erl >/dev/null; then
  log "Erlang 26 (rabbitmq/erlang-rpm, el8)"
  url=https://github.com/rabbitmq/erlang-rpm/releases/download/v26.2.5.19/erlang-26.2.5.19-1.el8.x86_64.rpm
  curl -fsSL -o erlang.rpm "$url"
  dnf -y -q install ./erlang.rpm
fi

if [ ! -x "$R/opt/rabbitmq/sbin/rabbitmq-server" ]; then
  log "RabbitMQ 3.13.7"
  curl -fsSL -o rabbitmq.txz https://github.com/rabbitmq/rabbitmq-server/releases/download/v3.13.7/rabbitmq-server-generic-unix-3.13.7.tar.xz
  mkdir -p "$R/opt/rabbitmq" && tar xJf rabbitmq.txz -C "$R/opt/rabbitmq" --strip-components=1
fi

cat > "$R/env.sh" <<EOF
export JAVA_HOME=$R/opt/jdk17
export PATH=$R/bin:$R/opt/jdk17/bin:$R/opt/maven/bin:$R/opt/k6:$R/opt/redis/bin:$R/opt/rabbitmq/sbin:\$PATH
export K6=$R/opt/k6/k6
EOF
. "$R/env.sh"
log "versions"
java -version 2>&1 | head -1
mvn -v 2>&1 | head -1
k6 version
mysqld --version
redis-server --version
erl -noshell -eval 'io:format("erlang ~s~n",[erlang:system_info(otp_release)]), halt().'
ls "$R/opt/rabbitmq/sbin"
log "SETUP DONE"
