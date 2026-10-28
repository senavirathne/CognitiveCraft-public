"""Independent native slot/capacity model; observations are not pass flags or save receipts."""
import json,collections
from common import *
EVENTS=['SINGLE','SINGLE_CHANGED','LOCKED','LOOT','DOUBLE','BARREL','TOPOLOGY_INVALID']
EXPECTED={'SINGLE':(27,1568,96),'SINGLE_CHANGED':(27,1536,128),'DOUBLE':(54,3295,97),'BARREL':(27,1601,63)}
def observations(rows):
    require([r['event']for r in rows]==EVENTS,'missing/duplicate adapter observations');revisions={}
    for r in rows:
        require(r['case']=='D05-GT01'and r['world']=='d0200000-0000-4000-8000-000000000001','swapped adapter world/case')
        require(r['integration_root']is None and r['save_proof']is None,'fabricated adapter settlement')
        e=r['event'];check=r['check'];binding=r['binding'];native=r['native'];require(binding['storage_id']==r['storage'],'adapter identity swapped')
        require(len(native)==len(binding['members']),'missing native participant');require([n['position']for n in native]==[[m[k]for k in ('x','y','z')]for m in binding['members']],'participant position mismatch')
        if e in EXPECTED:
            slots,capacity,stock=EXPECTED[e];view=r['snapshot'];require(view is not None and check['refusal']=='NONE','eligible native container refused')
            require(view['storage']==r['storage']and view['world']==r['world']and view['generation']==binding['binding_generation'],'visible native identity mismatch');require(len(view['slots'])==slots and len(view['participants'])==slots//27,'wrong native slot/half count')
            require(len({p['incarnation']for p in view['participants']})==slots//27,'double-counted native participant')
            actual=[]
            for n,m in zip(native,binding['members']):
                require(n['slots']==27 and not n['locked']and n['loot_table']is None,'invalid native container exported as empty');require(len(n['items'])==27,'native slot observation missing')
                for item in n['items']:actual.append((m['incarnation_id'],item['index'],item['item']if item['count']else'minecraft:air',item['count']))
            projected=[(s['participant'],s['index'],s['item']['registryId']if s['item']else'minecraft:air',s['count'])for s in view['slots']];require(actual==projected,'adapter slots disagree with native container')
            counts=collections.Counter();free=0
            for participant,index,item,count in actual:
                counts[item]+=count
                if count==0:free+=64
                elif item=='minecraft:oak_planks':free+=64-count
            require(counts['minecraft:oak_planks']==stock and free==capacity and check['insertionCapacity']==free,'actual capacity/count disagrees with independent native slot model')
            require(check['matchingItems']==0,'deposit-only check leaked withdrawal counts');require(check['binding']['storage']==r['storage'],'check identity changed');revisions[e]=view['inventoryRevision']
        else:
            require(r['snapshot']is None and check['insertionCapacity']==check['matchingItems']==0,'refusal disclosed contents or treated unknown as empty')
            if e=='LOCKED':require(check['refusal']=='LOCKED'and native[0]['locked']and 'lock'in native[0]['saved_nbt'].lower(),'actual locked container negative control absent')
            elif e=='LOOT':require(check['refusal']=='LOOT_TABLE'and native[0]['loot_table']=='minecraft:chests/simple_dungeon'and 'items'not in native[0],'unresolved loot unpacked/read')
            else:require(check['refusal']!='NONE'and any('minecraft:air'in n['state']for n in native),'incomplete double topology won')
    require(revisions['SINGLE']!=revisions['SINGLE_CHANGED'],'native inventory revision did not change with slots')
    return {'events':7,'supported_native_snapshots':4,'refused_native_snapshots':3,'new_native_cases':1,'inventory_effects':0,'save_claims':0}
def verify(root):
    result=observations([json.loads(s,object_pairs_hook=no_duplicates)for s in (root/'evidence/adapter-observations.jsonl').read_text().splitlines()]);control=read(root/'evidence/adapter-controls.json');require(control['loaded_before']is False and control['loaded_after']is False and control['chest_refusal']==control['barrel_refusal']=='UNLOADED','unloaded native probe forced chunk/treated unknown as empty')
    kind=read(root/'evidence/adapter-type-controls.json');require(kind['block']=='minecraft:trapped_chest'and kind['chest_instance']is True and kind['native_class']=='net.minecraft.world.level.block.entity.TrappedChestBlockEntity'and kind['refusal']=='UNSUPPORTED','trapped subclass widened native support')
    p=read(root/'evidence/adapter-performance.json');samples=p['samples'];require(len(samples)==200 and samples==sorted(samples)and all(type(n)is int and n>=0 for n in samples),'adapter native performance denominator')
    for key,index in [('p50_ns',99),('p95_ns',189),('p99_ns',197),('max_ns',199)]:require(p[key]==samples[index],'adapter percentile changed')
    require(p['warmup']==50 and p['slots_per_operation']==54 and p['scheduled_tick_tasks']==0 and p['p99_ns']<=50000000 and p['max_ns']<=250000000 and 0<p['heap_used_bytes']<1100*1024*1024,'native adapter work/performance bound')
    return {**result,'unloaded_controls':control,'performance':{k:v for k,v in p.items()if k!='samples'}}
