"""A10 acceptance guards. These synthetic contracts never count as native evidence."""
import copy,json,tempfile,unittest
from pathlib import Path

class OrchestratorIsolation(unittest.TestCase):
    def test_server_cores_are_reserved_within_the_existing_four_cores(self):
        from freeze_runtime import isolate_orchestrator
        with patch('freeze_runtime.os.sched_getaffinity',side_effect=[{0,1,2,3},{2,3}]),patch('freeze_runtime.os.sched_setaffinity') as apply:
            value=isolate_orchestrator('A06')
        apply.assert_called_once_with(0,{2,3});self.assertEqual([0,1],value['serverCores']);self.assertEqual(0,value['addedCores'])
    def test_missing_original_topology_fails_before_applying_affinity(self):
        from freeze_runtime import isolate_orchestrator
        with patch('freeze_runtime.os.sched_getaffinity',return_value={0,1}),patch('freeze_runtime.os.sched_setaffinity') as apply:
            with self.assertRaises(RuntimeError):isolate_orchestrator('A06')
        apply.assert_not_called()
    def test_every_original_profile_now_uses_the_same_finite_topology(self):
        from freeze_runtime import isolate_orchestrator
        for profile in CASES:
            with patch('freeze_runtime.os.sched_getaffinity',side_effect=[{0,1,2,3},{2,3}]),patch('freeze_runtime.os.sched_setaffinity') as apply:
                value=isolate_orchestrator(profile)
            self.assertTrue(value['isolationApplied']);apply.assert_called_once_with(0,{2,3})
from unittest.mock import patch
from zipfile import ZipFile
from freeze_plan import CASES,MODULES,REQUIRED_JOBS,compose_files,ledger,plan
from freeze_bundle import production
from freeze_aggregate import aggregate
from review_freeze import handoff_membership
from recovery_fixture import MEMBERSHIP,marker

class FrozenDenominator(unittest.TestCase):
    def test_every_original_profile_keeps_its_full_denominator_and_missing_case(self):
        self.assertEqual({'A00':4,'A01':2,'A02':5,'A03':7,'A04':10,'A05':15,'A06':21,'A07':27,'A08':21,'A09':9},{k:len(v) for k,v in CASES.items()})
        self.assertEqual(121,plan()['requiredGateCases']);self.assertEqual(2703,sum(MODULES.values()))
        for profile,ids in CASES.items():
            partial=ledger(profile,[{'id':ids[0],'status':'PASS'}]);self.assertEqual(len(ids),len(partial))
            self.assertTrue(all(r['collectionStatus']=='NOT_EXECUTED_REQUIRED' for r in partial[1:]))
    def test_duplicate_unplanned_and_substituted_case_ids_fail_closed(self):
        key=CASES['A00'][0]
        for rows in ([{'id':key},{'id':key}],[{'id':'synthetic-replacement'}]):
            with self.assertRaises(ValueError):ledger('A00',rows)
        with self.assertRaises(ValueError):compose_files('A10')
    def test_original_rpc_status_is_retained_without_fabricating_a_pass_string(self):
        row={'id':CASES['A03'][1],'status':{'status':'PASS','physicalExecution':'UNCHANGED_RAW'}}
        self.assertEqual(row['status'],ledger('A03',[row])[1]['collectionStatus'])
    def test_original_overlays_survive_pinned_build_transport(self):
        self.assertEqual(['e2e/compose.yml','e2e/parallel_a/compose-activity.yml','e2e/parallel_a/compose-leases.yml','e2e/parallel_a/compose-jobs.yml','e2e/parallel_a/compose-freeze.yml'],compose_files('A04'))
        self.assertIn('e2e/parallel_a/compose-lifecycle.yml',compose_files('A07'))
        self.assertIn('e2e/parallel_a/compose-activity-runtime.yml',compose_files('A09'))
    def test_production_jar_rejects_test_driver_and_duplicate_entries(self):
        with tempfile.TemporaryDirectory() as folder:
            jar=Path(folder)/'production.jar'
            with ZipFile(jar,'w') as z:z.writestr('dev/aivillages/fabric/AiVillages.class',b'fixture')
            self.assertEqual(64,len(production(jar)))
            with ZipFile(jar,'w') as z:z.writestr('dev/aivillages/fabric/RuntimeAObserver.class',b'fixture')
            with self.assertRaises(AssertionError):production(jar)

class CampaignAcceptance(unittest.TestCase):
    def populate(self,root):
        for profile,ids in CASES.items():
            path=root/profile/'review.json';path.parent.mkdir()
            value={'stage':'A10','profile':profile,'status':'PASS_INDEPENDENT_A10_PROFILE_REVIEW','source':'same-source','tree':'same-tree','productionSha256':'same-jar',
                   'requiredCases':len(ids),'cases':[{'id':id,'status':'PASS_ORIGINAL_CLASSIFICATION_RETAINED'} for id in ids],'observerSha256':'same-observer','clientSha256':'same-client'}
            path.write_text(json.dumps(value))
        return {k:{'result':'success'} for k in ('a04-contracts','a10-native',*REQUIRED_JOBS)}
    def test_complete_owned_campaign_still_does_not_claim_branch_ready(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);needs=self.populate(root);result=aggregate(root,'same-source','same-jar',needs)
            self.assertEqual(121,result['requiredGateCases']);self.assertFalse(result['branchReady']);self.assertFalse(result['mainMerged'])
    def test_missing_profile_case_different_jar_or_instrument_never_qualifies(self):
        for field,value in (('productionSha256','other-jar'),('observerSha256','other-observer'),('cases',[]),('status','FAIL')):
            with tempfile.TemporaryDirectory() as folder:
                root=Path(folder);needs=self.populate(root);path=root/'A00/review.json';row=json.loads(path.read_text());row[field]=value;path.write_text(json.dumps(row))
                with self.assertRaises(AssertionError):aggregate(root,'same-source','same-jar',needs)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);needs=self.populate(root);(root/'A07/review.json').unlink()
            with self.assertRaises(AssertionError):aggregate(root,'same-source','same-jar',needs)
    def test_failed_or_replaced_required_regression_is_not_hidden_by_native_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);needs=self.populate(root);needs[REQUIRED_JOBS[0]]['result']='failure'
            with self.assertRaises(AssertionError):aggregate(root,'same-source','same-jar',needs)
            needs[REQUIRED_JOBS[0]]['result']='success';needs['required_substitute']=needs.pop(REQUIRED_JOBS[0])
            with self.assertRaises(AssertionError):aggregate(root,'same-source','same-jar',needs)

class SavedHandoffGuard(unittest.TestCase):
    def fixture(self):
        row={'originalAtCut':{'attempts':[{'worker':{'entityId':'old'}}]},'successor':{'entityId':'new'},'key':{'step':'job'}}
        entries=[{'intent':{'key':{'step':'job','attempt':id},'actor':{'address':id}},'result':{'actor':{'incarnation':'inc-'+id,'revision':3}}} for id in ('old','new')]
        members=[{'entity':id,'role':'ACTOR','incarnation':'inc-'+id,'revision':3,'marker':json.dumps(marker(entry)),'present':id=='new'} for id,entry in zip(('old','new'),entries)]
        frame={'Position':[4,4],'Entities':[{'uuid':'new'}],MEMBERSHIP:json.dumps({'schema':1,'x':4,'z':4,'rows':members})}
        return row,entries,frame
    def run_review(self,row,entries,frame):
        with patch('review_freeze.read',return_value=row),patch('review_freeze.journal',return_value=({},entries)),patch('review_freeze.entities',return_value={'new':{}}),patch('review_freeze.regions',return_value=[frame]),patch('review_freeze.entity_uuid',side_effect=lambda e:e['uuid']):
            return handoff_membership(Path('synthetic-reader-contract'))
    def test_actual_old_absence_and_exact_result_metadata_are_required(self):
        row,entries,frame=self.fixture();self.assertEqual('PASS_SAVED_NATIVE_HANDOFF_MEMBERSHIP',self.run_review(row,entries,frame)['status'])
        state=json.loads(frame[MEMBERSHIP]);state['rows'][0]['present']=True;frame[MEMBERSHIP]=json.dumps(state)
        with self.assertRaises(AssertionError):self.run_review(row,entries,frame)
    def test_quarantine_or_changed_incarnation_cannot_be_called_a_saved_handoff(self):
        row,entries,frame=self.fixture();frame[MEMBERSHIP+'_original']={}
        with self.assertRaises(AssertionError):self.run_review(row,entries,frame)
        row,entries,frame=self.fixture();state=json.loads(frame[MEMBERSHIP]);state['rows'][0]['incarnation']='replacement';frame[MEMBERSHIP]=json.dumps(state)
        with self.assertRaises(AssertionError):self.run_review(row,entries,frame)

if __name__=='__main__':unittest.main()
