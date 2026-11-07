import copy,json,unittest
from pathlib import Path
from lifecycle_cases import contexts,disposition,LIVE_CASES

class LifecycleCasesTest(unittest.TestCase):
    def test_exact_nine_contexts_and_original_physical_layout_fields_are_retained(self):
        rows=contexts(Path(__file__).with_name('a07_w04_contexts.json'))
        for row in rows:
            c=row['case'];self.assertIn(c['quantity'],(2,3,4));self.assertTrue(c['positions']);self.assertEqual(len(c['destinationSlots']),c['occupiedSlots'])
            self.assertRegex(c['caseSha256'],r'^[a-f0-9]{64}$')
    def test_every_role_and_phase_is_required(self):
        self.assertEqual(len(LIVE_CASES),12);self.assertEqual(len(set(LIVE_CASES)),12)
        for role in ('ACTOR','SOURCE','FACILITY','DESTINATION'):
            self.assertEqual(sum(c.startswith(role+'-') for c in LIVE_CASES),3)
    def test_missing_native_item_never_proves_burning(self):
        self.assertEqual(disposition({'loaded':False},[]),'UNAVAILABLE_CUSTODY_UNKNOWN')
        self.assertEqual(disposition({'loaded':False,'removed':True,'removal':'UNLOADED_TO_CHUNK'},[]),'UNAVAILABLE_CUSTODY_UNKNOWN')
    def test_native_survival_requires_a_loaded_live_entity(self):
        self.assertEqual(disposition({'loaded':True,'alive':True,'removed':False},[]),'OBSERVED_SURVIVING_DROP')
        self.assertNotEqual(disposition({'loaded':True,'alive':False},[]),'OBSERVED_SURVIVING_DROP')
    def test_accepted_damage_without_actual_removal_does_not_prove_destruction(self):
        self.assertEqual(disposition({'loaded':False},[{'source':'lava','accepted':True,'removed':False}]),'UNAVAILABLE_CUSTODY_UNKNOWN')
    def test_actual_native_fire_removal_is_distinct_from_arbitrary_absence(self):
        self.assertEqual(disposition({'loaded':False,'removal':'KILLED'},[{'source':'lava','accepted':True,'removed':True}]),'OBSERVED_NATIVE_DESTRUCTION')

if __name__=='__main__':unittest.main()
