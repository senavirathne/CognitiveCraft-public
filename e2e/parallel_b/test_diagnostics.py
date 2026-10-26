import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from parallel_b.diagnostics import classify,MATRIX

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

if __name__=='__main__':unittest.main()
