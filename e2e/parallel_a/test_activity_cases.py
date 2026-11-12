import unittest
from pathlib import Path
from activity_cases import CASES,complete,selected_originals
from recovery_cases import originals

class A09EvidenceDenominator(unittest.TestCase):
    def test_native_regressions_keep_frozen_quantities_and_both_supported_materials(self):
        cases=originals(Path(__file__).with_name('a06_original_cases.json'));selected=selected_originals(cases)
        self.assertEqual({s:c['requested'] for s,c in selected.items()},{'HARVEST':2,'PICKUP':4,'DEPOSIT':4})
        self.assertEqual({c['item'] for c in selected.values()},{'minecraft:oak_log','minecraft:birch_log'})
        self.assertTrue(all(c in cases for c in selected.values()))
    def test_missing_or_replaced_positive_case_cannot_qualify_the_denominator(self):
        rows=[{'id':id,'status':'PASS_NATIVE_CANDIDATE_PENDING_REVIEW'} for id in CASES]
        for changed in (rows[1:],rows+[rows[0]],rows[::-1]):
            with self.assertRaises(AssertionError):complete(changed)
    def test_inconclusive_window_never_counts_as_positive_runtime_evidence(self):
        rows=[{'id':id,'status':'PASS_NATIVE_CANDIDATE_PENDING_REVIEW'} for id in CASES]
        for status in ('FAIL','INCONCLUSIVE_REQUIRED_NATIVE_WINDOW','NOT_EXECUTED_REQUIRED'):
            rows[0]['status']=status
            with self.assertRaises(AssertionError):complete(rows)
