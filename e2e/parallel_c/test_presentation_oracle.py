import copy
import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
from common import EvidenceError,HERE,ROOT,digest,read
from client_conversation import CAMPAIGN,CAMPAIGN_SHA256,check_guards,check_view,native_player,verify

class PresentationOracleTests(unittest.TestCase):
    def view(self):
        expected=read(CAMPAIGN)['cases'][0]['steps'][2]
        return dict(schema='cc.c.presentation/1',audience=dict(player=native_player('CPlayerA')),scope=dict(principal=native_player('CPlayerA')),
                    status='WAITING',text=expected['expected_text'],accessible_text=expected['expected_text']+' Choices: Cancel.',choices=[dict(label='Cancel')],language='en',locale_key='interaction.waiting',activity={},question_id='opaque'),expected
    def test_frozen_two_client_campaign_has_eighteen_distinct_declared_steps(self):
        c=read(CAMPAIGN);self.assertEqual(CAMPAIGN_SHA256,digest(CAMPAIGN));self.assertEqual(['C06-CL01','C06-CL02'],[r['id'] for r in c['cases']]);steps=[s for c in c['cases'] for s in c['steps']];self.assertEqual(18,len(steps));self.assertEqual(18,len({s['id'] for s in steps}));self.assertEqual({'CPlayerA','CPlayerB'},{s['player'] for s in steps})
    def test_swapped_native_principal_cannot_receive_a_private_view(self):
        v,w=self.view();self.assertTrue(check_view(v,w,native_player('CPlayerA')))
        with self.assertRaises(EvidenceError):check_view(v,w,native_player('CPlayerB'))
    def test_mutated_shortage_or_invented_completion_is_rejected(self):
        v,w=self.view()
        for change in ({'text':"Waiting for 99 oak planks in Ada's chest."},{'status':'COMPLETED'}):
            changed=dict(v,**change)
            with self.assertRaises(EvidenceError):check_view(changed,w,native_player('CPlayerA'))
    def test_raw_uuid_or_lost_accessibility_is_rejected(self):
        v,w=self.view()
        for text in ('',v['accessible_text']+' '+native_player('CPlayerA')):
            with self.assertRaises(EvidenceError):check_view(dict(v,accessible_text=text),w,native_player('CPlayerA'))
    def test_redacted_denial_cannot_carry_a_root_or_private_label(self):
        v,w=self.view();w=dict(w,expected_status='DENIED',expected_text='I cannot share details for this activity.');v.update(status='DENIED',text=w['expected_text'],accessible_text=w['expected_text'],locale_key='interaction.denied',choices=[])
        with self.assertRaises(EvidenceError):check_view(v,w,native_player('CPlayerA'))
        v.update(activity=None,question_id=None);self.assertTrue(check_view(v,w,native_player('CPlayerA')))
        v['private']='Private secret chest'
        with self.assertRaises(EvidenceError):check_view(v,w,native_player('CPlayerA'))
    def guards(self):
        ready=['JobLifecycleStore','SurvivalGateway'];zero=dict(active_hooks=ready,job_calls=0,world_operation_calls=0)
        return [dict(kind='server-start',data=dict(guards=dict(active_hooks=[],job_calls=0,world_operation_calls=0))),
                dict(kind='guard-ready',data=dict(hook='SurvivalGateway')),dict(kind='guard-ready',data=dict(hook='JobLifecycleStore')),
                dict(kind='guards-armed',data=zero),dict(kind='request',data=dict(guards=zero)),dict(kind='server-stop',data=zero.copy())]
    def test_any_actual_job_or_world_call_fails_the_guard_oracle(self):
        check_guards(self.guards())
        for field in ('job_calls','world_operation_calls'):
            rows=self.guards();rows[-1]['data'][field]=1
            with self.assertRaises(EvidenceError):check_guards(rows)
    def test_missing_native_hooks_or_shutdown_evidence_cannot_pass(self):
        rows=self.guards();missing_ready=copy.deepcopy(rows);missing_ready.pop(1)
        missing_arm=[e for e in rows if e['kind']!='guards-armed']
        early=copy.deepcopy(rows);early.insert(1,early.pop(4))
        for candidate in (rows[:-1],missing_ready,missing_arm,early):
            with self.assertRaises(EvidenceError):check_guards(candidate)
    def test_a_pass_summary_without_actual_client_processes_cannot_qualify(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises((EvidenceError,FileNotFoundError)):verify(Path(temporary))
            output=Path(temporary)/'failed-oracle.json'
            result=subprocess.run([sys.executable,str(HERE/'verify.py'),'--stage','C06','--root',temporary,'--source','0'*40,'--tree','0'*40,'--jar',str(Path(temporary)/'missing.jar'),'--output',str(output)],cwd=ROOT,capture_output=True,text=True)
            self.assertEqual(2,result.returncode,result.stderr);record=json.loads(output.read_text());self.assertIn(record['gate_status'],('FAIL','BLOCKED_DEPENDENCY'));self.assertEqual(0,record['client_executed']);self.assertEqual(0,record['selector_variations_executed'])
    def test_unqualified_c05_cannot_be_used_as_a_presenter_predecessor(self):
        from predecessor import validate_records
        forged=dict(stage='C05',gate_status='OWNED_GATES_PASS',source_sha='0'*40)
        with self.assertRaises(EvidenceError):validate_records(forged,forged,'C05')
