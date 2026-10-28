"""Synthetic tamper controls for the independent oracle; these are not native/client scenario credit."""
import copy,unittest
from mobility_oracle import movement,client_frames,ACTOR
from client_oracle import PLAYERS,offline

def point(p):return dict(zip(('x','y','z'),p),dimension='minecraft:overworld')
def synthetic():
    start=[3.5,201,7.5];target=[14.5,201,7.5];base={'mode':'COME_TO_PLAYER','case':'synthetic','world':'synthetic-world','principal':'synthetic-principal','actor':ACTOR,'captured':point(target),'accepted_tick':10,'tick':10,'native_feet':start,'native_player':[14.5,201,18.5],'origin':[0,201,0],'integration_root':None,'save_proof':None,'scheduled_tasks':0,'forced_before':0,'forced_now':0,'checked_chunks':25,'path_nodes':20,'path_length':20,'heap_used_bytes':1000000,'seed':0,'step_ns':1000,'event':'ACCEPTED','observation':None}
    rows=[copy.deepcopy(base)];positions=[];current=start[:]
    for target_point in ([3.5,201,11.6],[14.5,201,11.6],[14.5,201,7.5]):
        for axis in (0,2):
            while abs(current[axis]-target_point[axis])>.00001:
                step=min(.1,abs(target_point[axis]-current[axis]));current=current[:];current[axis]+=step if target_point[axis]>current[axis]else-step;positions.append(current)
    previous=start;travel=0
    for n,p in enumerate(positions,1):
        import math
        travel+=math.dist(previous,p);previous=p;r=copy.deepcopy(base);r.update(event='TERMINAL'if n==len(positions)else'STEP',tick=10+n,native_feet=p);r['observation']={'status':'ARRIVED'if n==len(positions)else'MOVING','blocker':'NONE','tick':r['tick'],'feet':point(p),'endpoint':point(target),'elapsedTicks':n,'travelBlocks':travel,'lastProgressTick':r['tick'],'lastProgress':point(p),'standingCandidates':1,'repathProposals':1};rows.append(r)
    return rows
def clients():
    rows=[]
    for phase in ('BEFORE','MOVING','TERMINAL'):
        for p in PLAYERS:rows.append({'phase':phase,'player':p,'state':{'play':True,'singleplayer':False,'uuid':offline(p),'pos':[14.5,201,18.5],'entities':[{'uuid':ACTOR,'type':'minecraft:villager','pos':[14.5,201,7.5]}]}})
    return rows
class MobilityOracleTest(unittest.TestCase):
    def test_captured_endpoint_mutation_and_clock_reset_refused(self):
        good=synthetic();movement(good,'synthetic','synthetic-world')
        from mobility_frames import collect,reconcile
        import tempfile,json
        from pathlib import Path
        with tempfile.TemporaryDirectory()as directory:
            root=Path(directory);log=root/'stdout.log';log.write_text(''.join('[native] CC_D06_FRAME '+json.dumps(r)+'\n'for r in good));collect(log,root/'raw');self.assertEqual(reconcile(log,root/'raw'),len(good));changed=copy.deepcopy(good);changed[-1]['native_feet'][0]+=1;(root/'raw/movement.jsonl').write_text(''.join(json.dumps(r)+'\n'for r in changed))
            with self.assertRaises(ValueError):reconcile(log,root/'raw')
        for change in ('endpoint','clock'):
            bad=copy.deepcopy(good)
            if change=='endpoint':bad[20]['captured']['x']=25
            else:bad[20]['accepted_tick']=20
            with self.assertRaises(ValueError):movement(bad,'synthetic','synthetic-world')
    def test_teleport_missing_tick_and_wall_crossing_refused(self):
        for change in ('teleport','gap','wall'):
            bad=synthetic()
            if change=='teleport':bad[10]['native_feet'][0]+=3
            elif change=='gap':del bad[10]
            else:bad[60]['native_feet']=[8.5,201,7.5]
            with self.assertRaises(ValueError):movement(bad,'synthetic','synthetic-world')
    def test_forged_travel_last_progress_and_deadline_refused(self):
        for field,value in [('travelBlocks',0),('lastProgressTick',0),('elapsedTicks',600)]:
            bad=synthetic();bad[30]['observation'][field]=value
            with self.assertRaises(ValueError):movement(bad,'synthetic','synthetic-world')
    def test_forced_chunk_repath_and_phony_settlement_refused(self):
        for change in ('forced','repath','settlement'):
            bad=synthetic()
            if change=='forced':bad[20]['forced_now']=1
            elif change=='repath':bad[2]['observation']['repathProposals']=2
            else:bad[20]['save_proof']='fabricated'
            with self.assertRaises(ValueError):movement(bad,'synthetic','synthetic-world')
    def test_swapped_client_principal_or_unsynchronized_actor_refused(self):
        good=clients();server=synthetic();client_frames(good,server)
        for change in ('principal','actor','disconnected'):
            bad=copy.deepcopy(good)
            if change=='principal':bad[-1]['state']['uuid']=offline('PlayerD_A')
            elif change=='actor':bad[-1]['state']['entities']=[]
            else:bad[-1]['state']['play']=False
            with self.assertRaises(ValueError):client_frames(bad,server)
    def test_player_not_moving_or_actor_chasing_new_position_refused(self):
        for change in ('player','actor'):
            bad=clients()
            if change=='player':bad[-2]['state']['pos']=[14.5,201,7.5]
            else:bad[-2]['state']['entities'][0]['pos']=[14.5,201,18.5]
            with self.assertRaises(ValueError):client_frames(bad,synthetic())
