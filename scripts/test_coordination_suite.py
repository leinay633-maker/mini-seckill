"""Offline control/validator regressions. Synthetic records are NOT devcloud measurements."""
import argparse
import ast
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'benchmark/native-linux/coordination-suite.py'
SHELL = ROOT / 'benchmark/native-linux/coordination-suite.sh'
spec = importlib.util.spec_from_file_location('coordination_suite', str(SCRIPT))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def final():
    return dict(total=10, available=7, sold=3, success=3, orders_other=0, messages=4, cancelled=1,
        unfinished=0, failed_terminal=0, duplicate_business=0, duplicate_order_id=0, consumed_without_success=0,
        redis_total=7, inflight=0, deadlines=0, buckets=[1]*7+[0]*57, mq_ready=0, mq_unacked=0, mq_dead=0)


def load(count=15000):
    metrics = {}
    for name, value in [('iterations',count),('coordination_requests',count),('coordination_queued',count),('coordination_sold_out',0)]:
        metrics[name] = {'values': {'count': value}}
    metrics['coordination_system_errors'] = {'values': {'rate': 0}}
    metrics['coordination_request_started_ms'] = {'values': {'min':1700000000000,'max':1700000075000}}
    return {'metrics': metrics}


class FinalValidatorTest(unittest.TestCase):
    def test_positive_conservation_including_cancelled_not_accepted(self):
        self.assertEqual([], m.validate_final(final(),10,3,False))
    def test_all_missing_and_unknown_fields_rejected(self):
        for key in final():
            for unknown in (None, 'UNKNOWN', True):
                with self.subTest(key=key, value=unknown):
                    s=final();s[key]=unknown;self.assertTrue(m.validate_final(s,10,3,False))
            s=final();del s[key];self.assertTrue(m.validate_final(s,10,3,False))
    def test_no_negative_counter_is_a_zero(self):
        for key in final():
            if key == 'buckets': continue
            s=final();s[key]=-1;self.assertTrue(m.validate_final(s,10,3,False))
    def test_every_forbidden_terminal_and_nonempty_observation_fails(self):
        for key in ['orders_other','unfinished','failed_terminal','duplicate_business','duplicate_order_id',
                    'consumed_without_success','inflight','deadlines','mq_ready','mq_unacked','mq_dead']:
            s=final();s[key]=1;self.assertTrue(m.validate_final(s,10,3,False),key)
    def test_not_just_mysql_oversell_check(self):
        for key in ['total','available','sold','success','messages','cancelled','redis_total']:
            s=final();s[key]+=1;self.assertTrue(m.validate_final(s,10,3,False),key)
    def test_bucket_shape_sum_and_nonintegral_values_rejected(self):
        for buckets in [[],[7],[0]*64,[7]+[0]*62,[-1,8]+[0]*62,[0.1]*64,[None]*64]:
            s=final();s['buckets']=buckets;self.assertTrue(m.validate_final(s,10,3,False))
    def test_accepted_client_cannot_be_lost(self):
        self.assertTrue(m.validate_final(final(),10,4,False))
    def test_scarcity_requires_all_supply_sold_not_merely_no_oversell(self):
        self.assertTrue(m.validate_final(final(),10,3,True))
        s=final();s.update(total=3,available=0,redis_total=0,buckets=[0]*64)
        self.assertEqual([],m.validate_final(s,3,3,True))


class LoadValidatorTest(unittest.TestCase):
    def test_missing_zero_drop_metric_is_preserved_as_unobserved(self):
        s=m.validate_load(load(),0)
        self.assertIsNone(s['dropped_iterations']);self.assertFalse(s['drop_metric_observed'])
    def test_reported_zero_drop_is_observed(self):
        s=load();s['metrics']['dropped_iterations']={'values':{'count':0}}
        self.assertTrue(m.validate_load(s,0)['drop_metric_observed'])
    def test_dropped_or_underdriven_not_a_pass(self):
        s=load();s['metrics']['dropped_iterations']={'values':{'count':1}}
        with self.assertRaises(AssertionError):m.validate_load(s,0)
        with self.assertRaises(AssertionError):m.validate_load(load(14999),0)
    def test_actual_historical_extra_boundary_iteration_is_allowed(self):
        self.assertEqual(15001,m.validate_load(load(15001),0)['completed'])
    def test_fixed_demand_exact(self):
        self.assertEqual(410,m.validate_load(load(410),0,410)['completed'])
        for n in (409,411):
            with self.assertRaises(AssertionError):m.validate_load(load(n),0,410)
    def test_missing_counters_or_error_observations_fail(self):
        for name in load()['metrics']:
            s=load();del s['metrics'][name]
            with self.assertRaises(KeyError):m.validate_load(s,0)
    def test_errors_process_failure_and_unaccounted_requests_fail(self):
        with self.assertRaises(AssertionError):m.validate_load(load(),99)
        s=load();s['metrics']['coordination_system_errors']['values']['rate']=0.01
        with self.assertRaises(AssertionError):m.validate_load(s,0)
        s=load();s['metrics']['coordination_requests']['values']['count']-=1
        with self.assertRaises(AssertionError):m.validate_load(s,0)
    def test_missing_or_invalid_request_start_window_cannot_prove_live_overlap(self):
        for values in [{}, {'min':0,'max':1}, {'min':2,'max':1}, {'min':1,'max':float('nan')}]:
            s=load();s['metrics']['coordination_request_started_ms']['values']=values
            with self.assertRaises(AssertionError):m.validate_load(s,0)
    def test_nonfinite_or_invalid_counts_rejected(self):
        for value in [float('nan'),float('inf'),None,-1,1.5,True,'15000']:
            s=load();s['metrics']['iterations']['values']['count']=value
            with self.assertRaises(AssertionError):m.validate_load(s,0)


class OrchestrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        args=argparse.Namespace(repo=str(ROOT),root=str(self.root),out=str(self.root/'result'),launcher_log=str(self.root/'launcher.log'))
        self.args=args;self.out=Path(args.out);self.out.mkdir();self.suite=m.Suite(args)
    def tearDown(self): self.tmp.cleanup()
    def test_plan_covers_nine_distinct_cases_and_global_pool40(self):
        self.assertEqual(9,len(m.plan()));self.assertEqual(9,len({c['name'] for c in m.plan()}))
        self.assertTrue(all(c['status']=='not_run' for c in m.plan()));self.assertEqual(40,sum(m.POOL))
    def test_python36_syntax(self):
        ast.parse(SCRIPT.read_text(encoding='utf-8'),feature_version=(3,6))
    def test_keys_match_production_keys(self):
        java=(ROOT/'src/main/java/com/example/miniseckill/util/RedisKeyUtil.java').read_text()
        self.assertIn('seckill:lock:reconcile:',java)
        self.assertEqual('seckill:lock:reconcile:1:1001',m.LOCK)
        self.assertEqual(m.TOTAL+':bucket:0','seckill:stock:1:1001:bucket:0')
    def test_invalid_request_ids_refuse_sql_concatenation(self):
        for value in ["x' OR 1=1",'a'*65,'', 'x;DROP TABLE t', 'x\ny']:
            with self.assertRaises(ValueError): m.sql_id(value)
    def test_archive_content_and_external_sidecar_verified(self):
        (self.out/'example.txt').write_text('fixture, not a result\n')
        m.write_json(self.out/'DONE.json',{'status':'failed','matrix_completed':False});m.archive(self.out)
        tgz=Path(str(self.out)+'.tar.gz')
        self.assertEqual(m.digest(tgz),Path(str(tgz)+'.sha256').read_text().split()[0])
        with tarfile.open(str(tgz)) as archive:
            checks=archive.extractfile(self.out.name+'/SHA256SUMS').read().decode()
            for line in checks.splitlines():
                sha,name=line.split('  ',1)
                self.assertEqual(sha,hashlib.sha256(archive.extractfile(self.out.name+'/'+name).read()).hexdigest())
    def test_symlink_evidence_refused(self):
        (self.out/'real').write_text('x');(self.out/'link').symlink_to('real')
        with self.assertRaises(RuntimeError): m.archive(self.out)
    def test_preflight_failure_still_packages_all_not_run(self):
        with mock.patch.object(m.Suite,'prepare',side_effect=RuntimeError('injected missing prerequisite')),mock.patch.object(m.Suite,'stop_all'):
            self.assertEqual(1,m.worker(self.args))
        summary=json.loads((self.out/'DONE.json').read_text())
        self.assertEqual('failed',summary['status']);self.assertFalse(summary['matrix_completed'])
        manifest=json.loads((self.out/'manifest.json').read_text())
        self.assertTrue(all(c['status']=='not_run' for c in manifest['cases']))
        self.assertTrue(Path(str(self.out)+'.tar.gz.sha256').exists())
    def test_unknown_observation_retries_without_substituting_zero(self):
        self.suite.events=io.StringIO()
        with mock.patch.object(self.suite,'observe',side_effect=[KeyError('messages_ready'),final()]),mock.patch.object(m.time,'sleep'):
            value=self.suite.observe_retry('snapshot.jsonl')
        self.assertEqual(final(),value);self.assertIn('observation_unknown',self.suite.events.getvalue())
    def test_event_disk_failure_does_not_interrupt_cleanup_authority(self):
        self.suite.events=mock.Mock();self.suite.events.write.side_effect=OSError('disk full')
        self.suite.event('signal',pid=123)
        self.assertEqual(1,len(self.suite.evidence_errors))
    def test_recycled_pid_never_signalled(self):
        n=object.__new__(m.Node);n.child=mock.Mock(pid=12345);n.start_ticks='old'
        with mock.patch.object(m,'proc_identity',return_value={'state':'S','start_ticks':'new'}),mock.patch.object(m.os,'kill') as kill:
            with self.assertRaises(AssertionError):n.signal(signal.SIGTERM)
            kill.assert_not_called()
    def test_matching_pid_but_wrong_marker_never_signalled(self):
        n=object.__new__(m.Node);n.child=mock.Mock(pid=12345);n.start_ticks='a';n.marker='mine';n.suite=mock.Mock(jar=self.root/'jar')
        with mock.patch.object(m,'proc_identity',return_value={'state':'S','start_ticks':'a'}),mock.patch.object(m,'cmdline',return_value=[b'other']),mock.patch.object(m.os,'kill') as kill:
            with self.assertRaises(AssertionError):n.signal(signal.SIGTERM)
            kill.assert_not_called()
    @unittest.skipUnless(Path('/proc/self/stat').exists(),'Linux proc identity required')
    def test_real_stop_continue_kill_on_owned_disposable_child_not_a_jvm_test(self):
        self.suite.jar=self.root/'not-a-java-jar';n=object.__new__(m.Node);n.suite=self.suite;n.index=0
        n.marker='--seckill.coordination.run-id=offline-control-test';n.faults=self.root/'faults';n.faults.mkdir()
        n.output=open(os.devnull,'w')
        n.child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)',str(self.suite.jar),n.marker],stdout=n.output)
        n.start_ticks=m.proc_identity(n.child.pid)['start_ticks']
        try:
            m.wait_for(lambda: n.marker.encode() in m.cmdline(n.child.pid), 5, 'owned child exec/readiness')
            n.signal(signal.SIGSTOP);self.assertEqual('T',m.proc_identity(n.child.pid)['state'])
            n.signal(signal.SIGCONT);n.signal(signal.SIGKILL);self.assertFalse(n.alive())
        finally:
            try: n.close()
            finally:
                # A test-created, unreaped child is still ours even if a deliberately
                # strict command-line assertion failed during interpreter startup.
                if n.child.poll() is None: n.child.kill()
                n.child.wait(timeout=5); n.output.close()
    def test_failed_launch_identity_persistence_kills_unregistered_child(self):
        self.suite.case_dir=self.out;self.suite.jar=self.root/'jar';self.suite.manifest['jar_sha256']='fixture'
        child=mock.Mock(pid=12345);child.poll.return_value=None
        with mock.patch.object(m.subprocess,'Popen',return_value=child),mock.patch.object(m,'proc_identity',return_value={'state':'S','start_ticks':'1'}),mock.patch.object(m,'write_json',side_effect=OSError('disk full')):
            with self.assertRaises(OSError):m.Node(self.suite,0)
        child.kill.assert_called_once();child.wait.assert_called_once()
    def test_reset_failure_is_failed_not_running_and_still_saved(self):
        with mock.patch.object(self.suite,'reset',side_effect=RuntimeError('reset refused')),mock.patch.object(self.suite,'observe',side_effect=RuntimeError('unknown')),mock.patch.object(self.suite,'stop_all'):
            self.suite.execute_case(self.suite.manifest['cases'][0])
        result=json.loads((self.out/m.plan()[0]['name']/'case.json').read_text())
        self.assertEqual('failed',result['status']);self.assertIn('reset refused',result['error'])


@unittest.skipUnless(os.name=='posix','Bash and flock require POSIX')
class RealLauncherTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        (self.root/'env.sh').write_text(':\n');(self.root/'results').mkdir();(self.root/'run').mkdir()
        self.env=dict(os.environ,MS_ROOT=str(self.root),COORD_RUN_ID='offline-fixture')
    def tearDown(self):self.tmp.cleanup()
    def test_collision_fails_before_worker_start(self):
        out=self.root/'results/offline-fixture';out.mkdir();(out/'keep').write_text('original')
        r=subprocess.run(['bash',str(SHELL),'start'],env=self.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.assertNotEqual(0,r.returncode);self.assertEqual('original',(out/'keep').read_text());self.assertFalse((out/'worker.pid').exists())
    def test_same_lock_as_pool_budget_and_coordination_prevents_launch(self):
        import fcntl
        for name in ['pool-budget-suite.lock','coordination-suite.lock']:
            with (self.root/'run'/name).open('w') as hold:
                fcntl.flock(hold,fcntl.LOCK_EX|fcntl.LOCK_NB)
                r=subprocess.run(['bash',str(SHELL),'start'],env=self.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
                self.assertEqual(2,r.returncode);self.assertFalse((self.root/'results/offline-fixture').exists())
    def test_actual_detached_worker_preflight_failure_archives_without_services(self):
        # Expose only launcher tools + fake java; missing Maven forces failure
        # BEFORE any stop/migration/reset. Never use a live fixture in this test.
        bins=self.root/'bin';bins.mkdir()
        for name in ['bash','dirname','mkdir','cp','flock','git','nohup','python3']:
            (bins/name).symlink_to(shutil.which(name))
        (bins/'java').symlink_to(shutil.which('false'))
        (self.root/'env.sh').write_text('export PATH="%s"\n' % bins)
        r=subprocess.run([shutil.which('bash'),str(SHELL),'start'],env=self.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=5)
        self.assertEqual(0,r.returncode,r.stderr.decode())
        out=self.root/'results/offline-fixture';end=time.monotonic()+15
        while not Path(str(out)+'.tar.gz.sha256').exists() and time.monotonic()<end:time.sleep(0.05)
        self.assertTrue(Path(str(out)+'.tar.gz.sha256').exists(),(self.root/'results/offline-fixture.launcher.log').read_text())
        result=json.loads((out/'DONE.json').read_text());self.assertEqual('failed',result['status']);self.assertIn('mvn',result['fatal_error'])
        self.assertFalse((out/'schema-migration.json').exists())


if __name__=='__main__':unittest.main()
