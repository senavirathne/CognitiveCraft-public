"""C08-P01 synthetic negative controls of final readiness; no native/model execution credit."""
import copy
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from common import CONTRACT,HERE,ROOT,EvidenceError,git,read
from qualification import ADAPTERS,expected_inventory,promote

class QualificationTests(unittest.TestCase):
    def fixture(self):
        registry=read(HERE/'registries/C08.json');required=expected_inventory(registry)
        def metric(n):return dict(planned=n,executed=n,passed=n,failed=0,refused_expected=0,inconclusive=0)
        q=dict(stage='C08',gate_status='OWNED_GATES_PASS',source_sha='1'*40,tree_sha='2'*40,jar_sha256='4'*64,contract_sha256=CONTRACT,counts=dict(unit=metric(2307+len(required['unit'])),python=metric(len(required['python']))),client_executed=3,client_tier='COMPONENT_CLIENT',language_messages_executed=60,genuine_provider_executed=6,selector_variations_executed=100,dialogue_sequences_executed=100,native_executed=0,workflow_run=123456,attempt=1,container_image_id='sha256:'+'5'*64)
        o=dict(source_sha=q['source_sha'],tree_sha=q['tree_sha'],docs_sha='3'*40,contract_sha256=CONTRACT,jars=dict.fromkeys(['candidate','native_regression','native_safety','provider','compiler','dialogue','selectors','benchmark'],q['jar_sha256']),cases=copy.deepcopy(required),language_counts=dict(planned=60,executed=60,passed=42,refused_expected=18,failed=0,inconclusive=0),job_calls=0,world_operation_calls=0,original_root_id=None,original_owner_id=None,a_settlement=None,production_activation=False,ownership_status='OWNED_GATES_PASS',predecessor_status='PASS',docs_status='PASS')
        return q,o,required
    def test_complete_consistent_control_is_ready_only_for_c_owned_component_gates(self):
        q,o,r=self.fixture();result=promote(q,o,r);self.assertEqual('BRANCH_READY',result['gate_status']);self.assertFalse(result['production_activation']);self.assertFalse(result['joint_stages_started']);self.assertEqual('BLOCKED_DEPENDENCY',result['physical_activity']['status']);self.assertEqual('BLOCKED_DEPENDENCY',result['restart_proof']['status']);self.assertEqual(6,len(result['required_j_adapters']))
    def test_missing_case_in_any_mandatory_tier_blocks_readiness(self):
        for tier in ['unit','python','clients','language','provider','selectors','dialogue']:
            q,o,r=self.fixture();o['cases'][tier].pop()
            with self.assertRaises(EvidenceError):promote(q,o,r)
    def test_duplicate_case_cannot_replace_a_missing_case_even_when_count_matches(self):
        for tier in ['unit','python','clients','language','provider','selectors','dialogue']:
            q,o,r=self.fixture();o['cases'][tier][1]=o['cases'][tier][0]
            with self.assertRaises(EvidenceError):promote(q,o,r)
    def test_mismatched_or_missing_jar_in_each_execution_phase_blocks_readiness(self):
        for phase in ['candidate','native_regression','native_safety','provider','compiler','dialogue','selectors','benchmark']:
            q,o,r=self.fixture();o['jars'][phase]='9'*64
            with self.assertRaises(EvidenceError):promote(q,o,r)
        q,o,r=self.fixture();o['jars'].pop('native_safety')
        with self.assertRaises(EvidenceError):promote(q,o,r)
    def test_false_a_settlement_root_owner_or_production_activation_is_rejected(self):
        for field,value in [('a_settlement',dict(outcome='SETTLED')),('original_root_id','forged'),('original_owner_id','forged'),('production_activation',True)]:
            q,o,r=self.fixture();o[field]=value
            with self.assertRaises(EvidenceError):promote(q,o,r)
    def test_invalid_contract_and_source_tree_or_docs_pins_block_readiness(self):
        for field,value in [('contract_sha256','0'*64),('source_sha','a'*40),('tree_sha','b'*40),('docs_sha',None)]:
            q,o,r=self.fixture();o[field]=value
            with self.assertRaises(EvidenceError):promote(q,o,r)
        q,o,r=self.fixture();q['contract_sha256']='0'*64
        with self.assertRaises(EvidenceError):promote(q,o,r)
    def test_missing_actual_ci_ownership_predecessor_or_docs_prevents_readiness(self):
        for field in ['workflow_run','attempt','container_image_id']:
            q,o,r=self.fixture();q[field]=None
            with self.assertRaises(EvidenceError):promote(q,o,r)
        for field in ['ownership_status','predecessor_status','docs_status']:
            q,o,r=self.fixture();o[field]='INCONCLUSIVE'
            with self.assertRaises(EvidenceError):promote(q,o,r)
    def test_expected_refusals_and_zero_effect_requirements_cannot_be_relabeled(self):
        for mutation in ['refusal','job','world','physical']:
            q,o,r=self.fixture()
            if mutation=='refusal':o['language_counts'].update(passed=60,refused_expected=0)
            elif mutation=='physical':q['native_executed']=1
            else:o['job_calls' if mutation=='job' else 'world_operation_calls']=1
            with self.assertRaises(EvidenceError):promote(q,o,r)
    def test_mutable_final_registry_cannot_drop_a_predecessor_method_or_language_row(self):
        original=read(HERE/'registries/C08.json')
        for key in ['regression_unit_cases','regression_python_cases','client_cases','language_cases','provider_cases']:
            altered=copy.deepcopy(original);altered[key].pop()
            with self.assertRaises(EvidenceError):expected_inventory(altered)
    def test_pass_summary_without_raw_evidence_cannot_qualify_through_the_cli(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'qualified.json').write_text('{"stage":"C08","gate_status":"BRANCH_READY"}');out=root/'checked.json'
            result=subprocess.run([sys.executable,str(HERE/'qualification.py'),'--root',temp,'--source',git(ROOT,'rev-parse','HEAD'),'--tree',git(ROOT,'rev-parse','HEAD^{tree}'),'--output',str(out)],cwd=ROOT,capture_output=True,text=True)
            self.assertEqual(2,result.returncode,result.stderr);checked=read(out);self.assertEqual('FAIL',checked['gate_status']);self.assertFalse(checked['branch_ready']);self.assertEqual(0,checked['client_executed'])
