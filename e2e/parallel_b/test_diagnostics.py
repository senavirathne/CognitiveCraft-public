import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from parallel_b.diagnostics import classify,pending_owners,settled,MATRIX

class DiagnosticEvidenceTests(unittest.TestCase):
    def row(self):
        return {'callsBefore':{'generationCalls':2},'callsAfter':{'generationCalls':2},
                'request':{'accepted':True},'view':{'marker':{'outcome':'FAILED'}},'delivered':0}

    def test_missing_receipt_and_zero_observed_output_never_authorize_replay(self):
        row=self.row()
        self.assertEqual('FAILED_OR_INTERRUPTED_PRESERVED_NO_REPLAY',classify(row))
        row['delivered']=None
        self.assertEqual('FAILED_OR_INTERRUPTED_PRESERVED_NO_REPLAY',classify(row))
        row['delivered']=4
        self.assertEqual('PARTIAL_PRESERVED_NO_REPLAY',classify(row))
        row['delivered']=0;row['actorHeld']=4
        self.assertEqual('PARTIAL_PRESERVED_NO_REPLAY',classify(row))

    def test_new_model_call_invalidates_even_an_apparent_success(self):
        row=self.row();row['view']['marker']['outcome']='SUCCEEDED';row['delivered']=7;row['callsAfter']['generationCalls']=3
        self.assertEqual('FAIL_MODEL_REGENERATION',classify(row))

    def test_intake_refusal_does_not_inherit_an_unrelated_success(self):
        row=self.row();row['request']['accepted']=False;row['view']['marker']['outcome']='SUCCEEDED'
        self.assertEqual('REFUSED_INTAKE_NO_ACCEPTED_RUN',classify(row))

    def test_frozen_matrix_keeps_all_repetitions_and_distinct_loading_controls(self):
        matrix=json.loads(MATRIX.read_text())
        self.assertFalse(matrix['stopAtFirstPass']);self.assertEqual(22,len(matrix['seeds'])*len(matrix['variants']))
        ids=[x['id'] for x in matrix['variants']]
        self.assertEqual(len(ids),len(set(ids)))
        self.assertIn('unloaded-target',ids);self.assertIn('loaded-target',ids)
        self.assertEqual('minecraft:birch_log',matrix['families']['seven-log']['item'])

    def test_failed_run_responsibility_is_found_even_when_absent_from_queue_map(self):
        owner={'id':'fixture:waiting-root','state':'WAITING','attempts':[]}
        snapshot={'knownPlayers':{'PlayerA':{'jobs':{},'runs':{'fixture:blocked-run':{'phase':'TERMINAL','responsibility':owner}}}}}
        self.assertEqual([('PlayerA',owner)],pending_owners(snapshot))
        owner['state']='CANCELLED'
        self.assertEqual([],pending_owners(snapshot))

    def test_cancellation_acknowledgement_alone_cannot_open_the_fixture_barrier(self):
        owner={'id':'fixture:waiting-root','state':'WAITING','attempts':[]}
        snapshot={'knownPlayers':{'PlayerA':{'jobs':{},'runs':{'fixture:blocked-run':{'phase':'TERMINAL','responsibility':owner}}}}}
        class Port:
            def snapshot(self):return snapshot
            def cancel_job(self,player,jid):return 'cancellation=true'
            def wait(self,predicate,reason,timeout):
                if not predicate():raise TimeoutError('Acknowledged cancellation is still not settled')
        with self.assertRaises(TimeoutError):settled(Port(),fence=True)

if __name__=='__main__':unittest.main()
