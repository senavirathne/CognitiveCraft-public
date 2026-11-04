"""Tamper guards for the independent recovery inspector; these fixtures count as no native successes."""
import copy,json,tempfile,unittest
from pathlib import Path
from recovery_fixture import participant,PARTICIPANT
from review_recovery_current import controls,closed_native_claims

class RecoveryReviewTest(unittest.TestCase):
    def test_native_claims_use_the_flat_journal_codec_and_reject_active_originals(self):
        key={'step':'original','world':'world','generation':1}
        row={'schema':1,'job':'original','world':'world','jobGeneration':1,'state':'RELEASED'}
        closed_native_claims([row],key)
        with self.assertRaises(AssertionError):closed_native_claims([dict(row,state='ACTIVE')],key)
        with self.assertRaises(AssertionError):closed_native_claims([dict(row,jobGeneration=2)],key)
    def test_foreign_claims_do_not_hide_original_claims_and_unknown_states_refuse(self):
        key={'step':'original','world':'world','generation':1}
        row={'schema':1,'job':'foreign','world':'world','jobGeneration':1,'state':'ACTIVE'}
        closed_native_claims([row],key)
        with self.assertRaises(AssertionError):closed_native_claims([dict(row,job='original')],key)
        with self.assertRaises(AssertionError):closed_native_claims([dict(row,state='UNKNOWN')],key)
    def participant(self):
        marker={'schema':1,'effect':'a'*64,'intent':'b'*64,'phase':'RESULT','result':'c'*64}
        expected={'incarnation':'original','revision':3,'slots':[{'item':'minecraft:oak_log','count':1},{'item':'minecraft:air','count':0}]}
        actual={PARTICIPANT:json.dumps({'schema':1,'incarnation':'original','revision':3,'marker':json.dumps(marker)}),
                'Inventory':[{'Slot':0,'id':'minecraft:oak_log','count':1}]}
        return actual,expected,marker
    def test_native_facts_compare_identically_after_artifact_json_round_trip(self):
        actual,expected,marker=self.participant();facts=participant(actual,expected,marker,'Inventory')
        self.assertEqual(facts,json.loads(json.dumps(facts)))
    def test_replaced_incarnation_wrong_marker_and_duplicate_inventory_slots_refuse(self):
        actual,expected,marker=self.participant()
        changed=copy.deepcopy(expected);changed['incarnation']='replacement'
        with self.assertRaises(AssertionError):participant(actual,changed,marker,'Inventory')
        changed=copy.deepcopy(marker);changed['result']='d'*64
        with self.assertRaises(AssertionError):participant(actual,expected,changed,'Inventory')
        actual['Inventory'].append(actual['Inventory'][0].copy())
        with self.assertRaises(AssertionError):participant(actual,expected,marker,'Inventory')
    def test_a_green_label_cannot_hide_missing_native_controls(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'a06-controls-results.json').write_text(json.dumps({'status':'PASS_ALL_REQUIRED_NATIVE_CONTROLS','required':[],'cases':[]}))
            with self.assertRaises(AssertionError):controls(root)

if __name__=='__main__':unittest.main()
