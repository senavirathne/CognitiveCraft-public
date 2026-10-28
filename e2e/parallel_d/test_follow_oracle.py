"""Synthetic negative controls for raw follow evidence, never native-case credit."""
import copy,unittest
from follow_oracle import sessions,ACTOR

def synthetic():
    def point(x):return dict(dimension='minecraft:overworld',x=x,y=201,z=0)
    s=dict(session='synthetic',action='WAIT',reason='NONE',tick=10,acceptedTick=10,deadline=1210,traveled=0,pausedTicks=0,observations=0,repaths=0,feet=point(0),target=point(12),proposal=None)
    first=dict(case='synthetic',event='ACCEPTED',tick=10,world='synthetic-world',principal='synthetic-principal',actor=ACTOR,seed=0,native_feet=[0,201,0],native_player=[12,201,0],origin=[-100,201,-100],connected=True,navigation_done=True,state=s,path_nodes=0,path_length=0,step_ns=0,heap_used_bytes=1000000,forced_chunks=0,integration_root=None,save_proof=None)
    rows=[first]
    for tick in range(10,31):
        r=copy.deepcopy(first);r['event']='STEP';r['tick']=tick;r['step_ns']=1000;r['state']['tick']=tick
        if tick==10:r['state']['observations']=1
        elif tick<20:r['state']['observations']=1
        else:r['state']['observations']=2
        if tick==30:r['state'].update(action='STOP',reason='CANCELLED')
        rows.append(r)
    return rows
class FollowOracleTest(unittest.TestCase):
    def verify(self,rows):return sessions(rows,'synthetic','synthetic-world')
    def test_native_clock_reset_and_missing_frames_refused(self):
        good=synthetic();self.verify(good)
        for kind in ('gap','reset'):
            bad=copy.deepcopy(good)
            if kind=='gap':del bad[5]
            else:bad[5]['state']['acceptedTick']=11
            with self.assertRaises(ValueError):self.verify(bad)
    def test_teleport_and_forged_travel_refused(self):
        for kind in ('teleport','travel'):
            bad=synthetic()
            if kind=='teleport':bad[5]['native_feet'][0]=5
            else:bad[5]['state']['traveled']=1
            with self.assertRaises(ValueError):self.verify(bad)
    def test_repath_lookahead_and_observation_inflation_refused(self):
        for kind in ('path','repath','observe'):
            bad=synthetic()
            if kind=='path':bad[5]['path_length']=9
            elif kind=='repath':bad[5]['state']['repaths']=2
            else:bad[5]['state']['observations']=2
            with self.assertRaises(ValueError):self.verify(bad)
    def test_disconnect_movement_and_phony_settlement_refused(self):
        for kind in ('disconnect','settlement','principal','load'):
            bad=synthetic()
            if kind=='disconnect':bad[5]['connected']=False
            elif kind=='settlement':bad[5]['save_proof']='fabricated'
            elif kind=='principal':bad[5]['principal']='swapped'
            else:bad[5]['forced_chunks']=1
            with self.assertRaises(ValueError):self.verify(bad)
    def test_terminal_resume_and_missing_terminal_refused(self):
        bad=synthetic();bad[-1]['state']['action']='WAIT'
        with self.assertRaises(ValueError):self.verify(bad)
        bad=synthetic();x=copy.deepcopy(bad[-1]);x['tick']+=1;x['navigation_done']=False;bad.append(x)
        with self.assertRaises(ValueError):self.verify(bad)
