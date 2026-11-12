import copy,unittest
from lifecycle_cycles import review
class NativeCycles(unittest.TestCase):
    def frames(self,kinds):
        first={'process':'one','tick':10,'entities':{'actor':{'loaded':True}}}
        last={'process':'one','tick':50,'entities':{'actor':{'loaded':True}},
              'nativeEvents':[{'tick':11+n,'event':kind,'entity':'actor'} for n,kind in enumerate(kinds)]}
        return first,last
    def test_two_real_pairs_require_two_generation_advances(self):
        a,b=self.frames(['ENTITY_UNLOAD','ENTITY_LOAD']*2);original=copy.deepcopy(b)
        self.assertEqual(2,review(a,b,'actor',1,3)['reloadCycles']);self.assertEqual(original,b)
    def test_duplicate_callbacks_cannot_mint_another_generation(self):
        a,b=self.frames(['ENTITY_UNLOAD','ENTITY_UNLOAD','ENTITY_LOAD','ENTITY_LOAD'])
        self.assertEqual(2,len(review(a,b,'actor',1,2)['duplicateCallbacksWithoutAdditionalGeneration']))
        with self.assertRaises(AssertionError):review(a,b,'actor',1,3)
    def test_under_counted_generation_cannot_pass_two_native_cycles(self):
        a,b=self.frames(['ENTITY_UNLOAD','ENTITY_LOAD']*2)
        with self.assertRaises(AssertionError):review(a,b,'actor',1,2)
    def test_no_unload_or_stale_process_cannot_prove_a_reload(self):
        a,b=self.frames(['ENTITY_LOAD'])
        with self.assertRaises(AssertionError):review(a,b,'actor',1,2)
        a,b=self.frames(['ENTITY_UNLOAD','ENTITY_LOAD']);b['process']='other'
        with self.assertRaises(AssertionError):review(a,b,'actor',1,2)
    def test_missing_final_load_and_out_of_order_native_events_are_rejected(self):
        a,b=self.frames(['ENTITY_UNLOAD'])
        with self.assertRaises(AssertionError):review(a,b,'actor',1,2)
        a,b=self.frames(['ENTITY_UNLOAD','ENTITY_LOAD']);b['nativeEvents'][1]['tick']=9
        with self.assertRaises(AssertionError):review(a,b,'actor',1,2)
if __name__=='__main__':unittest.main()
