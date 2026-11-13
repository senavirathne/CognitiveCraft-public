import copy,unittest
from lifecycle_connected import death_departure

class NativeDeathDepartureTests(unittest.TestCase):
    def frames(self):
        life={'identity':{'actor':{'entityId':'original'},'lifecycle':{'event':'DEATH','acknowledged':7}}}
        event={'event':'ENTITY_UNLOAD','entity':'original','removal':'KILLED','tick':120}
        native={'entities':{'original':{'loaded':False}},'nativeEvents':[event]}
        return life,native,event

    def test_acknowledged_death_still_waits_for_the_native_corpse_to_leave(self):
        life,native,event=self.frames();native['entities']['original']={'loaded':True,'alive':False,'removed':False}
        self.assertIsNone(death_departure(life,native,'original',100))
        native['entities']['original']={'loaded':False};native['nativeEvents']=[]
        self.assertIsNone(death_departure(life,native,'original',100))
        native['nativeEvents']=[event];self.assertEqual(death_departure(life,native,'original',100),event)

    def test_only_the_current_originals_confirmed_killed_event_can_complete_the_phase(self):
        life,native,event=self.frames()
        for field,value in [('entity','foreign'),('removal','UNLOADED_TO_CHUNK'),('removal','DISCARDED'),('event','ENTITY_LOAD'),('tick',99)]:
            changed=copy.deepcopy(native);changed['nativeEvents'][0][field]=value
            with self.subTest(field=field,value=value):self.assertIsNone(death_departure(life,changed,'original',100))
        for field,value in [('event','UNLOAD'),('acknowledged',3)]:
            changed=copy.deepcopy(life);changed['identity']['lifecycle'][field]=value
            self.assertIsNone(death_departure(changed,native,'original',100))
        life['identity']['actor']['entityId']='foreign';self.assertIsNone(death_departure(life,native,'original',100))

if __name__=='__main__':unittest.main()
