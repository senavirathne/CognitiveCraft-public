import copy,json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock,patch
from stop_ack import container,acknowledge,review

ID='a'*64
def state(running=True):
    return {'Id':ID,'Config':{'Labels':{'com.docker.compose.project':'owned','com.docker.compose.service':'minecraft'}},
            'State':{'StartedAt':'original-start','Running':running,'Pid':40 if running else 0,
                     'Status':'running' if running else 'exited','ExitCode':0,'OOMKilled':False}}
PROCESS={'kind':'server','pid':8,'start':100,'releaseSha256':'jar'}

class StopAcknowledgementTest(unittest.TestCase):
    def test_completed_exit_matches_the_exact_owned_container(self):
        before=container([state()],ID,'owned');after=container([state(False)],ID,'owned')
        self.assertEqual('PASS_EXACT_CONTAINER_EXIT_ACKNOWLEDGED',acknowledge(before,after,'0',PROCESS,False)['status'])

    def test_empty_or_foreign_container_is_refused(self):
        for identifier,project in [('', 'owned'),(ID,'foreign')]:
            with self.assertRaises(ValueError):container([state()],identifier,project)
        value=state();value['Config']['Labels']['com.docker.compose.service']='client-a'
        with self.assertRaises(ValueError):container([value],ID,'owned')

    def test_running_restarted_oom_or_wrong_exit_cannot_acknowledge_a_stop(self):
        before=container([state()],ID,'owned');after=container([state(False)],ID,'owned')
        for key,value in [('running',True),('pid',40),('status','running'),('startedAt','replacement'),('oomKilled',True),('exitCode',137)]:
            changed=copy.deepcopy(after);changed[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):acknowledge(before,changed,'0',PROCESS,False)

    def test_wrapper_waits_for_exit_before_returning_without_starting_a_replacement(self):
        from freeze_runtime import stop
        order=[]
        with tempfile.TemporaryDirectory() as folder:
            h=SimpleNamespace(root=Path(folder),booted={'server'},project='owned',a10_profile='A03',
                              compose=Mock(return_value=SimpleNamespace(stdout=ID)),event=Mock(),
                              a10_original_stop=lambda component,hard=False:order.append(('original-stop',hard)))
            def docker(_, *args, **kwargs):
                order.append(args[0])
                return SimpleNamespace(stdout='0' if args[0]=='wait' else json.dumps([state(len(order)==1)]))
            with patch('freeze_runtime.docker',side_effect=docker),patch('suite.read',return_value=PROCESS):stop(h,hard=True)
            self.assertEqual(['inspect',('original-stop',True),'wait','inspect'],order)
            self.assertEqual(1,len(list(Path(folder).glob('a10-stop-ack-*.json'))));h.event.assert_called_once()

    def test_client_stop_does_not_change_its_existing_transport(self):
        from freeze_runtime import stop
        h=SimpleNamespace(a10_original_stop=Mock(return_value='unchanged'))
        with patch('freeze_runtime.docker') as docker:self.assertEqual('unchanged',stop(h,'PlayerA',hard=True))
        docker.assert_not_called();h.a10_original_stop.assert_called_once_with('PlayerA',hard=True)

    def test_independent_reader_requires_every_original_stopped_native_birth(self):
        before=container([state()],ID,'owned');after=container([state(False)],ID,'owned');row=acknowledge(before,after,'0',PROCESS,False);row['profile']='A03'
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'a10-stop-ack-one.json').write_text(json.dumps(row))
            self.assertEqual(1,review(root,'A03','jar',[PROCESS])['nativeBirths'])
            with self.assertRaises(AssertionError):review(root,'A03','jar',[dict(PROCESS,start=101)])

    def test_a03_recreates_only_the_acknowledged_stopped_container_once(self):
        from freeze_runtime import compose
        with tempfile.TemporaryDirectory() as folder:
            h=SimpleNamespace(root=Path(folder),project='owned',env={},a10_profile='A03',event=Mock(),
                              a10_stopped_server={'after':{'id':ID,'startedAt':'old-start'}})
            with patch('freeze_runtime.subprocess.run',return_value=SimpleNamespace(returncode=0,stdout='',stderr='')) as run:
                compose(h,'up','-d','--no-deps','minecraft');compose(h,'up','-d','--no-deps','minecraft')
            commands=[c.args[0] for c in run.call_args_list]
            self.assertIn('--force-recreate',commands[0]);self.assertNotIn('--force-recreate',commands[1])
            self.assertEqual(1,h.event.call_count);self.assertIsNone(h.a10_stopped_server)

    def test_other_profiles_keep_their_existing_environment_sensitive_start(self):
        from freeze_runtime import compose
        with tempfile.TemporaryDirectory() as folder:
            h=SimpleNamespace(root=Path(folder),project='owned',env={'A05_DEPOSIT_ENABLED':'false'},a10_profile='A05',event=Mock(),
                              a10_stopped_server={'after':{'id':ID,'startedAt':'old-start'}})
            with patch('freeze_runtime.subprocess.run',return_value=SimpleNamespace(returncode=0,stdout='',stderr='')) as run:
                compose(h,'up','-d','--no-deps','minecraft')
            self.assertNotIn('--force-recreate',run.call_args.args[0]);self.assertEqual(h.env,run.call_args.kwargs['env'])
            h.event.assert_not_called()

if __name__=='__main__':unittest.main()
