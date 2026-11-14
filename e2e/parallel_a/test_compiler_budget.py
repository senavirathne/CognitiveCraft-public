import os,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from compiler_budget import configure

class CompilerBudgetTest(unittest.TestCase):
    def native(self,nice=0,extra='C1 CompilerThre',extra_threads=()):
        root=Path(tempfile.mkdtemp());self.addCleanup(lambda:__import__('shutil').rmtree(root))
        threads=[(1,'Server thread'),(2,extra),(3,'C2 CompilerThre'),*extra_threads]
        for tid,name in threads:
            path=root/str(tid);path.mkdir();(path/'comm').write_text(name)
        real=Path
        def path(value):
            if value=='/proc/40':return root
            if value=='/proc/40/task':return root
            return real(value)
        values={tid:(nice if tid!=1 else 0) for tid,_ in threads};changed=[];affinities={n:{0,1} for n in [40,*values]}
        def priority(_,tid,value):values[tid]=value;changed.append((tid,value))
        patches=[patch('compiler_budget.Path',side_effect=path),patch('compiler_budget.birth',return_value=99),
                 patch('compiler_budget.os.sched_getaffinity',side_effect=lambda tid:affinities[tid]),
                 patch('compiler_budget.os.sched_setaffinity',side_effect=lambda tid,cpus:affinities.__setitem__(tid,set(cpus))),
                 patch('compiler_budget.os.getpriority',side_effect=lambda _,tid:values[tid]),
                 patch('compiler_budget.os.setpriority',side_effect=priority)]
        for p in patches:p.start();self.addCleanup(p.stop)
        return changed
    def test_compilers_only_lower_priority_and_never_raise_an_existing_lower_priority(self):
        changed=self.native(15);value=configure(40,99,[0,1])
        self.assertCountEqual([(2,15),(3,15)],changed);self.assertEqual(0,value['serverThread']['niceAfter'])
        self.assertEqual([0,1],value['serverThread']['cpusAfter']);self.assertTrue(all(t['cpusAfter']==[1] for t in value['compilerThreads']))
    def test_a06_server_core_reservation_is_bounded_and_idempotent_without_priority_raise(self):
        changed=self.native();first=configure(40,99,[0,1],server_cpu=0)
        self.assertEqual([0,1],first['cpus']);self.assertEqual(0,first['addedCores'])
        self.assertEqual([0],first['serverThread']['cpusAfter'])
        self.assertEqual(0,first['serverThread']['niceAfter'])
        self.assertTrue(all(row['cpusAfter']==[1] for row in first['compilerThreads']))
        second=configure(40,99,[0,1],server_cpu=0)
        self.assertEqual([0],second['serverThread']['cpusBefore'])
        self.assertEqual([0],second['serverThread']['cpusAfter'])
        self.assertTrue(all(tid!=1 for tid,_ in changed))
    def test_a04_real_storage_and_prestarted_baseline_workers_share_the_original_background_core(self):
        changed=self.native(extra_threads=((4,'cognitivecraft-'),(5,'runtime-bench04')))
        value=configure(40,99,[0,1],server_cpu=0,application_writers=True,require_writer_fixtures=True)
        self.assertEqual([0],value['serverThread']['cpusAfter']);self.assertEqual(0,value['serverThread']['niceAfter'])
        self.assertEqual([0,1],value['cpus']);self.assertEqual(0,value['addedCores'])
        self.assertEqual({'cognitivecraft-','runtime-bench04'},{r['name'] for r in value['applicationWriterThreads']})
        self.assertTrue(all(r['cpusAfter']==[1] and r['niceAfter']==10 for r in value['applicationWriterThreads']))
        self.assertTrue(all(tid!=1 for tid,_ in changed))
    def test_a04_missing_either_actual_writer_is_refused_before_any_budget_mutation(self):
        for name in ('cognitivecraft-','runtime-bench04'):
            with self.subTest(only=name):
                changed=self.native(extra_threads=((4,name),))
                with self.assertRaises(ValueError):configure(40,99,[0,1],server_cpu=0,application_writers=True,require_writer_fixtures=True)
                self.assertEqual([],changed)
    def test_server_reservation_outside_the_original_two_core_budget_is_refused(self):
        changed=self.native()
        with self.assertRaises(ValueError):configure(40,99,[0,1],server_cpu=2)
        self.assertEqual([],changed)
    def test_a06_storage_worker_inherited_server_core_is_moved_inside_original_budget(self):
        self.native(extra_threads=((4,'cognitivecraft-'),))
        os.sched_setaffinity(4,{0})
        value=configure(40,99,[0,1],server_cpu=0,application_writers=True,require_storage_writer=True)
        self.assertEqual([0],value['applicationWriterThreads'][0]['cpusBefore'])
        self.assertEqual([1],value['applicationWriterThreads'][0]['cpusAfter'])
        self.assertEqual(10,value['applicationWriterThreads'][0]['niceAfter'])
        self.assertEqual(0,value['addedCores']);self.assertEqual([0],value['serverThread']['cpusAfter'])
    def test_missing_a06_actual_storage_worker_refuses_before_mutation(self):
        changed=self.native(extra_threads=((4,'runtime-a-a06'),))
        with self.assertRaises(ValueError):configure(40,99,[0,1],server_cpu=0,application_writers=True,require_storage_writer=True)
        self.assertEqual([],changed)
    def test_a06_auxiliary_workers_share_core_one_without_raising_server_priority(self):
        changed=self.native(extra_threads=((4,'Netty Epoll IO #'),(5,'ZWorker#0'),(6,'cognitivecraft-')))
        value=configure(40,99,[0,1],server_cpu=0,application_writers=True,require_storage_writer=True,isolate_auxiliary=True)
        self.assertEqual({4,5},{r['tid'] for r in value['auxiliaryThreads']})
        self.assertTrue(all(r['cpusAfter']==[1] and r['niceAfter']==10 for r in value['auxiliaryThreads']))
        self.assertEqual([0],value['serverThread']['cpusAfter']);self.assertEqual(0,value['serverThread']['niceAfter'])
        self.assertEqual(0,value['addedCores']);self.assertTrue(all(tid!=1 for tid,_ in changed))
    def test_auxiliary_isolation_requires_the_existing_server_reservation(self):
        changed=self.native(extra_threads=((4,'Netty Epoll IO #'),))
        with self.assertRaises(ValueError):configure(40,99,[0,1],isolate_auxiliary=True)
        self.assertEqual([],changed)
    def test_missing_owned_observers_is_refused_before_budget_mutation(self):
        changed=self.native()
        with self.assertRaises(ValueError):configure(40,99,[0,1],require_observers=True)
        self.assertEqual([],changed)
    def test_reused_native_pid_is_refused_before_any_priority_mutation(self):
        changed=self.native()
        with patch('compiler_budget.birth',return_value=100),self.assertRaises(ValueError):configure(40,99,[0,1])
        self.assertEqual([],changed)
    def test_missing_compiler_tier_is_refused_before_any_priority_mutation(self):
        changed=self.native(extra='unrelated worker')
        with self.assertRaises(ValueError):configure(40,99,[0,1])
        self.assertEqual([],changed)
    def test_foreign_uid_is_refused_before_any_priority_mutation(self):
        changed=self.native()
        with patch('compiler_budget.os.getuid',return_value=os.getuid()+1),self.assertRaises(ValueError):configure(40,99,[0,1])
        self.assertEqual([],changed)
    def test_server_start_wrapper_preserves_derived_readiness_and_client_behavior(self):
        from freeze_runtime import attach
        events=[]
        class Derived:
            def start(self,component):events.append(('derived-ready',component));return 'original-result'
        h=Derived()
        with patch.dict(os.environ,{'COGNITIVECRAFT_A10_PROFILE':'A06'}),patch('compiler_budget.prepare',side_effect=lambda h:events.append(('compiler-budget','server'))):
            attach(h);self.assertEqual('original-result',h.start('server'));self.assertEqual('original-result',h.start('PlayerA'))
        self.assertEqual([('derived-ready','server'),('compiler-budget','server'),('derived-ready','PlayerA')],events)

if __name__=='__main__':unittest.main()
