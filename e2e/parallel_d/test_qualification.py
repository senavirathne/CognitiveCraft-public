"""D11-P01: synthetic readiness guard controls; zero native/client execution credit."""
import copy,tempfile,unittest
from pathlib import Path
from common import HERE,BASE,CONTRACT,read,registry,sha,contract_check
from qualification import HOST_WORLD,BOOT,native_context,native_producer,component_context,no_settlement,readiness_status

class QualificationTest(unittest.TestCase):
    def identity(self):return {'source_sha':'1'*40,'tree_sha':'2'*40,'jar_sha256':'3'*64,'workflow_run':123,'attempt':1}
    def native(self):return {'identity':self.identity(),'stage':'D11','world':HOST_WORLD,'seed':20261027,'fixture_sha256':'4'*64,'release_sha256':'3'*64,'connected_clients':0}
    def report(self):
        r={**self.identity(),'stage':'D11','owner':'D','base_sha':BASE,'docs_sha':'5'*40,'blocked':['J01 registration','J02 authority','J05 participant saves'],'regressions':[]}
        spec=registry('D11')
        for tier,n in [('unit',spec['unit_planned']),('python',len(spec['python_cases'])),('game_tests',len(spec['game_tests'])),('client_e2e',len(spec['client_e2e']))]:r[tier]={'status':'PASS','planned':n,'executed':n,'passed':n,'refused_expected':0,'failed':0,'inconclusive':0}
        return r
    def test_missing_native_producer_cannot_be_branch_ready(self):
        with tempfile.TemporaryDirectory()as d:
            root=Path(d);box=root/'incarnation';box.mkdir();(box/'stdout.log').write_text(BOOT);(box/'selected-fixture.jar').write_bytes(b'synthetic guard fixture; not Minecraft evidence');(box/'native-results.xml').write_text('<testsuite/>')
            row={'phase':'incarnation','selected_fixture_sha256':sha(box/'selected-fixture.jar'),'command':['net.fabricmc.loader.impl.launch.knot.KnotServer','-Dfabric-api.gametest','-Dcognitivecraft.parallelD.fixtureWorld='+HOST_WORLD]}
            self.assertEqual(native_producer(root,row),'incarnation');(box/'native-results.xml').unlink()
            with self.assertRaisesRegex(ValueError,'missing native producer'):native_producer(root,row)
    def test_wrong_native_fixture_or_world_cannot_be_branch_ready(self):
        good=self.native();native_context(good,self.identity(),'4'*64)
        for key,value in [('fixture_sha256','0'*64),('world','00000000-0000-4000-8000-000000000000')]:
            bad=copy.deepcopy(good);bad[key]=value
            with self.assertRaisesRegex(ValueError,'fixture|world'):native_context(bad,self.identity(),'4'*64)
    def test_wrong_client_fixture_or_world_cannot_be_branch_ready(self):
        good={'identity':self.identity(),'case':'D10-CL01','phase':'COLLECTED_RAW','world':'d1000000-0000-4000-8000-000000000001','fixture_sha256':'4'*64,'integration_root':None,'save_proof':None};component_context(good,self.identity(),'4'*64)
        for key,value in [('fixture_sha256','0'*64),('world',HOST_WORLD)]:
            bad=copy.deepcopy(good);bad[key]=value
            with self.assertRaises(ValueError):component_context(bad,self.identity(),'4'*64)
    def test_false_participant_save_or_settlement_cannot_be_branch_ready(self):
        good={'classification':'UNKNOWN_NATIVE_PARTICIPANT_PERSISTENCE','save_proof':None,'integration_root':None,'effect_id':None};no_settlement(good)
        for key,value in [('save_proof',{'saved':True}),('original_owner_settlement','SETTLED'),('participant_save_proof','claimed'),('tier','PARTICIPANT_SAVES_CONFIRMED')]:
            bad=copy.deepcopy(good);bad[key]=value
            with self.assertRaisesRegex(ValueError,'fabricated'):no_settlement(bad)
    def test_stale_shared_contract_cannot_be_branch_ready(self):
        contract_check()
        with tempfile.TemporaryDirectory()as d:
            root=Path(d);(root/'SHA256SUMS.json').write_bytes((HERE/'contracts/SHA256SUMS.json').read_bytes()+b' ')
            with self.assertRaisesRegex(ValueError,'contract manifest changed'):contract_check(root)
    def test_incomplete_or_failed_mandatory_tier_cannot_be_branch_ready(self):
        for tier in ('unit','python','game_tests','client_e2e'):
            bad=self.report();bad[tier]['passed']-=1;bad[tier]['failed']=1
            with self.assertRaisesRegex(ValueError,'mandatory tier'):readiness_status(bad)
        bad=self.report();bad['regressions']=['new regression']
        with self.assertRaisesRegex(ValueError,'regression'):readiness_status(bad)
    def test_local_owned_pass_has_no_branch_readiness(self):
        good=self.report();good.update(workflow_run=None,attempt=None);self.assertEqual(readiness_status(good),'OWNED_GATES_PASS')
        good['attempt']=1
        with self.assertRaisesRegex(ValueError,'CI run'):readiness_status(good)
    def test_complete_pinned_ci_counts_are_eligible_after_raw_verification(self):
        good=self.report();self.assertEqual(readiness_status(good),'BRANCH_READY');good['client_e2e'].update(passed=26,refused_expected=27);self.assertEqual(readiness_status(good),'BRANCH_READY')
        good['docs_sha']=None
        with self.assertRaisesRegex(ValueError,'pin missing'):readiness_status(good)
