"""Detached 60s A/B + within-build pool ablation + labelled knee probes.
Requires the existing /data/ms native installation. Only resets its disposable
benchmark database/Redis/queue; never invokes git checkout, push, or paid APIs.
Python 3.6+, stdlib. Run via pool-budget-suite.sh so FD 9 retains the process lock.
"""
import argparse
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tarfile
import time
import traceback

BASE = '29b794e9ac9ba3e8c73643c7de6b7b802a81df0c'
RATES = [1000, 2000, 2200, 2400, 2600, 2800, 3000, 4000, 5000]
COMMON = ['--spring.datasource.hikari.maximum-pool-size=40',
          '--spring.datasource.hikari.minimum-idle=8',
          '--spring.datasource.hikari.connection-timeout=2000',
          '--seckill.capacity.activity-cache-ttl=250ms',
          '--seckill.capacity.initial-sending-enabled=true',
          '--seckill.mq-consumer.concurrent-consumers=8',
          '--seckill.mq-consumer.max-concurrent-consumers=32',
          '--seckill.mq-consumer.prefetch-count=100']


def build_plan(rounds=3, rates=None):
    rates = list(RATES if rates is None else rates)
    if rounds < 1 or rounds > 20 or not rates or any(r <= 0 for r in rates) or rates != sorted(set(rates)):
        raise ValueError('rounds must be 1..20; rates must be unique, positive and ascending')
    cases = []
    def add(phase, variant, number, ladder):
        label = '%02d-%s-%s-round%d' % (len(cases) + 1, phase, variant, number)
        cases.append({'label': label, 'phase': phase, 'variant': variant,
                      'round': number, 'rates': list(ladder)})
    # AB, BA, AB ...: primary historical baseline vs selected experimental split.
    for number in range(1, rounds + 1):
        order = ['baseline', 'split28'] if number % 2 else ['split28', 'baseline']
        for variant in order:
            add('ab', variant, number, rates)
    # Same candidate binary, Latin rotation: isolates partitioning from safety/fusion changes.
    variants = ['shared', 'split28', 'split20']
    for number in range(1, rounds + 1):
        for offset in range(3):
            add('ablation', variants[(offset + number - 1) % 3], number, rates)
    # Separate from headline performance. All four configurations at both historical knees.
    for rate in (3000, 4000):
        for variant in ('baseline', 'shared', 'split28', 'split20'):
            add('diagnostic', variant, 1, [rate])
    return cases


def app_args(variant):
    args = list(COMMON)
    if variant in ('baseline', 'shared'):
        return args + ['--seckill.pool-budget.enabled=false']
    if variant not in ('split28', 'split20'):
        raise ValueError('unknown variant: ' + variant)
    return args + ['--seckill.pool-budget.enabled=true', '--seckill.pool-budget.total-connections=40',
                   '--seckill.pool-budget.consumer-connections=' + ('28' if variant == 'split28' else '20')]


def write(path, value):
    path = Path(path)
    tmp = path.with_name(path.name + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.replace(str(tmp), str(path))


def sha256(path):
    h = hashlib.sha256()
    with open(str(path), 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def stop_child(child):
    if child is None or child.poll() is not None:
        return
    try:
        os.killpg(child.pid, signal.SIGTERM)
        child.wait(timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except OSError:
            pass
        child.wait(timeout=10)


def command(args, log, env, cwd=None, timeout=300, check=True):
    with open(str(log), 'w') as output:
        child = subprocess.Popen([str(x) for x in args], stdout=output, stderr=subprocess.STDOUT,
                                 cwd=str(cwd) if cwd else None, env=env, start_new_session=True)
        try:
            code = child.wait(timeout=timeout)
        except BaseException:
            stop_child(child)
            raise
    if check and code != 0:
        raise RuntimeError('command failed (%s): %s; see %s' % (code, args[0], log))
    return code


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, str(path))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def archive(out):
    # Hash only quiescent files. Launcher output is outside this directory.
    report = out / 'scripts' / 'pool-budget-report.py'
    if report.exists():
        module(report, 'budget_report').hash_manifest(out)
    dest = Path(str(out) + '.tar.gz')
    with tarfile.open(str(dest) + '.tmp', 'w:gz') as target:
        target.add(str(out), arcname=out.name)
    os.replace(str(dest) + '.tmp', str(dest))
    Path(str(dest) + '.sha256').write_text(sha256(dest) + '  ' + dest.name + '\n', encoding='utf-8')


def snapshot(repo, sha, destination, env, log):
    destination.mkdir()
    bundle = destination.parent / (destination.name + '.tar')
    command(['git', '-C', repo, 'archive', '--format=tar', '--output=' + str(bundle), sha], log, env)
    # Git archive of this user's repository, no arbitrary incoming archive.
    command(['tar', '-xf', bundle, '-C', destination], str(log) + '.extract', env)
    bundle.unlink()


def worker(args):
    out, root, repo = Path(args.out).resolve(), Path(args.root).resolve(), Path(args.repo).resolve()
    # Fail rather than pretending that a manually launched worker is protected.
    import fcntl  # worker is native Linux; pure plan/report helpers stay importable elsewhere
    fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB)
    manifest = {'schema': 1, 'baseline_sha': BASE, 'started_at': time.time(), 'cases': [],
                'method': {'duration_seconds': 60, 'disk_await_ms': 2, 'disk_noisy_seconds': 5,
                           'minimum_disk_coverage': .9, 'drain_quiet_seconds': 5,
                           'capacity_claim': '60s sampling only; not a soak test'}}
    samplers, handles = [], []
    env = os.environ.copy()
    scripts = out / 'scripts'
    app = scripts / 'app.sh'
    started_app = False
    status, error = 'failed', None
    try:
        rounds = int(os.environ.get('BUDGET_ROUNDS', '3'))
        rates = [int(v) for v in os.environ.get('BUDGET_RATES', ' '.join(map(str, RATES))).split()]
        manifest['cases'] = build_plan(rounds, rates)
        sha = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], universal_newlines=True).strip()
        dirty = subprocess.check_output(['git', '-C', str(repo), 'status', '--porcelain'], universal_newlines=True)
        if dirty.strip():
            raise RuntimeError('working tree is dirty; refusing an untraceable candidate build')
        subprocess.check_call(['git', '-C', str(repo), 'merge-base', '--is-ancestor', BASE, sha])
        manifest['candidate_sha'] = sha
        disk = os.environ.get('DISK_DEVICE', 'vdc')
        devices = [line.split()[2] for line in Path('/proc/diskstats').read_text().splitlines()]
        if not re.fullmatch(r'[a-zA-Z0-9_.-]+', disk) or disk not in devices:
            raise RuntimeError('data device not found: %s; set DISK_DEVICE to the verified data device' % disk)
        manifest['disk_device'] = disk
        manifest['disk_device_source'] = 'DISK_DEVICE override' if 'DISK_DEVICE' in os.environ else 'vdc from prior same-host evidence; verify mount.txt'
        for tool in ('mvn', 'java', 'k6', 'mysql', 'redis-cli', 'jstack', 'tar', 'bash', 'curl', 'docker', 'pgrep', 'findmnt', 'lsblk'):
            if shutil.which(tool) is None:
                raise RuntimeError('missing native prerequisite: ' + tool)
        if subprocess.call(['pgrep', '-x', 'k6'], stdout=subprocess.DEVNULL) == 0:
            raise RuntimeError('another k6 run is active; refusing overlapping load')
        command(['findmnt', '-T', root], out / 'mount.txt', env, check=False)
        command(['lsblk', '-o', 'NAME,MAJ:MIN,SIZE,TYPE,MOUNTPOINT'], out / 'lsblk.txt', env, check=False)
        command(['bash', '-c', 'uname -a; lscpu; free -h; java -version; mvn -version; k6 version'],
                out / 'host.txt', env)
        sources = out / 'sources'; sources.mkdir()
        snapshot(repo, sha, sources / 'candidate', env, out / 'snapshot-candidate.log')
        snapshot(repo, BASE, sources / 'baseline', env, out / 'snapshot-baseline.log')
        shutil.copytree(str(sources / 'candidate' / 'benchmark' / 'native-linux'), str(scripts))
        # One identical instrumentation/benchmark snapshot is used with both binaries.
        env.update({'MS_ROOT': str(root), 'MS_BIN': str(scripts), 'MS_REPO': str(sources / 'candidate'),
                    'MS_RESULTS': str(out), 'DUR': '60', 'DRAIN_MAX': os.environ.get('DRAIN_MAX', '600')})
        if not re.fullmatch(r'[1-9][0-9]*', env['DRAIN_MAX']):
            raise ValueError('DRAIN_MAX must be a positive integer')
        manifest['drain_max_seconds'] = int(env['DRAIN_MAX'])
        jars = {}
        (out / 'jars').mkdir()
        for version in ('baseline', 'candidate'):
            source = sources / version
            command(['mvn', '-B', '-DskipTests', 'package'], out / ('build-%s.log' % version),
                    env, source, timeout=1800)
            found = list((source / 'target').glob('*.jar'))
            if len(found) != 1:
                raise RuntimeError('expected exactly one executable jar in ' + str(source / 'target'))
            jar = out / 'jars' / (version + '.jar')
            shutil.copy2(str(found[0]), str(jar))
            jars[version] = jar
            manifest[version + '_jar_sha256'] = sha256(jar)
            # Do not duplicate target/ reports and large jars in source evidence.
            shutil.rmtree(str(source / 'target'))
        write(out / 'manifest.json', manifest)
        previous_log = root / 'logs' / 'app.log'
        if previous_log.exists():
            with previous_log.open('rb') as src, gzip.open(str(out / 'pre-suite-app.log.gz'), 'wb') as dest:
                shutil.copyfileobj(src, dest)
        command(['bash', app, 'stop'], out / 'initial-stop.log', env, timeout=60)
        for filename, parameters in (('metrics-sampler.py', [out / 'metrics.jsonl']),
                                     ('disk-sampler.py', [disk, out / 'disk.jsonl'])):
            handle = open(str(out / (filename + '.log')), 'w'); handles.append(handle)
            child = subprocess.Popen(['python3', str(scripts / filename)] + [str(x) for x in parameters],
                                     env=env, stdout=handle, stderr=subprocess.STDOUT, start_new_session=True)
            samplers.append(child)
        time.sleep(2)
        if any(child.poll() is not None for child in samplers):
            raise RuntimeError('side sampler exited before the first case')
        for case in manifest['cases']:
            folder = out / case['label']; folder.mkdir()
            case['started_at'] = time.time()
            options = app_args(case['variant'])
            jar = jars['baseline' if case['variant'] == 'baseline' else 'candidate']
            case['jar_sha256'] = sha256(jar)
            (folder / 'app-args.txt').write_text('\n'.join(options) + '\n', encoding='utf-8')
            write(out / 'manifest.json', manifest)
            try:
                command(['python3', scripts / 'pool-budget-observe.py', 'reset-stopped',
                         sources / 'candidate', root], folder / 'cold-reset.log', env, timeout=90)
                started_app = True  # start may launch JVM then fail health; still stop it
                command(['bash', app, 'start', jar] + options, folder / 'start.log', env, timeout=390)
                time.sleep(10)
                command(['curl', '-fsS', '--max-time', '5', 'http://localhost:18080/actuator/prometheus'],
                        folder / 'startup-prometheus.txt', env, timeout=10, check=False)
                case_env = env.copy()
                case_env['CAP_DIAGNOSTIC'] = '1' if case['phase'] == 'diagnostic' else '0'
                code = command(['bash', scripts / 'cap-step.sh', case['label']] + list(map(str, case['rates'])),
                               folder / 'ladder.log', case_env,
                               timeout=len(case['rates']) * (int(env['DRAIN_MAX']) + 240) + 120, check=False)
                case['exit'] = code
                # 20 = not drained, 21 = k6 artifact/infrastructure error; retain and move to a
                # fresh JVM only after stop. Script/config failures must not waste the whole matrix.
                if code not in (0, 20, 21):
                    raise RuntimeError('unexpected ladder exit %s; see %s' % (code, folder / 'ladder.log'))
            finally:
                case['finished_at'] = time.time()
                # Stop before the next reset, including after a baseline's pinned deliveries.
                command(['bash', app, 'stop'], folder / 'stop.log', env, timeout=60)
                started_app = False
                if previous_log.exists():
                    with previous_log.open('rb') as src, gzip.open(str(folder / 'app.log.gz'), 'wb') as dest:
                        shutil.copyfileobj(src, dest)
                write(out / 'manifest.json', manifest)
            if any(child.poll() is not None for child in samplers):
                raise RuntimeError('side sampler died during suite; retain the incomplete evidence')
        status = 'completed'
    except BaseException as ex:
        error = str(ex)
        (out / 'worker-error.txt').write_text(traceback.format_exc(), encoding='utf-8')
    finally:
        for child in samplers:
            stop_child(child)
        for handle in handles:
            handle.close()
        if started_app and app.exists():
            try:
                command(['bash', app, 'stop'], out / 'final-stop.log', env, timeout=60)
            except Exception as ex:
                error = (error or '') + '; cleanup: ' + str(ex)
                status = 'failed'
        manifest.update({'finished_at': time.time(), 'status': status, 'error': error})
        write(out / 'manifest.json', manifest)
        reporting = scripts / 'pool-budget-report.py'
        if reporting.exists():
            try:
                rows = module(reporting, 'budget_report').write_report(out)
                manifest['observed_steps'] = sum(r['status'] == 'observed' for r in rows)
                manifest['planned_steps'] = len(rows)
                manifest['correctness_false'] = sum(r.get('correctness') is False for r in rows)
                manifest['correctness_unknown'] = sum(r.get('correctness') is None for r in rows if r['status'] == 'observed')
                write(out / 'manifest.json', manifest)
            except Exception as ex:
                error = (error or '') + '; report: ' + str(ex)
                status = 'failed'
        manifest.update({'status': status, 'error': error})
        write(out / 'manifest.json', manifest)
        launcher = Path(args.launcher_log)
        if launcher.exists():
            shutil.copyfile(str(launcher), str(out / 'launcher-log-snapshot.txt'))
        write(out / 'DONE.json', {'status': status, 'error': error, 'finished_at': time.time(),
                                 'note': 'completed means matrix orchestration finished, NOT all samples passed'})
        # Packing is attempted for failures too; its separate checksum covers the final archive.
        try:
            archive(out)
        except Exception:
            (out / 'PACK_FAILED.txt').write_text(traceback.format_exc(), encoding='utf-8')
            raise
        print('Finished:', status, 'archive:', str(out) + '.tar.gz', flush=True)
    return 0 if status == 'completed' else 1


def interrupted(signum, _frame):
    raise InterruptedError('worker interrupted by signal %s' % signum)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--plan', action='store_true')
    parser.add_argument('--repo'); parser.add_argument('--root'); parser.add_argument('--out')
    parser.add_argument('--launcher-log')
    args = parser.parse_args()
    if args.plan:
        print(json.dumps(build_plan(), indent=2)); return 0
    if not all((args.repo, args.root, args.out, args.launcher_log)):
        parser.error('worker paths required; use the shell launcher')
    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGTERM, interrupted)
    return worker(args)


if __name__ == '__main__':
    raise SystemExit(main())
