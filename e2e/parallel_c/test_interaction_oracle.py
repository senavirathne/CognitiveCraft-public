import copy
import os
from pathlib import Path
import tempfile
import unittest
from common import EvidenceError,HERE,read
from client_conversation import native_player
from oracle import freeze,java_id,check_messages,check_output,check_provider

class InteractionOracleTests(unittest.TestCase):
    def sample(self):
        want=freeze()['cases'][12];native=native_player(want['player']);request=dict(id='00000000-0000-0000-0000-000000000001')
        text="Waiting for 2 oak planks in Ada's chest."
        packet=dict(schema='cc.c.safety-output/1',kind='SAFETY',case_id=want['id'],message_id=request['id'],native_player=native,session='00000000-0000-0000-0000-000000000002',citizen='Ada',classification='ASK',reason='AUTHORIZED_FACTS_ONLY',candidate=None,selector=None,confirmation_required=False,text=text,accessible_text=text,question_id=java_id('C07-question:'+native+':Ada'),root_id=java_id('C07-root:'+native+':Ada'),generation=1,facts_tier='SYNTHETIC_CONTRACT',production_enabled=False,a_settlement=None)
        return packet,want,native,request
    def test_frozen_sixty_messages_preserve_all_supplied_ids_and_expectations(self):
        rows=freeze()['cases'];self.assertTrue(check_messages(rows,rows));self.assertEqual(42,sum(r['expected_outcome']=='PASS' for r in rows));self.assertEqual(18,sum(r['expected_outcome']=='REFUSED_EXPECTED' for r in rows))
    def test_mutated_shortage_fails_with_a_positive_control(self):
        p,w,n,r=self.sample();self.assertTrue(check_output(p,w,n,r));p['text']=p['accessible_text']="Waiting for 1 oak planks in Ada's chest."
        with self.assertRaises(EvidenceError):check_output(p,w,n,r)
    def test_swapped_native_principal_fails_even_with_matching_message(self):
        p,w,n,r=self.sample()
        with self.assertRaises(EvidenceError):check_output(p,w,native_player('CPlayerB'),r)
    def test_omitted_duplicate_or_reordered_messages_fail(self):
        rows=freeze()['cases']
        for bad in (rows[:-1],[rows[0]]+rows[:-1],rows[::-1]):
            with self.assertRaises(EvidenceError):check_messages(bad,rows)
    def test_invented_completion_or_settlement_fails(self):
        p,w,n,r=self.sample()
        for changes in (dict(classification='COMPLETED'),dict(text='Done, delivered everything.'),dict(a_settlement=dict(outcome='SETTLED')),dict(production_enabled=True)):
            with self.assertRaises(EvidenceError):check_output(dict(p,**changes),w,n,r)
    def test_missing_or_swapped_message_and_root_question_fail(self):
        p,w,n,r=self.sample()
        for field in ('message_id','root_id','question_id'):
            mutated=dict(p);mutated[field]='00000000-0000-0000-0000-000000000099'
            with self.assertRaises(EvidenceError):check_output(mutated,w,n,r)
    def test_private_labels_raw_uuids_and_missing_accessibility_fail(self):
        p,w,n,r=self.sample()
        for changes in (dict(accessible_text=''),dict(accessible_text=p['text']+' '+n),dict(text='Private secret chest')):
            with self.assertRaises(EvidenceError):check_output(dict(p,**changes),w,n,r)
    def offline(self):
        import hashlib,json
        w=freeze()['cases'][0];facts=dict(mode='SMALL_TALK',language='en',key='conversation.small_talk',materials=[])
        p=dict(reason='OFFLINE',elapsed_ns=1,calls=0,reserved_input_tokens=0,reserved_output_tokens=0,actual_input_tokens=None,actual_output_tokens=None,compute_termination_confirmed=False,deliverable=True,text='Hello.',facts=facts,facts_sha256=hashlib.sha256((json.dumps(facts,sort_keys=True,separators=(',',':'))+'\n').encode()).hexdigest(),fault=None)
        return dict(provider=p),w
    def test_provider_reason_attempt_and_deadline_limits_are_independent_of_pass_flags(self):
        observation,w=self.offline();self.assertTrue(check_provider(observation,w))
        for changes in (dict(reason='MODEL'),dict(calls=3),dict(elapsed_ns=10000000001),dict(reserved_input_tokens=2049)):
            bad=copy.deepcopy(observation);bad['provider'].update(changes)
            with self.assertRaises(EvidenceError):check_provider(bad,w)
    def test_unknown_provider_usage_and_compute_stop_cannot_be_invented(self):
        observation,w=self.offline()
        for changes in (dict(actual_input_tokens=1),dict(actual_output_tokens=1),dict(compute_termination_confirmed=True)):
            bad=copy.deepcopy(observation);bad['provider'].update(changes)
            with self.assertRaises(EvidenceError):check_provider(bad,w)
    def test_malformed_and_delayed_faults_cannot_be_replaced_with_offline_summaries(self):
        observation,w=self.offline()
        for fault in ('MALFORMED','DELAY_AFTER_CANCEL'):
            with self.assertRaises(EvidenceError):check_provider(observation,dict(w,provider_fault=fault))
    def test_c06_predecessor_cannot_silently_lose_its_two_actual_client_cases(self):
        from predecessor import configuration,validate_records
        from common import CONTRACT
        pins,files,units,python=configuration('C06');metric=lambda c:dict(planned=c,executed=c,passed=c,failed=0,inconclusive=0,refused_expected=0)
        manifest=dict(stage='C06',gate_status='OWNED_GATES_PASS',contract_sha256=CONTRACT,**pins,unit=metric(units),python=metric(python),client_e2e=metric(2),client_transport_steps=18,component_client_tier='COMPONENT_CLIENT',native_job_calls=0,native_world_operations=0)
        oracle=dict(manifest,counts=dict(unit=metric(units),python=metric(python)),native_executed=0)
        validate_records(manifest,oracle,'C06')
        for changes in (dict(client_executed=0),dict(client_tier='SYNTHETIC_CONTRACT')):
            with self.assertRaises(EvidenceError):validate_records(manifest,dict(oracle,**changes),'C06')

class InteractionBenchmarkTests(unittest.TestCase):
    def test_unchanged_c06_then_candidate_use_the_same_frozen_sixty_message_parser_schedule(self):
        from safety_benchmark import run
        evidence=os.environ.get('COGNITIVECRAFT_C_PYTHON_EVIDENCE_ROOT')
        with tempfile.TemporaryDirectory() as temporary:
            result=run(Path(evidence or temporary)/'safety-benchmark');self.assertEqual('PASS',result['status']);self.assertEqual(0,result['native_credit']);self.assertEqual(60,result['measurements']['baseline']['cases']);self.assertEqual(60,result['measurements']['candidate']['cases'])
