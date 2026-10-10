"""Offline tests with synthetic fixtures; not native performance measurements."""
import ast
import importlib.util
import json
import os
import shutil
import sys
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
BIN = ROOT / 'benchmark' / 'native-linux'


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), str(BIN / (name + '.py')))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


suite = module('pool-budget-suite')
report = module('pool-budget-report')
observe = module('pool-budget-observe')
metrics = module('metrics-sampler')


def state(nonterminal=0, unacked=0, count=10):
    return {'db': {'messages': count, 'nonterminal': nonterminal, 'consumed': count,
                   'timeout': 0, 'dead': 0, 'orders': count, 'success': count},
            'mq': {'messages_ready': 0, 'messages_unacknowledged': unacked}, 't': 100}


def final(count=10):
    value = state(count=count)
    value['checks'] = {'duplicate_groups': 0, 'duplicate_order_ids': 0,
                       'segment_available': 200 - count, 'stock_available': 200}
    return value


class PlanTests(unittest.TestCase):
    def test_primary_ab_alternates_starting_version(self):
        cases = suite.build_plan()
        ab = [c['variant'] for c in cases if c['phase'] == 'ab']
        self.assertEqual(['baseline', 'split28', 'split28', 'baseline', 'baseline', 'split28'], ab)

    def test_ablation_is_same_candidate_with_latin_rotation(self):
        cases = [c for c in suite.build_plan() if c['phase'] == 'ablation']
        self.assertEqual(['shared', 'split28', 'split20'], [c['variant'] for c in cases[:3]])
        self.assertEqual(['split28', 'split20', 'shared'], [c['variant'] for c in cases[3:6]])
        self.assertEqual(['split20', 'shared', 'split28'], [c['variant'] for c in cases[6:9]])
        self.assertTrue(all(c['variant'] != 'baseline' for c in cases))

    def test_all_planned_cells_and_diagnostics_are_explicit(self):
        cases = suite.build_plan()
        self.assertEqual(23, len(cases))
        self.assertEqual(23, len(set(c['label'] for c in cases)))
        self.assertEqual(143, sum(len(c['rates']) for c in cases))
        self.assertEqual(8, sum(c['phase'] == 'diagnostic' for c in cases))
        self.assertEqual([1000, 2000, 2200, 2400, 2600, 2800, 3000, 4000, 5000], cases[0]['rates'])

    def test_plan_rejects_ambiguous_or_nonpositive_ladders(self):
        for rounds, rates in [(0, [1]), (21, [1]), (1, []), (1, [2, 1]), (1, [1, 1]), (1, [0])]:
            with self.subTest(rounds=rounds, rates=rates), self.assertRaises(ValueError):
                suite.build_plan(rounds, rates)

    def test_each_configuration_keeps_total_and_listener_settings(self):
        for name in ('baseline', 'shared', 'split28', 'split20'):
            args = suite.app_args(name)
            self.assertIn('--spring.datasource.hikari.maximum-pool-size=40', args)
            self.assertIn('--spring.datasource.hikari.minimum-idle=8', args)
            self.assertIn('--seckill.mq-consumer.concurrent-consumers=8', args)
            self.assertIn('--seckill.mq-consumer.max-concurrent-consumers=32', args)
        self.assertIn('--seckill.pool-budget.enabled=false', suite.app_args('shared'))
        self.assertIn('--seckill.pool-budget.consumer-connections=28', suite.app_args('split28'))
        self.assertIn('--seckill.pool-budget.consumer-connections=20', suite.app_args('split20'))


class MetricsTests(unittest.TestCase):
    def test_pool_labels_do_not_overwrite_and_legacy_totals_still_work(self):
        parsed = metrics.parse_prometheus('''hikaricp_connections_active{application="mini",pool="admission"} 12
hikaricp_connections_active{application="mini",pool="consumer"} 28
hikaricp_connections_max{application="mini",pool="admission"} 12
hikaricp_connections_max{application="mini",pool="consumer"} 28
hikaricp_connections_acquire_seconds_max{pool="admission"} 0.5
hikaricp_connections_acquire_seconds_max{pool="consumer"} 0.2
seckill_mq_total{application="mini",result="consume_retry_requeued"} 9
''')
        self.assertEqual(40, parsed['app']['hikaricp_connections_active'])
        self.assertEqual(40, parsed['app']['hikaricp_connections_max'])
        self.assertEqual(.5, parsed['app']['hikaricp_connections_acquire_seconds_max'])
        self.assertEqual(12, parsed['pools']['admission']['hikaricp_connections_active'])
        self.assertEqual(28, parsed['pools']['consumer']['hikaricp_connections_active'])
        self.assertEqual(9, parsed['app']['seckill_mq_total{result="consume_retry_requeued"}'])

    def test_pool_stats_use_counter_deltas_not_mean_of_means(self):
        def point(count, seconds):
            return {'pools': {'consumer': {'hikaricp_connections_max': 28,
                    'hikaricp_connections_acquire_seconds_count': count,
                    'hikaricp_connections_acquire_seconds_sum': seconds},
                    'admission': {'hikaricp_connections_max': 12}}}
        stats, valid = report.pool_statistics([point(10, 1), point(30, 1.4)], {'consumer': 28, 'admission': 12})
        self.assertTrue(valid)
        self.assertEqual(20, stats['consumer']['acquire_count_delta'])
        self.assertEqual(20, stats['consumer']['acquire_ms'])

    def test_counter_reset_and_absent_pools_are_unknown(self):
        data = [{'pools': {'x': {'hikaricp_connections_acquire_seconds_count': n,
                              'hikaricp_connections_acquire_seconds_sum': n}}} for n in (10, 1)]
        stats, valid = report.pool_statistics(data, {'shared': 40})
        self.assertIsNone(stats['x']['acquire_ms'])
        self.assertIsNone(valid)

    def test_hidden_third_pool_fails_budget_evidence(self):
        data = [{'pools': {k: {'hikaricp_connections_max': v}
                          for k, v in [('admission', 12), ('consumer', 28), ('oops', 40)]}}]
        self.assertFalse(report.pool_statistics(data, {'admission': 12, 'consumer': 28})[1])

    def test_shared_pool_name_may_vary_but_limit_may_not(self):
        for maximum in (40, 80):
            data = [{'pools': {'HikariPool-1': {'hikaricp_connections_max': maximum}}}]
            self.assertEqual(maximum == 40, report.pool_statistics(data, {'shared': 40})[1])


class EvidenceTests(unittest.TestCase):
    def test_disk_coverage_and_noise_predeclared_boundary(self):
        records = [{'t': 100 + i, 'w_await_ms': 3 if i < 5 else .3} for i in range(60)]
        self.assertEqual('noisy', report.disk_window(records, 100, 60)['class'])
        records[4]['w_await_ms'] = 2
        self.assertEqual('low-stall', report.disk_window(records, 100, 60)['class'])
        self.assertEqual('unknown', report.disk_window(records[:53], 100, 60)['class'])
        self.assertEqual('unknown', report.disk_window([], 100, 60)['class'])

    def test_duplicate_disk_lines_do_not_fabricate_full_coverage(self):
        records = [{'t': 100.1, 'w_await_ms': .3}] * 60
        self.assertEqual('unknown', report.disk_window(records, 100, 60)['class'])

    def test_admission_uses_original_threshold_and_never_passes_missing_data(self):
        def m(iters, dropped, errors):
            return {'iterations': {'values': {'count': iters}},
                    'dropped_iterations': {'values': {'count': dropped}},
                    'system_error_rate': {'values': {'rate': errors}}}
        self.assertTrue(report.admission_pass(m(100, 1, .01), 99))
        self.assertFalse(report.admission_pass(m(100, 2, .01), 99))
        self.assertFalse(report.admission_pass(m(0, 0, 0), 0))
        self.assertIsNone(report.admission_pass({}, 0))
        self.assertIsNone(report.admission_pass(m(100, 0, 0), 1))

    def test_client_timeout_does_not_make_durable_extra_orders_duplicates(self):
        self.assertTrue(report.correctness(final(11), {'drained': True}, 10, 200))
        self.assertFalse(report.correctness(final(9), {'drained': True}, 10, 200))

    def test_final_failure_and_stock_mismatch_do_not_count_as_success(self):
        f = final(); f['db']['dead'] = 1
        self.assertFalse(report.correctness(f, {'drained': True}, 10, 200))
        f = final(); f['checks']['segment_available'] = 100
        self.assertFalse(report.correctness(f, {'drained': True}, 10, 200))
        self.assertFalse(report.correctness(final(), {'drained': False}, 10, 200))
        self.assertIsNone(report.correctness({'db_error': 'timeout'}, {'drained': True}, 10, 200))

    def test_not_run_cells_and_missing_json_stay_visible(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'manifest.json').write_text(json.dumps({'cases': suite.build_plan(1, [1000])}))
            rows = report.write_report(root)
            self.assertEqual(13, len(rows))
            self.assertTrue(all(r['status'] == 'not-run' for r in rows))
            self.assertIn('unknown', (root / 'REPORT.md').read_text())

    def test_hash_manifest_recomputes_and_ignores_itself_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'sample.txt').write_text('synthetic')
            report.hash_manifest(root)
            first = json.loads((root / 'SHA256SUMS.json').read_text())
            report.hash_manifest(root)
            self.assertEqual(first, json.loads((root / 'SHA256SUMS.json').read_text()))
            (root / 'sample.txt').write_text('changed')
            report.hash_manifest(root)
            self.assertNotEqual(first['sample.txt'], json.loads((root / 'SHA256SUMS.json').read_text())['sample.txt'])

    def test_partial_jsonl_retains_valid_records_and_original_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'samples.jsonl'; p.write_text('{"t":1}\n{"t":')
            self.assertEqual([{'t': 1}], report.jsonl(p))
            self.assertEqual('{"t":1}\n{"t":', p.read_text())


class DrainTests(unittest.TestCase):
    def run_drain(self, producer, maximum=12):
        clock = [0.0]
        def tick(seconds): clock[0] += seconds
        with tempfile.TemporaryDirectory() as tmp:
            prefix = Path(tmp) / 'case'
            with mock.patch.object(observe, 'state', side_effect=producer), \
                 mock.patch.object(observe.time, 'monotonic', side_effect=lambda: clock[0]), \
                 mock.patch.object(observe.time, 'time', side_effect=lambda: clock[0]), \
                 mock.patch.object(observe.time, 'sleep', side_effect=tick):
                result = observe.drain(prefix, maximum)
            saved = json.loads(Path(str(prefix) + '-drain.json').read_text())
            return result, saved

    def test_unknown_sql_or_mq_is_not_empty(self):
        self.assertFalse(observe.empty({'db_error': 'pool timeout', 'mq': state()['mq']}))
        self.assertFalse(observe.empty({'db': state()['db'], 'mq_error': 'unavailable'}))
        self.assertFalse(observe.empty(state(unacked=1)))
        self.assertFalse(observe.empty(state(nonterminal=1)))

    def test_recovery_requires_quiet_window_after_failure(self):
        calls = [0]
        def next_state():
            calls[0] += 1
            return {'db_error': 'synthetic failure'} if calls[0] < 3 else state()
        result, saved = self.run_drain(next_state)
        self.assertTrue(result)
        self.assertGreaterEqual(saved['finished_at'], 7)

    def test_pinned_manual_delivery_times_out_not_false_drained(self):
        result, saved = self.run_drain(lambda: state(unacked=1), 6)
        self.assertFalse(result)
        self.assertFalse(saved['drained'])

    def test_late_durable_acceptance_restarts_the_quiet_window(self):
        calls = [0]
        def next_state():
            calls[0] += 1
            return state(count=10 if calls[0] <= 4 else 11)
        result, saved = self.run_drain(next_state)
        self.assertTrue(result)
        self.assertGreaterEqual(saved['finished_at'], 9)

    def test_cold_reset_refuses_existing_pid_before_destructive_io(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'run').mkdir(); (root / 'run' / 'app.pid').write_text('123')
            with mock.patch.object(observe, 'mysql') as sql, self.assertRaises(RuntimeError):
                observe.reset_stopped(root, root)
            sql.assert_not_called()


@unittest.skipUnless(os.name == 'posix' and shutil.which('bash'), 'native shell checks require Linux/POSIX')
class ScriptTests(unittest.TestCase):
    def test_shell_syntax_and_python36_grammar(self):
        for name in ('app.sh', 'cap-step.sh', 'pool-budget-suite.sh'):
            subprocess.run(['bash', '-n', str(BIN / name)], check=True)
        subprocess.run(['bash', '-n', str(ROOT / 'benchmark' / 'reset-env.sh')], check=True)
        for name in ('pool-budget-suite.py', 'pool-budget-observe.py', 'pool-budget-report.py',
                     'metrics-sampler.py', 'sampler.py'):
            ast.parse((BIN / name).read_text(), feature_version=(3, 7) if sys.version_info >= (3, 13) else (3, 6))

    def test_plan_command_does_not_require_native_installation(self):
        result = subprocess.run(['bash', str(BIN / 'pool-budget-suite.sh'), 'plan'],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, check=True)
        self.assertEqual(23, len(json.loads(result.stdout)))

    def test_app_stop_refuses_dangerous_pid_without_removing_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'run').mkdir(); (root / 'env.sh').write_text('')
            pid = root / 'run' / 'app.pid'; pid.write_text('1')
            env = dict(os.environ, MS_ROOT=str(root))
            result = subprocess.run(['bash', str(BIN / 'app.sh'), 'stop'], env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual('1', pid.read_text())

    def test_cold_reset_entry_checks_pid_before_any_external_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'run').mkdir(); (root / 'run' / 'app.pid').write_text('123')
            result = subprocess.run(['bash', str(ROOT / 'benchmark' / 'reset-env.sh'), '--cold-only'],
                                    env=dict(os.environ, MS_ROOT=str(root)),
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(2, result.returncode)
            self.assertIn('refusing cold reset', result.stderr.decode())

    def test_launcher_refuses_existing_run_directory_before_spawning(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'env.sh').write_text('')
            (root / 'results' / 'same').mkdir(parents=True)
            fake = root / 'fake-bin'; fake.mkdir()
            git = fake / 'git'; git.write_text('#!/bin/sh\necho deadbeef\n'); git.chmod(0o755)
            env = dict(os.environ, MS_ROOT=str(root), BUDGET_RUN_ID='same', PATH=str(fake) + ':' + os.environ['PATH'])
            result = subprocess.run(['bash', str(BIN / 'pool-budget-suite.sh'), 'start'], env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / 'results' / 'same' / 'worker.pid').exists())


    def test_ladder_never_resets_next_rate_after_unproven_drain(self):
        # Execute the real shell orchestrator with synthetic adapters, no services.
        for drain_rc in (0, 20):
            with self.subTest(drain_rc=drain_rc), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                fake, adapters, bench = root / 'fake', root / 'adapters', root / 'repo' / 'benchmark'
                for d in (fake, adapters, bench / 'results'): d.mkdir(parents=True)
                (root / 'env.sh').write_text('')
                for name in ('k6', 'java', 'lscpu', 'redis-cli', 'sleep'):
                    p = fake / name; p.write_text('#!/bin/sh\nexit 0\n'); p.chmod(0o755)
                (adapters / 'sampler.py').write_text('import time\ntime.sleep(20)\n')
                (adapters / 'pool-budget-observe.py').write_text(
                    'import os,sys,json\n'
                    'if sys.argv[1] == "drain": sys.exit(int(os.environ["DRAIN_RC"]))\n')
                (adapters / 'cap-report.py').write_text(
                    'import sys\nif sys.argv[1] == "--stop": sys.exit(1)\nprint("{}")\n')
                (bench / 'reset-env.sh').write_text('echo "reset $1" >> "$TRACE"\n')
                (bench / 'run-k6.sh').write_text(
                    '#!/bin/bash\nwhile (( $# )); do if [[ $1 == --name ]]; then name=$2; fi; shift; done\n'
                    'echo "{}" > "$(dirname "$0")/results/stamp-$name-summary.json"\n')
                (bench / 'run-verify.sh').write_text(
                    'echo synthetic > "$(dirname "$0")/results/stamp-$1-verify.txt"\n')
                trace = root / 'trace.txt'
                env = dict(os.environ, MS_ROOT=str(root), MS_REPO=str(root / 'repo'),
                           MS_BIN=str(adapters), PATH=str(fake) + ':' + os.environ['PATH'],
                           DRAIN_RC=str(drain_rc), TRACE=str(trace), CAP_DIAGNOSTIC='0')
                result = subprocess.run(['bash', str(BIN / 'cap-step.sh'), 'synthetic', '1000', '2000'],
                                        env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
                self.assertEqual(drain_rc, result.returncode, result.stderr.decode())
                self.assertEqual(['reset 120000'] if drain_rc else ['reset 120000', 'reset 240000'],
                                 trace.read_text().splitlines())

    def test_detached_failure_keeps_lock_and_packages_failure_evidence(self):
        # Real launcher/fd inheritance/finalizer, no DB/GitHub/Java calls.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'env.sh').write_text('')
            fake = root / 'fake-bin'; fake.mkdir()
            git = fake / 'git'
            git.write_text('#!/bin/sh\ncase "$*" in *porcelain*) exit 0;; *) echo deadbeef;; esac\n')
            git.chmod(0o755)
            env = dict(os.environ, MS_ROOT=str(root), BUDGET_RUN_ID='failure-fixture',
                       DISK_DEVICE='deliberately-absent-device', PATH=str(fake) + ':' + os.environ['PATH'])
            result = subprocess.run(['bash', str(BIN / 'pool-budget-suite.sh'), 'start'], env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
            self.assertEqual(0, result.returncode, result.stderr)
            folder = root / 'results' / 'failure-fixture'
            checksum = Path(str(folder) + '.tar.gz.sha256')
            deadline = time.monotonic() + 10
            while not checksum.exists() and time.monotonic() < deadline:
                time.sleep(.05)
            self.assertTrue(checksum.exists(), (folder / 'worker-error.txt').read_text() if (folder / 'worker-error.txt').exists() else result.stdout)
            done = json.loads((folder / 'DONE.json').read_text())
            self.assertEqual('failed', done['status'])
            self.assertIn('data device not found', done['error'])
            self.assertTrue((folder / 'worker-error.txt').exists())


if __name__ == '__main__':
    unittest.main()
