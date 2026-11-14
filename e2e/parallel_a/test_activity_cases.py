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

class NativeReceiptOwnerCodec(unittest.TestCase):
    def test_dedicated_deposit_tag_is_contextual_while_missing_or_conflicting_harvest_pickup_tags_fail(self):
        from review_activity_connected import owner_receipt
        raw={'id':'fixture:actual-native-id','quantity':1}
        decoded=owner_receipt(raw,'DEPOSIT');self.assertEqual(decoded['id'],raw['id']);self.assertEqual(decoded['stage'],'DEPOSIT');self.assertNotIn('stage',raw)
        for stage in ('HARVEST','PICKUP'):
            with self.assertRaises(AssertionError):owner_receipt(raw,stage)
        with self.assertRaises(AssertionError):owner_receipt({'stage':'HARVEST'},'DEPOSIT')
