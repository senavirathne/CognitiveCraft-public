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
    def test_loaded_identity_and_native_presence_come_from_the_same_frame(self):
        from lifecycle_cycles import loaded_observation
        life={'identity':{'actor':{'citizenId':'citizen','entityId':'actor'},'displayName':'Hana',
                         'availability':'LOADED','lifecycle':{'event':'LOAD','acknowledged':7,'generation':2}}}
        frame={'tick':50,'process':'one','entities':{'actor':{'loaded':True}},
               'lifecycle':{'PlayerA':{'citizen':life}}}
        before=copy.deepcopy(frame)
        self.assertIs(loaded_observation(frame,'citizen','actor','Hana'),life)
        self.assertEqual(frame,before)
        for citizen,entity,name in [('other','actor','Hana'),('citizen','other','Hana'),('citizen','actor','Other')]:
            self.assertIsNone(loaded_observation(frame,citizen,entity,name))

    def test_old_independent_or_unacknowledged_identity_cannot_supply_the_frame(self):
        from lifecycle_cycles import loaded_observation
        life={'identity':{'actor':{'citizenId':'citizen','entityId':'actor'},'displayName':'Hana',
                         'availability':'LOADED','lifecycle':{'event':'LOAD','acknowledged':7}}}
        frame={'entities':{'actor':{'loaded':True}},'lifecycle':{'PlayerA':{'citizen':life}}}
        self.assertIsNone(loaded_observation({'entities':frame['entities']},'citizen','actor','Hana'))
        for target,key,value in [('identity','availability','UNLOADED'),('marker','event','UNLOAD'),('marker','acknowledged',3)]:
            changed=copy.deepcopy(frame);identity=changed['lifecycle']['PlayerA']['citizen']['identity']
            (identity if target=='identity' else identity['lifecycle'])[key]=value
            self.assertIsNone(loaded_observation(changed,'citizen','actor','Hana'))
        changed=copy.deepcopy(frame);changed['entities']['actor']['loaded']=False
        self.assertIsNone(loaded_observation(changed,'citizen','actor','Hana'))

    def test_reload_boundary_requires_all_loss_acks_and_native_absence_in_same_frame(self):
        from lifecycle_cycles import unloaded_observation
        life={'identity':{'actor':{'citizenId':'citizen','entityId':'actor'},'displayName':'Hana',
                         'availability':'UNLOADED','lifecycle':{'event':'UNLOAD','acknowledged':7}}}
        frame={'entities':{'actor':{'loaded':False}},'lifecycle':{'PlayerA':{'citizen':life}}}
        self.assertIs(unloaded_observation(frame,'citizen','actor','Hana'),life)
        for ack in (0,1,3):
            changed=copy.deepcopy(frame);changed['lifecycle']['PlayerA']['citizen']['identity']['lifecycle']['acknowledged']=ack
            self.assertIsNone(unloaded_observation(changed,'citizen','actor','Hana'))
        changed=copy.deepcopy(frame);changed['entities']['actor']['loaded']=True
        self.assertIsNone(unloaded_observation(changed,'citizen','actor','Hana'))
        changed=copy.deepcopy(frame);changed['entities']={}
        self.assertIsNone(unloaded_observation(changed,'citizen','actor','Hana'))

if __name__=='__main__':unittest.main()
