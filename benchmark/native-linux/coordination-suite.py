"""One-shot native multi-JVM correctness suite, Python 3.6+ stdlib.
Only the dedicated existing /data/ms fixture is reset. Signal faults are real;
no networking fault or cross-host/failover claim. Invoke the shell launcher.
All numbers below are experiment configuration, NOT measured capacity.
"""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import tarfile
import threading
import time
import traceback
import urllib.error
import urllib.request

BASE = 'e2a6f96fc6b55ac9dc97fd03dc3800e6d758960f'
PORTS = [18081, 18082, 18083]
POOL = [14, 13, 13]  # global maximum remains 40, not 40 per JVM
INITIAL = 10
QUEUE = 'mini.seckill.order.queue'
DEAD_QUEUE = 'mini.seckill.dead.queue'
TOTAL = 'seckill:stock:1:1001'
FRONTIER = 'seckill:coord:1:1001:version'
INFLIGHT = 'seckill:coord:1:1001:inflight'
DEADLINES = 'seckill:coord:1:1001:deadlines'
LOCK = 'seckill:lock:reconcile:1:1001'  # validated against RedisKeyUtil by CI test


def plan():
    return [{'name': name, 'status': 'not_run'} for name in (
        '01-live-three-and-repair', '02-live-deduction-frontier', '03-expired-owner-successor',
        '04-paused-reservation-reclaimed', '05-killed-before-persist',
        '06-killed-after-persist', '07-send-attempt-aba', '08-live-consumer-loss', '09-scarce-stock')]


def write_json(path, value):
    path = Path(path)
    temp = path.with_name(path.name + '.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.replace(str(temp), str(path))


def digest(path):
    result = hashlib.sha256()
    with open(str(path), 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def archive(out):
    entries = []
    for path in sorted(out.rglob('*')):
        if path.is_file() and path.name != 'SHA256SUMS':
            if path.is_symlink():
                raise RuntimeError('evidence symlink refused: ' + str(path))
            entries.append(digest(path) + '  ' + str(path.relative_to(out)))
    (out / 'SHA256SUMS').write_text('\n'.join(entries) + '\n', encoding='utf-8')
    dest = Path(str(out) + '.tar.gz')
    with tarfile.open(str(dest) + '.tmp', 'w:gz') as target:
        target.add(str(out), arcname=out.name)
    os.replace(str(dest) + '.tmp', str(dest))
    Path(str(dest) + '.sha256').write_text(digest(dest) + '  ' + dest.name + '\n', encoding='utf-8')


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def wait_for(predicate, timeout, description):
    deadline = time.monotonic() + timeout
    while True:
        if predicate():
            return
        if time.monotonic() >= deadline:
            raise RuntimeError('timed out: ' + description)
        time.sleep(0.1)


def proc_identity(pid):
    if not isinstance(pid, int) or pid <= 1:
        raise ValueError('invalid PID')
    try:
        raw = Path('/proc/%d/stat' % pid).read_text(encoding='utf-8')
        # comm may contain spaces/parentheses: parse after the last closing paren.
        suffix = raw.rsplit(') ', 1)[1].split()
        return {'state': suffix[0], 'start_ticks': suffix[19]}
    except FileNotFoundError:
        return None
    # Permission/malformed proc data is UNKNOWN, never proof of death.


def cmdline(pid):
    return Path('/proc/%d/cmdline' % pid).read_bytes().split(b'\0')


def sql_id(value):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', value):
        raise ValueError('unexpected request ID: ' + repr(value))
    return "'" + value + "'"


def request(port, path, payload=None, method=None, timeout=10):
    data = None if payload is None else json.dumps(payload).encode('utf-8')
    req = urllib.request.Request('http://127.0.0.1:%d%s' % (port, path), data=data,
                                 method=method, headers={'Content-Type': 'application/json'})
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode('utf-8')
            status = response.status
    except urllib.error.HTTPError as error:
        raw = error.read().decode('utf-8', errors='replace')
        status = error.code
    result = {'started_at': started, 'finished_at': time.time(), 'http_status': status, 'raw': raw}
    try:
        result['body'] = json.loads(raw)
    except ValueError:
        result['body'] = None
    return result


class AsyncRequest:
    """No background file writer: unjoined requests cannot mutate a packed evidence file."""
    def __init__(self, port, path, payload=None, method='POST'):
        self.result = None
        def run():
            try:
                self.result = request(port, path, payload, method, timeout=90)
            except Exception as error:
                self.result = {'transport_error': str(error), 'finished_at': time.time()}
        self.thread = threading.Thread(target=run)
        self.thread.daemon = True
        self.thread.start()

    def collect(self, timeout=95):
        self.thread.join(timeout)
        if self.thread.is_alive():
            raise RuntimeError('HTTP outcome remains unknown')
        return self.result


class Node:
    def __init__(self, suite, index, initial_sending=True, lease='20s'):
        self.suite, self.index, self.port = suite, index, PORTS[index]
        self.folder = suite.case_dir / ('node-%s' % chr(65 + index))
        self.folder.mkdir(exist_ok=True)
        self.faults = self.folder / 'faults'
        self.faults.mkdir(exist_ok=True)
        self.generation = len(list(self.folder.glob('launch-*.json'))) + 1
        marker = '%s-%d-%d' % (suite.out.name, index, self.generation)
        self.marker = '--seckill.coordination.run-id=' + marker
        self.args = ['java', '-Xms512m', '-Xmx1024m', '-jar', str(suite.jar), self.marker,
            '--server.address=127.0.0.1', '--server.port=%d' % self.port,
            '--spring.profiles.active=100k,coordination-test',
            '--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/mini_seckill?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true',
            '--spring.data.redis.host=127.0.0.1', '--spring.data.redis.port=6379',
            '--spring.rabbitmq.host=127.0.0.1', '--spring.rabbitmq.port=5672',
            '--spring.rabbitmq.requested-heartbeat=5s',
            '--seckill.snowflake.worker-id=%d' % (index + 1),
            '--spring.datasource.hikari.maximum-pool-size=%d' % POOL[index],
            '--spring.datasource.hikari.minimum-idle=%d' % (3 if index < 2 else 2),
            '--spring.datasource.hikari.connection-timeout=2000',
            '--seckill.pool-budget.enabled=false', '--seckill.rate-limit.enabled=false',
            '--seckill.anti-brush.enabled=false', '--seckill.dynamic-rate-limit.enabled=false',
            '--seckill.user-auth.enabled=false', '--seckill.admin-auth.enabled=false',
            '--seckill.mq-fallback-sync=false', '--seckill.sold-out-local-cache.enabled=false',
            '--seckill.stock-shard.enabled=true', '--seckill.stock-shard.bucket-count=64',
            '--seckill.mysql-stock-segment.enabled=true', '--seckill.mysql-stock-segment.segment-count=32',
            '--seckill.capacity.initial-sending-enabled=' + str(initial_sending).lower(),
            '--seckill.mq-consumer.concurrent-consumers=2', '--seckill.mq-consumer.max-concurrent-consumers=2',
            '--seckill.mq-consumer.prefetch-count=20',
            '--seckill.message-retry.fixed-delay=1000', '--seckill.message-retry.max-retry=5',
            '--seckill.order-timeout.fixed-delay=2000',
            '--seckill.reconcile.fixed-delay=%d' % (1000 if suite.live else 60000),
            '--seckill.coordination.repair-lease=' + lease,
            '--seckill.coordination.reservation-timeout=5s',
            '--seckill.coordination.recovery-delay=500',
            '--seckill.coordination.faults-enabled=true',
            '--seckill.coordination.fault-directory=' + str(self.faults),
            '--seckill.coordination.fault-wait=120s']
        # Credentials are supplied only through the environment, not logged argv.
        env = suite.env.copy()
        env['TZ'] = 'Asia/Shanghai'
        self.output = open(str(self.folder / ('app-%d.log' % self.generation)), 'w')
        self.child = subprocess.Popen(self.args, stdout=self.output, stderr=subprocess.STDOUT,
                                      env=env, start_new_session=True, close_fds=True)
        self.start_ticks = None
        try:
            identity = proc_identity(self.child.pid)
            if identity is None:
                raise RuntimeError('JVM exited before identity capture')
            self.start_ticks = identity['start_ticks']
            write_json(self.folder / ('launch-%d.json' % self.generation), {
                'pid': self.child.pid, 'start_ticks': self.start_ticks, 'argv': self.args,
                'worker_id': index + 1, 'port': self.port, 'jar_sha256': suite.manifest['jar_sha256'], 'at': time.time()})
        except BaseException:
            # This unreaped Popen child cannot have its PID reused. A failure to
            # save evidence must not leave an untracked JVM behind.
            try:
                if self.child.poll() is None: self.child.kill()
                self.child.wait(timeout=10)
                self.output.close()
            except Exception:
                suite.nodes.append(self)  # block reset if death cannot be proven
            raise

    def alive(self):
        ident = proc_identity(self.child.pid)
        return ident is not None and ident['state'] != 'Z' and ident['start_ticks'] == self.start_ticks

    def signal(self, sig):
        ident = proc_identity(self.child.pid)
        if ident is None or ident['state'] == 'Z':
            return
        require(ident['start_ticks'] == self.start_ticks, 'recycled PID: refusing signal')
        args = cmdline(self.child.pid)
        require(self.marker.encode() in args and str(self.suite.jar).encode() in args,
                'PID does not match our jar/run marker: refusing signal')
        os.kill(self.child.pid, sig)
        self.suite.event('signal', node=self.index, pid=self.child.pid, signal=int(sig), before_state=ident['state'])
        if sig == signal.SIGSTOP:
            wait_for(lambda: proc_identity(self.child.pid)['state'] in ('T', 't'), 5, 'JVM stopped state')
        elif sig == signal.SIGKILL:
            wait_for(lambda: not self.alive(), 10, 'JVM death after KILL')
            self.child.wait(timeout=10)

    def healthy(self):
        if not self.alive():
            raise RuntimeError('JVM exited; see ' + str(self.folder))
        try:
            reply = request(self.port, '/actuator/health', timeout=2)
            return reply['http_status'] == 200 and reply['body'].get('status') == 'UP'
        except (OSError, urllib.error.URLError):
            return False

    def arm(self, point):
        arm = self.faults / (point + '.arm')
        with arm.open('x', encoding='utf-8') as stream:
            stream.write('one-shot\n')
        self.suite.event('arm', node=self.index, point=point)

    def hit(self, point):
        path = self.faults / (point + '.hit')
        wait_for(lambda: path.exists() and bool(path.read_text(encoding='utf-8').strip()), 30, point + ' barrier')
        text = path.read_text(encoding='utf-8').strip()
        require(bool(text), 'empty barrier identity')
        self.suite.event('hit', node=self.index, point=point, identity=text)
        return text.split()

    def release(self, point):
        (self.faults / (point + '.release')).write_text('release\n', encoding='utf-8')
        self.suite.event('release', node=self.index, point=point)

    def close(self):
        if self.alive():
            for path in self.faults.glob('*.claimed'):
                try: (self.faults / (path.stem + '.release')).write_text('cleanup\n', encoding='utf-8')
                except OSError as error: self.suite.evidence_errors.append('cleanup barrier: ' + str(error))
            self.signal(signal.SIGCONT)
            self.signal(signal.SIGTERM)
            try:
                self.child.wait(timeout=45)
            except subprocess.TimeoutExpired:
                # Only a recorded child with exact PID start-time/run marker may be escalated.
                self.signal(signal.SIGKILL)
        else:
            self.child.wait(timeout=10)
        require(not self.alive(), 'JVM not proven dead; do not reset')
        self.output.close()


class Suite:
    def __init__(self, args):
        self.repo, self.root, self.out = map(lambda p: Path(p).resolve(), (args.repo, args.root, args.out))
        self.launcher_log = Path(args.launcher_log)
        self.env = os.environ.copy()
        self.env['MYSQL_PWD'] = self.env.get('COORD_DB_PASSWORD', 'miniseckill')
        self.env['SPRING_DATASOURCE_USERNAME'] = self.env.get('COORD_DB_USER', 'miniseckill')
        self.env['SPRING_DATASOURCE_PASSWORD'] = self.env['MYSQL_PWD']
        self.env['SPRING_RABBITMQ_USERNAME'] = self.env.get('COORD_MQ_USER', 'guest')
        self.env['SPRING_RABBITMQ_PASSWORD'] = self.env.get('COORD_MQ_PASSWORD', 'guest')
        self.nodes, self.async_requests = [], []
        self.case_dir, self.live, self.k6 = None, False, None
        self.events = None
        self.evidence_errors = []
        self.manifest = {'format': 1, 'started_at': time.time(), 'status': 'running', 'cases': plan(),
            'scope': 'one host, three native JVMs, one MySQL/Redis/RabbitMQ; process signals only',
            'configuration_not_results': {'pool_maximum_per_node': POOL, 'pool_maximum_total': sum(POOL),
                'redis_buckets': 64, 'mysql_segments': 32, 'production_repair_lease_seconds': 20,
                'fixture_broker_heartbeat_seconds': 5, 'fixture_reservation_timeout_seconds': 5, 'production_reservation_timeout_seconds': 60,
                'live_rate_per_second': 200, 'live_seconds': 75, 'scarce_supply': 41, 'scarce_demand': 410}}

    def command(self, argv, filename, timeout=60, check=True, stdin=None, cwd=None):
        path = (self.case_dir or self.out) / filename
        with path.open('wb') as output:
            child = subprocess.Popen([str(x) for x in argv], stdin=subprocess.PIPE if stdin is not None else subprocess.DEVNULL,
                stdout=output, stderr=subprocess.STDOUT, env=self.env, cwd=str(cwd) if cwd else None, start_new_session=True, close_fds=True)
            try:
                child.communicate(input=None if stdin is None else stdin.encode('utf-8'), timeout=timeout)
            except BaseException:
                os.killpg(child.pid, signal.SIGKILL); child.wait(timeout=10)
                raise
        if check and child.returncode != 0:
            raise RuntimeError('command failed (%s); see %s' % (child.returncode, path))
        return child.returncode

    def sql(self, query):
        argv = ['mysql', '--protocol=TCP', '-h127.0.0.1', '-P3306', '-u' + self.env['SPRING_DATASOURCE_USERNAME'],
                '--connect-timeout=3', '-N', '-B', 'mini_seckill', '-e', query]
        result = subprocess.run(argv, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        if result.returncode != 0:
            raise RuntimeError('MySQL query failed: ' + result.stderr.decode('utf-8', errors='replace'))
        return result.stdout.decode('utf-8').strip()

    def redis(self, *args):
        result = subprocess.run(['redis-cli', '-h', '127.0.0.1', '-p', '6379', '--raw'] + [str(x) for x in args],
            env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        text = result.stdout.decode('utf-8').strip()
        if result.returncode != 0 or text.startswith(('ERR ', 'WRONGTYPE ', 'NOAUTH ', 'MISCONF ', 'BUSY ', 'LOADING ', 'OOM ', 'NOREPLICAS ')):
            raise RuntimeError('Redis command failed: ' + text + result.stderr.decode('utf-8', errors='replace'))
        return text

    def mq(self, name, method='GET', allow_absent=False):
        auth = base64.b64encode((self.env['SPRING_RABBITMQ_USERNAME'] + ':' + self.env['SPRING_RABBITMQ_PASSWORD']).encode()).decode()
        url = 'http://127.0.0.1:15672/api/queues/%2F/' + name + ('/contents' if method == 'DELETE' else '')
        req = urllib.request.Request(url, method=method, headers={'Authorization': 'Basic ' + auth})
        try:
            with urllib.request.urlopen(req, timeout=5) as response:
                raw = response.read()
                return json.loads(raw.decode()) if raw else {}
        except urllib.error.HTTPError as error:
            if allow_absent and error.code == 404:
                return None
            raise

    def event(self, kind, **values):
        item = dict(values, kind=kind, at=time.time(), monotonic=time.monotonic())
        if self.events is not None:
            try:
                self.events.write(json.dumps(item, ensure_ascii=False) + '\n'); self.events.flush()
            except (OSError, ValueError) as error:
                # Signalling/cleanup must proceed even if the evidence disk fills.
                # This case cannot pass when its event stream was not recorded.
                self.evidence_errors.append('event %s: %s' % (kind, error))

    def assert_fixture_idle(self):
        for path in Path('/proc').iterdir():
            if not path.name.isdigit():
                continue
            pid = int(path.name)
            if pid <= 1:
                continue
            try:
                args = cmdline(pid)
            except FileNotFoundError:
                continue
            except PermissionError:
                # Other users' processes are not ours to signal; TCP checks still apply.
                continue
            text = b' '.join(args)
            if args and Path(os.fsdecode(args[0])).name == 'k6':
                raise RuntimeError('unowned/live k6 %d; refusing fixture reset' % pid)
            if args and (b'java' in args[0]) and (b'mini-seckill' in text or b'--server.port=1808' in text):
                ident = proc_identity(pid)
                if ident and ident['state'] != 'Z':
                    raise RuntimeError('unowned/live benchmark JVM %d; refusing reset' % pid)
        for port in [18080] + PORTS:
            with socket.socket() as probe:
                probe.settimeout(0.3)
                if probe.connect_ex(('127.0.0.1', port)) == 0:
                    raise RuntimeError('port %d is occupied; refusing reset' % port)

    def prepare(self):
        for name in ('git', 'java', 'mvn', 'k6', 'mysql', 'redis-cli', 'jstack', 'bash', 'tar'):
            if shutil.which(name) is None:
                raise RuntimeError('missing existing native prerequisite: ' + name)
        require(subprocess.check_output(['git', '-C', str(self.repo), 'status', '--porcelain']).strip() == b'',
                'dirty working tree: refusing untraceable build')
        sha = subprocess.check_output(['git', '-C', str(self.repo), 'rev-parse', 'HEAD']).decode().strip()
        subprocess.check_call(['git', '-C', str(self.repo), 'merge-base', '--is-ancestor', BASE, sha])
        self.manifest['candidate_sha'] = sha
        self.command(['bash', '-c', 'uname -a; java -version; mvn -version; k6 version; redis-cli INFO server; free -m; df -h'], 'host.txt')
        # Freeze all code before a possible checkout/edit in the user's working tree.
        source_archive = self.out / 'source.tar'
        self.command(['git', '-C', self.repo, 'archive', '--format=tar', '--output=' + str(source_archive), sha], 'snapshot.log')
        self.source = self.out / 'source'; self.source.mkdir()
        self.command(['tar', '-xf', source_archive, '-C', self.source], 'extract.log')
        source_archive.unlink()
        self.command(['mvn', '-B', '-f', self.source / 'pom.xml', 'clean', 'verify'], 'maven-verify.log', timeout=1800, cwd=self.source)
        jars = list((self.source / 'target').glob('*.jar'))
        require(len(jars) == 1, 'expected exactly one executable jar')
        self.jar = self.out / 'candidate.jar'; shutil.copyfile(str(jars[0]), str(self.jar))
        self.manifest['jar_sha256'] = digest(self.jar)
        for folder in ('surefire-reports', 'site'):
            path = self.source / 'target' / folder
            if path.exists(): shutil.copytree(str(path), str(self.out / ('build-' + folder)))
        shutil.rmtree(str(self.source / 'target'))
        # Stop only the old PR #3 app whose PID/port checks are implemented in the existing script.
        self.command(['bash', self.source / 'benchmark/native-linux/app.sh', 'stop'], 'pre-suite-stop.log', timeout=60)
        self.assert_fixture_idle()
        require(self.redis('PING') == 'PONG', 'Redis not ready')
        policy = self.redis('CONFIG', 'GET', 'maxmemory-policy').splitlines()
        require(policy == ['maxmemory-policy', 'noeviction'], 'requires Redis noeviction policy; do not change it silently')
        write_json(self.out / 'redis-policy.json', {'maxmemory_policy': policy, 'maxmemory': self.redis('CONFIG', 'GET', 'maxmemory'),
            'note': 'noeviction and adequate memory headroom are prerequisites; failover/data-loss not tested'})
        self.sql('SELECT 1')
        # Idempotent migration: only add missing columns, never rewrite stock or evidence.
        existing = set(self.sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='mini_seckill' AND TABLE_NAME='seckill_message'").splitlines())
        actions = []
        for name, ddl in [('send_token', 'VARCHAR(64) NULL'), ('send_lease_until', 'DATETIME(6) NULL')]:
            if name not in existing:
                action = 'ALTER TABLE seckill_message ADD COLUMN %s %s' % (name, ddl)
                self.sql(action); actions.append(action)
        write_json(self.out / 'schema-migration.json', {'actions': actions, 'at': time.time()})
        write_json(self.out / 'manifest.json', self.manifest)

    def reset(self):
        self.assert_fixture_idle()
        self.sql((self.source / 'benchmark/reset.sql').read_text(encoding='utf-8'))
        keys = self.redis('--scan', '--pattern', 'seckill:*').splitlines()
        for offset in range(0, len(keys), 100):
            self.redis('DEL', *keys[offset:offset + 100])
        self.mq(QUEUE, 'DELETE', allow_absent=True); self.mq(DEAD_QUEUE, 'DELETE', allow_absent=True)
        self.event('cold_reset', removed_redis_keys=len(keys))

    def start(self, stock=INITIAL, initial_sending=True, leases=None):
        self.nodes = []
        for n in range(3):
            node = Node(self, n, initial_sending, (leases or ['20s'] * 3)[n]); self.nodes.append(node)
        for node in self.nodes:
            wait_for(node.healthy, 150, 'node %d health' % node.index)
        reply = request(PORTS[0], '/api/seckill/init?activityId=1&skuId=1001&stock=%d' % stock, method='POST')
        write_json(self.case_dir / 'init.json', reply)
        require(reply['body'] and reply['body'].get('code') == 0, 'new SKU initialization failed')
        self.initial_stock = stock
        self.event('nodes_started', stock=stock)

    def warm(self, n=1):
        return request(PORTS[n], '/api/seckill/warmup?activityId=1&skuId=1001', method='POST')

    def async_warm(self, n):
        result = AsyncRequest(PORTS[n], '/api/seckill/warmup?activityId=1&skuId=1001')
        self.async_requests.append(result); return result

    def order(self, n, user, asynchronous=False):
        payload = {'activityId': 1, 'skuId': 1001, 'userId': user}
        if asynchronous:
            result = AsyncRequest(PORTS[n], '/api/seckill/order', payload)
            self.async_requests.append(result); return result
        result = request(PORTS[n], '/api/seckill/order', payload, method='POST')
        self.event('order_response', node=n, user=user, response=result)
        require(result['body'] and result['body'].get('code') == 0, 'forced admission did not queue')
        return result

    def status(self, rid):
        value = self.sql('SELECT status FROM seckill_message WHERE request_id=' + sql_id(rid))
        return None if not value else int(value)

    def success(self, user):
        return int(self.sql('SELECT COUNT(*) FROM seckill_order WHERE activity_id=1 AND sku_id=1001 AND user_id=%d AND status=2' % user)) == 1

    def state(self):
        # One SQL statement for each MySQL observation. During load it is not
        # atomic WITH Redis/MQ; final cross-store assertions require stopped JVMs.
        columns = ['total', 'available', 'sold', 'success', 'orders_other', 'messages', 'cancelled',
                   'unfinished', 'failed_terminal', 'duplicate_business', 'duplicate_order_id', 'consumed_without_success']
        query = """SELECT
          (SELECT COALESCE(SUM(total_stock),0) FROM sku_stock_segment),
          (SELECT COALESCE(SUM(available_stock),0) FROM sku_stock_segment),
          (SELECT COALESCE(SUM(sold_count),0) FROM sku_stock_segment),
          (SELECT COUNT(*) FROM seckill_order WHERE status=2),
          (SELECT COUNT(*) FROM seckill_order WHERE status<>2),
          (SELECT COUNT(*) FROM seckill_message),
          (SELECT COUNT(*) FROM seckill_message WHERE status=11),
          (SELECT COUNT(*) FROM seckill_message WHERE status IN (0,1,3,4,5,8,9,10)),
          (SELECT COUNT(*) FROM seckill_message WHERE status IN (6,7)),
          (SELECT COUNT(*) FROM (SELECT activity_id,user_id,sku_id FROM seckill_order GROUP BY activity_id,user_id,sku_id HAVING COUNT(*)>1) b),
          (SELECT COUNT(*) FROM (SELECT order_id FROM seckill_order GROUP BY order_id HAVING COUNT(*)>1) o),
          (SELECT COUNT(*) FROM seckill_message m WHERE m.status=2 AND NOT EXISTS
             (SELECT 1 FROM seckill_order o WHERE o.activity_id=m.activity_id AND o.sku_id=m.sku_id AND o.user_id=m.user_id AND o.status=2))"""
        fields = self.sql(query).split('\t'); require(len(fields) == len(columns), 'incomplete MySQL observation')
        result = dict(zip(columns, map(int, fields)))
        # One read-only Lua gives a coherent Redis observation of all 64 buckets + ledger.
        script = "local r={redis.call('GET',KEYS[1]) or 'MISSING',redis.call('HLEN',KEYS[2]),redis.call('ZCARD',KEYS[3])}; for i=4,#KEYS do r[#r+1]=redis.call('GET',KEYS[i]) or 'MISSING' end; return r"
        keys = [TOTAL, INFLIGHT, DEADLINES] + [TOTAL + ':bucket:%d' % i for i in range(64)]
        values = self.redis('EVAL', script, len(keys), *keys).splitlines()
        require(len(values) == 67 and 'MISSING' not in values, 'incomplete Redis observation')
        values = list(map(int, values))
        result.update(redis_total=values[0], inflight=values[1], deadlines=values[2], buckets=values[3:])
        queue = self.mq(QUEUE); dead = self.mq(DEAD_QUEUE)
        # A missing management field is not a zero queue depth.
        result.update(mq_ready=int(queue['messages_ready']), mq_unacked=int(queue['messages_unacknowledged']),
                      mq_dead=int(dead['messages_ready']) + int(dead['messages_unacknowledged']))
        result['at'] = time.time()
        return result

    def observe(self, name='observations.jsonl'):
        value = self.state()
        with (self.case_dir / name).open('a', encoding='utf-8') as output:
            output.write(json.dumps(value) + '\n')
        return value

    def observe_retry(self, name, timeout=30):
        result = [None]
        def read():
            try: result[0] = self.observe(name); return True
            except (OSError, ValueError, KeyError, RuntimeError, AssertionError) as error:
                self.event('observation_unknown', file=name, error=str(error))
                time.sleep(1)
                return False
        wait_for(read, timeout, 'complete observation: ' + name)
        return result[0]

    def drain(self):
        stable = [0]
        def settled():
            try: value = self.observe('drain.jsonl')
            except (OSError, ValueError, KeyError, RuntimeError, AssertionError) as error:
                stable[0] = 0
                self.event('observation_unknown', file='drain.jsonl', error=str(error))
                time.sleep(1)
                return False
            empty = all(value[k] == 0 for k in ('unfinished', 'inflight', 'deadlines', 'mq_ready', 'mq_unacked'))
            stable[0] = stable[0] + 1 if empty else 0
            time.sleep(1)
            return stable[0] >= 5
        wait_for(settled, 180, 'durable messages + reservations + broker drain')
        for n in range(3):
            if self.nodes[n].alive():
                reply = self.warm(n)
                self.event('final_warmup', node=n, response=reply)
                if reply['body'] and reply['body'].get('code') == 0:
                    return
        raise RuntimeError('no final guarded repair completed')

    def stop_all(self):
        errors = []
        for node in self.nodes:
            try: node.close()
            except Exception as error: errors.append(str(error))
        if errors: raise RuntimeError('cleanup failed; DO NOT reset: ' + '; '.join(errors))
        self.assert_fixture_idle()
        for call in self.async_requests:
            call.thread.join(5)
        self.async_requests = []

    def contains(self, node, pattern):
        return any(re.search(pattern, p.read_text(encoding='utf-8', errors='replace'))
                   for p in self.nodes[node].folder.glob('app-*.log'))

    def run_load(self, nodes, actions=None, iterations=None):
        env = self.env.copy()
        env.update(BASE_URLS=','.join('http://127.0.0.1:%d' % PORTS[n] for n in nodes), RATE='200', DURATION='75s', USER_BASE='10000000')
        if iterations: env['ITERATIONS'] = str(iterations)
        summary = self.case_dir / 'k6-summary.json'
        argv = ['k6', 'run', '--summary-export', str(summary), str(self.source / 'benchmark/native-linux/coordination-load.js')]
        todo = list(actions or [])
        with (self.case_dir / 'k6.log').open('w') as output:
            self.k6 = subprocess.Popen(argv, stdout=output, stderr=subprocess.STDOUT, env=env, start_new_session=True)
            start = time.monotonic()
            self.event('load_started', nodes=nodes, iterations=iterations, argv=argv)
            while self.k6.poll() is None:
                for after, action in list(todo):
                    if time.monotonic() - start >= after:
                        action(); todo.remove((after, action))
                self.observe('load-observations.jsonl')
                if time.monotonic() - start > 180:
                    raise RuntimeError('k6 exceeded scenario budget')
                time.sleep(1)
            code = self.k6.wait(timeout=5); self.k6 = None
        self.event('load_finished', exit=code)
        require(not todo, 'load ended before all scheduled faults executed')
        require(summary.exists(), 'k6 did not produce summary')
        result = json.loads(summary.read_text(encoding='utf-8'))
        observation = validate_load(result, code, iterations)
        self.load_window = (observation['first_request_started_at'], observation['last_request_started_at'])
        self.queued = observation['queued']
        write_json(self.case_dir / 'load-coverage.json', observation)
        return result

    def execute_case(self, case):
        name = case['name']; self.case_dir = self.out / name; self.case_dir.mkdir()
        self.events = (self.case_dir / 'events.jsonl').open('w', encoding='utf-8')
        self.live = name.startswith(('01-', '08-'))
        self.queued = 0; self.nodes = []; self.async_requests = []
        case.update(status='running', started_at=time.time())
        errors_before = len(self.evidence_errors)
        try:
            self.reset()
            # 02 keeps A's lease valid through the 45 s order wait below; 03 needs A's to lapse.
            self.start(stock=30000 if self.live else (41 if name.startswith('09-') else INITIAL),
                       initial_sending=not name.startswith('07-'),
                       leases=['2s', '20s', '20s'] if name.startswith('03-') else (
                           ['60s', '20s', '20s'] if name.startswith('02-') else None))
            self.observe_retry('before.jsonl')
            if name.startswith('01-'):
                def drift():
                    self.event('injected_drift', before=self.redis('GET', TOTAL), new='0', note='decrease total only; no frontier rewrite')
                    self.drift_at = time.time()
                    self.redis('SET', TOTAL, 0)
                self.run_load([0, 1, 2], actions=[(20, drift)])
                applied = []
                for node in self.nodes:
                    for path in node.folder.glob('app-*.log'):
                        for at in re.findall(r'COORD_REPAIR outcome=APPLIED[^\n]*atMillis=(\d+)', path.read_text(encoding='utf-8', errors='replace')):
                            stamp = int(at)/1000.0
                            if max(self.drift_at,self.load_window[0]) <= stamp <= self.load_window[1]:
                                applied.append({'node':node.index,'applied_at':stamp})
                require(bool(applied), 'no actual guarded write BETWEEN post-drift live request starts; quiescent-only repair is not this case')
                write_json(self.case_dir / 'repair-during-live-window.json', {'applied':applied,'request_start_window':self.load_window,'drift_at':self.drift_at})
            elif name.startswith('02-'):
                a = self.nodes[0]; a.arm('after-snapshot'); old = self.async_warm(0); a.hit('after-snapshot'); a.signal(signal.SIGSTOP)
                # The broker may deliver this order to stopped A's consumer and requeues it only after
                # missing A's heartbeats (run f86c1d55: still unacked 10 s after STOP), so allow 45 s.
                self.order(1, 701); wait_for(lambda: self.success(701), 45, 'concurrent durable order')
                require(int(self.redis('PTTL', LOCK)) > 500, 'lease expired before version-race test; inconclusive')
                a.release('after-snapshot'); a.signal(signal.SIGCONT)
                write_json(self.case_dir / 'old-repair-response.json', old.collect())
                wait_for(lambda: self.contains(0, r'COORD_REPAIR outcome=VERSION_CHANGED'), 10, 'live frontier rejection')
                require(int(self.redis('GET', TOTAL)) == 9, 'old snapshot overwrote concurrent admission')
            elif name.startswith('03-'):
                a, b = self.nodes[:2]; a.arm('after-snapshot'); old = self.async_warm(0); a.hit('after-snapshot'); a.signal(signal.SIGSTOP)
                self.order(2, 702); wait_for(lambda: self.success(702), 45, 'new durable order')  # same broker redelivery wait as 02
                wait_for(lambda: int(self.redis('EXISTS', LOCK)) == 0, 10, 'old Redis lease expiry')
                self.redis('SET', TOTAL, 0); b.arm('after-snapshot'); new = self.async_warm(1); hit = b.hit('after-snapshot')
                successor = hit[2]; require(self.redis('GET', LOCK) == successor, 'B does not own successor lease')
                a.release('after-snapshot'); a.signal(signal.SIGCONT); write_json(self.case_dir / 'stale-response.json', old.collect())
                wait_for(lambda: self.contains(0, r'COORD_REPAIR outcome=LEASE_LOST'), 10, 'expired owner rejection')
                require(self.redis('GET', LOCK) == successor, 'A unlocked B')
                b.release('after-snapshot'); write_json(self.case_dir / 'successor-response.json', new.collect())
                wait_for(lambda: int(self.redis('GET', TOTAL)) == 9, 10, 'successor repair write')
            elif name.startswith(('04-', '05-', '06-')):
                a = self.nodes[0]; point = 'after-persist' if name.startswith('06-') else 'after-reserve'
                a.arm(point); old = self.order(0, 703, asynchronous=True); hit = a.hit(point); rid = hit[0]
                if name.startswith('04-'): a.signal(signal.SIGSTOP)
                else: a.signal(signal.SIGKILL)
                blocked = self.warm(1); write_json(self.case_dir / 'repair-while-unresolved.json', blocked)
                require(self.contains(1, r'COORD_REPAIR outcome=INFLIGHT'), 'unresolved reservation did not block repair')
                if point == 'after-persist':
                    wait_for(lambda: int(self.redis('HLEN', INFLIGHT)) == 0, 20, 'durable intent resolution')
                    require(int(self.redis('GET', TOTAL)) == 9, 'durable acceptance was refunded')
                    wait_for(lambda: self.status(rid) == 2, 90, 'retry after killed initial sender')
                else:
                    wait_for(lambda: self.status(rid) == 11 and int(self.redis('HLEN', INFLIGHT)) == 0, 20, 'cancellation fence and refund')
                    require(int(self.redis('GET', TOTAL)) == 10, 'orphan refund not exactly once')
                    self.order(1, 703); wait_for(lambda: self.success(703), 15, 'new request for same business owner')
                if name.startswith('04-'):
                    a.release(point); a.signal(signal.SIGCONT)
                else:
                    a.close(); self.nodes[0] = Node(self, 0); wait_for(self.nodes[0].healthy, 150, 'restarted A health')
                write_json(self.case_dir / 'original-client-outcome.json', old.collect())
                if point == 'after-reserve': require(self.status(rid) == 11, 'late process reopened cancellation')
                require(int(self.redis('GET', TOTAL)) == 9, 'late process changed remaining stock')
                write_json(self.case_dir / 'cut-identity.json', {'request_id': rid, 'point': point, 'status': self.status(rid)})
            elif name.startswith('07-'):
                a = self.nodes[0]; a.arm('after-send-claim'); old = self.order(0, 704, asynchronous=True); rid, token = a.hit('after-send-claim')
                a.signal(signal.SIGSTOP); wait_for(lambda: self.status(rid) == 2, 90, 'B retries after A send lease expiry')
                successor = self.sql('SELECT send_token FROM seckill_message WHERE request_id=' + sql_id(rid))
                require(successor != token, 'no new send generation observed')
                a.release('after-send-claim'); a.signal(signal.SIGCONT); write_json(self.case_dir / 'late-send-client.json', old.collect())
                wait_for(lambda: self.contains(0, r'COORD_SEND_RESULT requestId=' + re.escape(rid) + ' token=' + re.escape(token) + r' status=\d+ changed=0'), 20, 'late callback rejected')
                require(self.status(rid) == 2, 'late callback regressed consumed status')
                write_json(self.case_dir / 'send-aba.json', {'request_id': rid, 'expired_token': token, 'successor_token': successor})
            elif name.startswith('08-'):
                def restart():
                    self.nodes[0].close(); self.nodes[0] = Node(self, 0); wait_for(self.nodes[0].healthy, 150, 'replacement A')
                self.run_load([1, 2], actions=[(15, lambda: self.nodes[0].signal(signal.SIGSTOP)),
                    (25, lambda: self.nodes[0].signal(signal.SIGKILL)), (35, restart)])
            elif name.startswith('09-'):
                self.run_load([0, 1, 2], iterations=410)
            self.drain()
            for node in self.nodes:
                if node.alive():
                    self.command(['jstack', str(node.child.pid)], 'jstack-node%d.txt' % node.index, timeout=15, check=False)
                    try:
                        value = request(node.port, '/actuator/prometheus', timeout=5)
                        (self.case_dir / ('prometheus-node%d.txt' % node.index)).write_text(value['raw'], encoding='utf-8')
                    except Exception as error:
                        self.event('metrics_unknown', node=node.index, error=str(error))
            self.stop_all()
            final = self.observe_retry('stopped-final.jsonl')
            failures = validate_final(final, self.initial_stock, self.queued, name.startswith('09-'))
            require(not failures, '; '.join(failures))
            case.update(status='passed', final=final, client_queued=self.queued)
        except BaseException as error:
            case.update(status='failed', error=str(error))
            (self.case_dir / 'failure.txt').write_text(traceback.format_exc(), encoding='utf-8')
            try: self.observe('failure-observation.jsonl')
            except Exception as observation: case['observation_error'] = str(observation)
            if isinstance(error, (KeyboardInterrupt, InterruptedError)):
                raise
        finally:
            if self.k6 is not None:
                if self.k6.poll() is None: os.killpg(self.k6.pid, signal.SIGKILL)
                self.k6.wait(timeout=10); self.k6 = None
            try: self.stop_all()
            except Exception as cleanup:
                case['cleanup_error'] = str(cleanup)
                case['status'] = 'failed'
                raise RuntimeError('cannot prove all JVMs dead; remaining cases NOT RUN: ' + str(cleanup))
            finally:
                case['finished_at'] = time.time()
                if len(self.evidence_errors) > errors_before:
                    case.update(status='failed', evidence_errors=self.evidence_errors[errors_before:])
                self.events.close(); self.events = None
                write_json(self.case_dir / 'case.json', case)
                write_json(self.out / 'manifest.json', self.manifest)


def validate_load(result, code, iterations=None):
    metrics = result['metrics']
    def fields(name):
        # `k6 run --summary-export` (used by run_load) writes fields directly on each metric,
        # e.g. {"count": 15001, "rate": ...} and a Rate as {"passes", "fails", "value"};
        # handleSummary() data nests them under "values". Missing fields stay KeyErrors.
        metric = metrics[name]
        return metric['values'] if 'values' in metric else metric
    def count(name):
        value = fields(name)['count']
        require(type(value) in (int, float) and value >= 0 and value % 1 == 0,
                'invalid k6 count: ' + name)
        return int(value)
    queued, completed, requests = count('coordination_queued'), count('iterations'), count('coordination_requests')
    sold_out = count('coordination_sold_out')
    require(code == 0, 'k6 threshold/process failed; original summary retained')
    errors = fields('coordination_system_errors')
    error_rate = errors['rate'] if 'rate' in errors else errors['value']
    require(type(error_rate) in (int, float) and error_rate == 0, 'unexpected client outcomes')
    require(completed == requests == queued + sold_out, 'incomplete or unaccounted client iterations')
    window = fields('coordination_request_started_ms')
    require(all(type(window.get(k)) in (int,float) and math.isfinite(window[k]) and window[k] > 0 for k in ('min','max')),
            'request-start window missing or invalid')
    require(window['min'] <= window['max'], 'invalid request-start timeline')
    target = iterations if iterations is not None else 200 * 75
    # Some k6 versions omit dropped_iterations when no drop was emitted. Preserve
    # the missing observation as null, and prove offered-load completion separately.
    dropped = count('dropped_iterations') if 'dropped_iterations' in metrics else None
    require(dropped in (None, 0), 'load generator dropped iterations')
    require(completed >= target, 'load generator did not complete the planned demand')
    if iterations is not None: require(completed == target, 'fixed-iteration demand changed')
    return {'first_request_started_at': window['min']/1000.0, 'last_request_started_at': window['max']/1000.0,
            'queued': queued, 'sold_out': sold_out, 'completed': completed, 'planned_demand': target,
            'dropped_iterations': dropped,
            'drop_metric_observed': dropped is not None,
            'coverage_basis': 'completed iterations == requests == queued + sold_out; completed >= planned demand'}


def validate_final(s, stock, queued, scarce):
    """Strict validator: missing observations are UNKNOWN/error, never substituted with zeros."""
    required = ['total','available','sold','success','orders_other','messages','cancelled','unfinished','failed_terminal',
        'duplicate_business','duplicate_order_id','consumed_without_success','redis_total','inflight','deadlines','buckets',
        'mq_ready','mq_unacked','mq_dead']
    absent = [key for key in required if key not in s]
    if absent: return ['unknown/missing: ' + ','.join(absent)]
    errors = []
    scalars = [key for key in required if key != 'buckets']
    if any(type(s[key]) is not int or s[key] < 0 for key in scalars):
        return ['unknown/invalid numeric observation']
    if type(queued) is not int or queued < 0:
        return ['invalid queued observation']
    if s['total'] != stock or s['available'] < 0 or s['sold'] < 0 or s['available'] + s['sold'] != stock:
        errors.append('MySQL stock conservation')
    if s['sold'] != s['success']: errors.append('sold != SUCCESS orders')
    if s['success'] != s['messages'] - s['cancelled']: errors.append('durable accepted messages != SUCCESS orders')
    if s['success'] < queued: errors.append('client queued exceeds durable successes')
    for field in ['orders_other','unfinished','failed_terminal','duplicate_business','duplicate_order_id','consumed_without_success',
                  'inflight','deadlines','mq_ready','mq_unacked','mq_dead']:
        if s[field] != 0: errors.append('%s=%s' % (field,s[field]))
    if not isinstance(s['buckets'], list) or len(s['buckets']) != 64 or any(type(v) is not int or v < 0 for v in s['buckets']):
        errors.append('invalid bucket observation')
    elif sum(s['buckets']) != s['redis_total']: errors.append('Redis aggregate != bucket sum')
    if s['redis_total'] != s['available']: errors.append('Redis != quiescent MySQL budget')
    if scarce and s['success'] != stock: errors.append('scarce scenario did not sell exactly the supply')
    return errors


def worker(args):
    suite = Suite(args)
    fatal = None
    try:
        suite.prepare()
        for case in suite.manifest['cases']:
            suite.execute_case(case)
    except BaseException as error:
        fatal = str(error)
        (suite.out / 'worker-error.txt').write_text(traceback.format_exc(), encoding='utf-8')
    finally:
        try: suite.stop_all()
        except Exception as cleanup: fatal = (fatal or '') + '; cleanup: ' + str(cleanup)
        if suite.events is not None: suite.events.close()
        cases = suite.manifest['cases']
        passed = sum(c['status'] == 'passed' for c in cases)
        completed = all(c['status'] in ('passed','failed') for c in cases)
        result = {'status': 'passed' if fatal is None and passed == len(cases) else 'failed',
                  'matrix_completed': completed, 'passed': passed, 'planned': len(cases),
                  'fatal_error': fatal, 'finished_at': time.time(),
                  'note': 'Not-run, unknown, failed observations and generator failures cannot count as correctness passes.'}
        suite.manifest.update(result)
        write_json(suite.out / 'manifest.json', suite.manifest)
        rows = ['# Multi-instance coordination evidence', '',
                'Candidate: `%s`' % suite.manifest.get('candidate_sha','UNKNOWN'),
                'Jar SHA-256: `%s`' % suite.manifest.get('jar_sha256','UNKNOWN'), '',
                '| Scenario | Status | Detail |', '|---|---|---|']
        for case in cases:
            final = case.get('final')
            detail = ('SUCCESS=%d, cancelled=%d, remaining=%d' % (final['success'],final['cancelled'],final['available'])) if final else case.get('error','Not observed')
            rows.append('| %s | %s | %s |' % (case['name'],case['status'],detail.replace('|','/').replace('\n',' ')))
        rows.extend(['', 'Signal faults are same-host process faults, not network partitions or Redis/MySQL failover.',
                     'Raw logs, SQL/Redis/broker observations and k6 summaries override this generated table.',
                     'Historical REPORT/evidence files were not overwritten. No capacity comparison is inferred.'])
        (suite.out / 'SUMMARY.md').write_text('\n'.join(rows)+'\n',encoding='utf-8')
        if suite.launcher_log.exists(): shutil.copyfile(str(suite.launcher_log), str(suite.out / 'launcher-log-snapshot.txt'))
        write_json(suite.out / 'DONE.json', result)
        try: archive(suite.out)
        except Exception:
            (suite.out / 'PACK_FAILED.txt').write_text(traceback.format_exc(), encoding='utf-8')
            raise
        print(json.dumps(result, ensure_ascii=False), flush=True)
        print('Archive: ' + str(suite.out) + '.tar.gz', flush=True)
    return 0 if suite.manifest['status'] == 'passed' else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--plan', action='store_true')
    for name in ('repo','root','out','launcher-log'): parser.add_argument('--' + name)
    args = parser.parse_args()
    if args.plan:
        print(json.dumps({'cases': plan(), 'configuration_not_results': True, 'pool_total': sum(POOL)}, indent=2)); return 0
    if not all((args.repo,args.root,args.out,args.launcher_log)): parser.error('use coordination-suite.sh start')
    def interrupted(signum, frame): raise InterruptedError('worker signal %d' % signum)
    signal.signal(signal.SIGTERM, interrupted); signal.signal(signal.SIGINT, interrupted)
    return worker(args)


if __name__ == '__main__':
    raise SystemExit(main())
