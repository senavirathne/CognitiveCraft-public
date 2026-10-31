import copy
import os
from pathlib import Path
import tempfile
import unicodedata
import unittest
from common import EvidenceError, digest, read
from selector_variations import CAMPAIGN, CAMPAIGN_SHA256, check_frames, run


class SelectorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        evidence=os.environ.get('COGNITIVECRAFT_C_PYTHON_EVIDENCE_ROOT')
        cls.temporary=None if evidence else tempfile.TemporaryDirectory()
        cls.folder=Path(evidence or cls.temporary.name)/'selector-variations';cls.result=run(cls.folder);cls.observed=read(cls.folder/'frames.json')
    @classmethod
    def tearDownClass(cls):
        if cls.temporary: cls.temporary.cleanup()
    def test_one_hundred_held_out_variations_preserve_anchor_role_and_direction(self):
        self.assertEqual((100,100),(self.result['executed'],self.result['passed']))
        self.assertEqual(CAMPAIGN_SHA256,digest(CAMPAIGN))
    def test_five_frozen_families_have_twenty_distinct_nfc_labels(self):
        rows=read(CAMPAIGN)['cases'];self.assertEqual(100,len({r['label'] for r in rows}))
        self.assertEqual(list(range(20261027,20261127)),[r['seed'] for r in rows])
        for family in {r['family'] for r in rows}: self.assertEqual(20,sum(r['family']==family for r in rows))
        self.assertTrue(all(unicodedata.is_normalized('NFC',r['label']) for r in rows))
    def reject(self,mutate):
        rows=copy.deepcopy(self.observed);mutate(rows)
        with self.assertRaises(EvidenceError): check_frames(rows,read(CAMPAIGN))
    def test_swapped_caller_actor_anchor_is_rejected(self):
        self.reject(lambda r:r['cases'][60]['intent']['body']['source']['selector'].__setitem__('anchor','ACTOR'))
    def test_reversed_source_destination_is_rejected(self):
        def swap(r):
            b=r['cases'][0]['intent']['body'];b['source'],b['destination']=b['destination'],b['source']
        self.reject(swap)
    def test_lost_container_type_or_changed_owner_role_is_rejected(self):
        self.reject(lambda r:r['cases'][0]['intent']['body']['source']['selector'].__setitem__('container_type','BARREL'))
        self.reject(lambda r:r['cases'][20]['intent']['body']['source']['selector'].__setitem__('role','OUTPUT'))
    def test_duplicate_partial_or_summary_only_frames_cannot_qualify(self):
        self.reject(lambda r:r['cases'].pop())
        self.reject(lambda r:r['cases'].__setitem__(1,r['cases'][0]))
    def test_simulated_native_credit_is_rejected(self):
        self.reject(lambda r:r.__setitem__('native_executed',100))
    def test_qualified_c04_pin_is_required_for_advancement(self):
        from predecessor import validate_records
        forged={'stage':'C04','gate_status':'OWNED_GATES_PASS','source_sha':'1230d28de43a9b8cf48fe7ae61774ed11e3bcfb9'}
        with self.assertRaises(EvidenceError): validate_records(forged,forged,'C04')
