"""Synthetic packet-shape tamper tests only; actual client credit also requires process/driver/packet/render evidence."""
import copy,json,unittest
from client_oracle import menu_observations,privacy_observations,PLAYERS,BEFORE,AFTER,offline
READY={'world':'synthetic-model-only','ids':{t:'synthetic-'+t for t in ('single','double','barrel','private','deposit')}}
def synthetic():
    frames=[]
    for phase,targets in [('BEFORE',('single','double','barrel')),('AFTER',('barrel','single','double')),('SPLIT',('double',))]:
        for target in targets:
            for player in PLAYERS:
                total=1 if phase=='SPLIT'else BEFORE[target]if phase=='BEFORE'else AFTER[target];size=54 if target=='double'and phase!='SPLIT'else 27;slots=[]
                for i in range(size):
                    n=min(64,total);total-=n;slots.append({'slot':i,'item':'minecraft:oak_planks'if n else'minecraft:air','count':n,'playerInventory':False})
                projected=[{'item':{'registryId':s['item']}if s['count']else None,'count':s['count']}for s in slots];state={'play':True,'singleplayer':False,'uuid':offline(player),'menu':1,'slots':slots,'blocks':{'10,201,8':'minecraft:chest[type=single]','11,201,8':'minecraft:air'}};payload='UNAVAILABLE'if phase=='SPLIT'else json.dumps({'world':READY['world'],'storage':READY['ids'][target],'slots':projected});frames.append({'phase':phase,'target':target,'player':player,'state':state,'observer':{'payload':payload}})
    return frames
def private():
    return [{'player':'PlayerD_A','target':'private','payload':json.dumps({'storage':READY['ids']['private'],'slots':[{'item':{'registryId':'minecraft:diamond'},'count':7}]})},*({'player':p,'target':t,'payload':'UNAVAILABLE'}for p,t in [('PlayerD_B','private'),('PlayerD_B','missing'),('PlayerD_A','deposit')])]
class ClientOracleTest(unittest.TestCase):
    def test_synthetic_model_control_is_separate_from_actual_client_credit(self):self.assertEqual(menu_observations(synthetic(),READY)['menu_observations'],14);self.assertEqual(privacy_observations(private(),READY)['uniform_refusals'],3)
    def test_missing_duplicate_disconnected_or_swapped_client_rejected(self):
        rows=synthetic()
        for bad in (rows[:-1],rows+[rows[0]]):
            with self.assertRaises(ValueError):menu_observations(bad,READY)
        for key,value in [('uuid',offline('PlayerD_B')),('singleplayer',True),('play',False)]:
            bad=copy.deepcopy(rows);bad[0]['state'][key]=value
            with self.assertRaises(ValueError):menu_observations(bad,READY)
    def test_changed_slots_reopened_update_and_private_disclosure_rejected(self):
        for change in ('count','menu'):
            bad=synthetic()
            if change=='count':bad[0]['state']['slots'][0]['count']+=1
            else:next(r for r in bad if (r['phase'],r['target'])==('AFTER','barrel'))['state']['menu']=99
            with self.assertRaises(ValueError):menu_observations(bad,READY)
        bad=private();bad[1]['payload']=bad[0]['payload']
        with self.assertRaises(ValueError):privacy_observations(bad,READY)
