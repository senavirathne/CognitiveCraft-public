import os,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from compiler_budget import configure

class CompilerBudgetTest(unittest.TestCase):
    def native(self,nice=0,extra='C1 CompilerThre'):
        root=Path(tempfile.mkdtemp());self.addCleanup(lambda:__import__('shutil').rmtree(root))
        for tid,name in ((1,'Server thread'),(2,extra),(3,'C2 CompilerThre')):
            path=root/str(tid);path.mkdir();(path/'comm').write_text(name)
        real=Path
        def path(value):
            if value=='/proc/40':return root
            if value=='/proc/40/task':return root
            return real(value)
        values={1:0,2:nice,3:nice};changed=[]
        def priority(_,tid,value):values[tid]=value;changed.append((tid,value))
        patches=[patch('compiler_budget.Path',side_effect=path),patch('compiler_budget.birth',return_value=99),
                 patch('compiler_budget.os.sched_getaffinity',return_value={0,1}),
                 patch('compiler_budget.os.getpriority',side_effect=lambda _,tid:values[tid]),
                 patch('compiler_budget.os.setpriority',side_effect=priority)]
        for p in patches:p.start();self.addCleanup(p.stop)
        return changed
    def test_compilers_only_lower_priority_and_never_raise_an_existing_lower_priority(self):
        changed=self.native(15);value=configure(40,99,[0,1])
        self.assertCountEqual([(2,15),(3,15)],changed);self.assertEqual(0,value['serverThread']['niceAfter'])
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
