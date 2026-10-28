"""Synthetic model/tamper inputs only: these Python cases do not execute or credit native Minecraft."""
import copy,unittest
from adapters_oracle import observations,EVENTS,EXPECTED
from common import *

def synthetic():
    result=[];world='d0200000-0000-4000-8000-000000000001'
    for e in EVENTS:
        size=54 if e in ('DOUBLE','TOPOLOGY_INVALID')else 27;members=[{'incarnation_id':f'00000000-0000-4000-8000-{i+1:012d}','x':i,'y':64,'z':0}for i in range(size//27)]
        binding={'storage_id':'synthetic-storage','binding_generation':1,'members':members};native=[];slots=[]
        plan={}
        if e=='SINGLE':plan={0:('minecraft:oak_planks',32),1:('minecraft:oak_planks',64),2:('minecraft:wooden_pickaxe',1)}
        elif e in ('SINGLE_CHANGED','LOCKED','LOOT'):plan={0:('minecraft:oak_planks',64),1:('minecraft:oak_planks',64),2:('minecraft:wooden_pickaxe',1)}
        elif e in ('DOUBLE','TOPOLOGY_INVALID'):plan={0:('minecraft:oak_planks',32),1:('minecraft:stone',64),27:('minecraft:oak_planks',64),53:('minecraft:oak_planks',1)}
        else:plan={0:('minecraft:oak_planks',63),1:('minecraft:stone',64)}
        for i,m in enumerate(members):
            items=[]
            for j in range(27):
                item,count=plan.get(i*27+j,('minecraft:air',0));items.append({'index':j,'item':item,'count':count});slots.append({'participant':m['incarnation_id'],'index':j,'item':{'registryId':item}if count else None,'count':count})
            n={'position':[m[k]for k in ('x','y','z')],'state':'minecraft:air'if e=='TOPOLOGY_INVALID'and i==1 else'minecraft:chest','slots':27,'locked':e=='LOCKED','loot_table':'minecraft:chests/simple_dungeon'if e=='LOOT'else None,'saved_nbt':'synthetic lock','items':items}
            if e=='LOOT':del n['items']
            native.append(n)
        check={'refusal':'NONE'if e in EXPECTED else'LOCKED'if e=='LOCKED'else'LOOT_TABLE'if e=='LOOT'else'UNKNOWN','insertionCapacity':EXPECTED[e][1]if e in EXPECTED else 0,'matchingItems':0,'binding':{'storage':'synthetic-storage'}}
        view={'storage':'synthetic-storage','world':world,'generation':1,'slots':slots,'participants':[{'incarnation':m['incarnation_id']}for m in members],'inventoryRevision':e}
        result.append({'case':'D05-GT01','event':e,'world':world,'storage':'synthetic-storage','snapshot':view if e in EXPECTED else None,'check':check,'binding':binding,'native':native,'integration_root':None,'save_proof':None})
    return result
class AdapterOracleTest(unittest.TestCase):
    def test_synthetic_model_control_never_counts_as_native_execution(self):self.assertEqual(observations(synthetic())['events'],7)
    def test_missing_duplicate_or_swapped_native_principal_world_is_rejected(self):
        rows=synthetic()
        for bad in (rows[:-1],rows+[rows[0]]):
            with self.assertRaises(ValueError):observations(bad)
        bad=copy.deepcopy(rows);bad[0]['world']='foreign'
        with self.assertRaises(ValueError):observations(bad)
    def test_wrong_capacity_duplicate_half_and_unknown_content_leak_are_rejected(self):
        for change in ('capacity','half','unknown','proof'):
            bad=synthetic()
            if change=='capacity':bad[0]['check']['insertionCapacity']+=1
            elif change=='half':bad[4]['snapshot']['participants'][1]=bad[4]['snapshot']['participants'][0]
            elif change=='unknown':bad[2]['check']['insertionCapacity']=1728
            else:bad[0]['save_proof']='synthetic-forged-receipt'
            with self.assertRaises(ValueError):observations(bad)
