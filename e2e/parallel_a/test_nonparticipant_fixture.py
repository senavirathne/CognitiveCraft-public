import copy,unittest
from pathlib import Path
from nonparticipant_fixture import layout,protects,setup
from recovery_cases import originals,positions

class NonparticipantFixtureTests(unittest.TestCase):
    def test_original_a05_source_and_destination_and_work_cells_stay_clear(self):
        from generalization_support import at,DST
        points=[at(x,2) for x in range(6,14)]
        task={'source':{prefix+axis:fn(p[i] for p in points) for prefix,fn in [('min',min),('max',max)]
                        for i,axis in enumerate(('X','Y','Z'))},
              'destination':dict(zip(('x','y','z'),DST))}
        before=copy.deepcopy(task);self.assertTrue(protects(task))
        for wall in layout():
            self.assertTrue(all(abs(wall[0]-p[0])+abs(wall[2]-p[2])>1 for p in [*points,DST]))
        self.assertEqual(task,before)

    def test_profiles_without_this_fixture_are_refused_before_any_setup(self):
        from types import SimpleNamespace
        for profile in ('A08',):
            with self.assertRaises(AssertionError):setup(SimpleNamespace(actions_active=False,a10_profile=profile))

    def test_each_harness_sets_up_once_and_participating_profiles_are_not_changed(self):
        from unittest.mock import patch
        from baseline import RuntimeAHarness
        from deposits import DepositHarness
        from recovery_native import RecoveryHarness
        from lifecycle_connected import LifecycleHarness
        scenarios=[(RuntimeAHarness,p,1) for p in ('A00','A01','A02','A03','A04')]
        scenarios +=[(DepositHarness,'A05',1),(RecoveryHarness,'A06',1),(RecoveryHarness,'A09',1),
                     (LifecycleHarness,'A07',0),(LifecycleHarness,'A08',0)]
        for cls,profile,expected in scenarios:
            with self.subTest(profile=profile),patch('generalization_support.AssessmentHarness.reset',return_value={'parent':True}),patch('nonparticipant_fixture.setup') as setup:
                h=object.__new__(cls);h.a10_profile=profile;h.move_companion=False;h.snapshot=lambda:{'fixture':True}
                result=h.reset({},table=False)
                self.assertEqual(setup.call_count,expected)
                self.assertEqual(result,{'fixture':True} if expected else {'parent':True})

    def test_actual_setup_uses_fresh_process_bound_snapshot_without_activation_subclass(self):
        import json,tempfile
        from types import SimpleNamespace
        from unittest.mock import patch
        from nonparticipant_fixture import review_record
        for profile in ('A00','A01','A02','A03','A04','A05','A06','A07','A09'):
            with self.subTest(profile=profile),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);(root/'server').mkdir()
                process={'kind':'server','pid':17,'start':100,'releaseSha256':'jar'}
                (root/'server/process.json').write_text(json.dumps(process))
                actor={'uuid':'foreign','alive':True,'baby':False,'noAI':False,'controlled':False,'pos':[82.5,208,65.5]}
                snapshot={'pid':17,'time':123457,'actors':[actor],
                          'blocks':{','.join(map(str,p)):'minecraft:glass' for p in layout()},
                          'knownPlayers':{'PlayerB':{'uuid':'owner','citizens':{'citizens':[
                              {'actor':{'citizenId':'citizen','entityId':'foreign'},'availability':'LOADED'}]}}}}
                calls=[];rejections=[]
                def wait(predicate,message,timeout):
                    if message.startswith('Actual ordinary'):
                        for bad in ({'time':123455},{'pid':18}):
                            stale={**snapshot,**bad};(root/'server/snapshot.json').write_text(json.dumps(stale))
                            rejections.append(predicate())
                        (root/'server/snapshot.json').write_text(json.dumps(snapshot))
                    result=predicate();self.assertIsNotNone(result);return result
                h=SimpleNamespace(root=root,actions_active=False,a10_profile=profile,
                    entities={'PlayerA':'primary','PlayerB':'foreign'},citizens={'PlayerB':'citizen'},
                    players={'PlayerB':'owner'},snapshot=lambda:snapshot,wait=wait,
                    console=lambda commands:calls.append(commands),event=lambda *args:None)
                with patch('nonparticipant_fixture.time.time',return_value=123.456),patch('baseline.native_process',return_value={'namespaceProcess':process}):
                    record=setup(h)
                self.assertFalse(hasattr(h,'activated'))
                self.assertEqual(rejections,[None,None]);self.assertEqual(len(calls),1)
                self.assertEqual(record['setupIssuedAtMillis'],123456)
                self.assertEqual(review_record(root,record,profile,'jar')['status'],'PASS_APPLIED_NORMAL_AI_IDLE_ACTOR_FIXTURE')

    def test_all_live_lifecycle_targets_and_the_distinct_successor_stay_clear(self):
        points=[[67,208,68],[73,208,73],[77,208,72],[76,208,74],[74,208,69],[68,208,68]]
        for p in points:
            task={'source':{prefix+axis:p[i] for prefix in ('min','max') for i,axis in enumerate(('X','Y','Z'))},
                  'destination':dict(zip(('x','y','z'),[77,208,72]))}
            self.assertTrue(protects(task))
            self.assertTrue(all(abs(w[0]-p[0])+abs(w[2]-p[2])>1 for w in layout()))

    def test_every_frozen_task_and_its_work_positions_stay_clear(self):
        cases=originals(Path(__file__).with_name('a06_original_cases.json'));before=copy.deepcopy(cases)
        for case in cases:
            self.assertTrue(protects(case))
            targets=[*positions(case),[case['destination'][k] for k in ('x','y','z')]]
            for wall in layout():
                self.assertTrue(all(abs(wall[0]-p[0])+abs(wall[2]-p[2])>1 for p in targets))
        self.assertEqual(cases,before)

    def test_an_overlapping_task_is_refused_without_changing_it(self):
        case=copy.deepcopy(originals(Path(__file__).with_name('a06_original_cases.json'))[0])
        case['source'].update(minX=81,maxX=84,minZ=64,maxZ=67);before=copy.deepcopy(case)
        with self.assertRaisesRegex(ValueError,'overlaps'):protects(case)
        self.assertEqual(case,before)

if __name__=='__main__':unittest.main()
