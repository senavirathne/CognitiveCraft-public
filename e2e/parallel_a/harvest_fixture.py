"""Independent raw native save review for the first H envelope; never a production authority."""
import json
from native_nbt import chunk
from review_receipt_prefix import regions,dimension_root,entity_uuid,item,block_name,journal,sha,canonical

SOURCE_TAG='cognitivecraft_harvest_sources'
PARTICIPANT_TAG='cognitivecraft_deposit_evidence'

def saved_harvest(world):
    header,rows=journal(world/'data/cognitivecraft/harvest-evidence/v1/state.jsonl')
    assert len(rows)==1 and rows[0]['schema']==1
    entry=rows[0];intent,result=entry['intent'],entry['result'];source=result['source']
    assert result['live']=='COMPLETE' and result['receipt']['stage']=='HARVEST'
    assert result['receipt']['operation']==intent['operation'] and result['receipt']['run']==intent['run']
    assert result['actor']==intent['actorBefore']
    marker={'schema':1,'effect':sha(canonical(intent['key'])),'intent':sha(canonical(intent)),'phase':'RESULT','result':sha(canonical(result))}
    entities=[e for c in regions(world,'entities') for e in c.get('Entities',[])]
    actor=next(e for e in entities if entity_uuid(e)==intent['actor']['entity'])
    assert actor['id']=='minecraft:villager' and actor.get('Inventory',[])==[]
    actor_metadata=json.loads(actor[PARTICIPANT_TAG]);assert actor_metadata['schema']==1
    assert actor_metadata['incarnation']==result['actor']['incarnation'] and actor_metadata['revision']==result['actor']['revision']
    assert json.loads(actor_metadata['marker'])==marker
    x,y,z=source['x'],source['y'],source['z'];path=dimension_root(world)/'region'/f'r.{x//512}.{z//512}.mca'
    native_chunk=chunk(path,x//16,z//16);metadata=json.loads(native_chunk[SOURCE_TAG]);assert metadata['schema']==1
    assert metadata['dimension']==source['dimension'] and (metadata['chunkX'],metadata['chunkZ'])==(x//16,z//16)
    row=next(r for r in metadata['sources'] if (r['x'],r['y'],r['z'])==(x,y,z))
    assert row['incarnation']==source['incarnation'] and row['revision']==source['revision']
    assert json.loads(row['marker'])==marker and source['state']=='minecraft:air' and block_name(world,[x,y,z])=='minecraft:air'
    drops=[]
    for ref in result['drops']:
        actual=next(e for e in entities if entity_uuid(e)==ref['entity']);assert actual['id']=='minecraft:item'
        stack=actual['Item'];assert item(stack)[1:]==(ref['item'],ref['quantity'],{})
        state=json.loads(actual[PARTICIPANT_TAG]);assert state['schema']==1
        assert state['incarnation']==ref['incarnation'] and state['revision']==ref['revision'] and json.loads(state['marker'])==marker
        drops.append({'entity':ref['entity'],'item':stack,'metadata':state})
    assert [d['entity'] for d in drops]==result['receipt']['drops']
    assert sum(d['item'].get('count',d['item'].get('Count',1)) for d in drops)==result['receipt']['quantity']
    return {'header':header,'entry':entry,'marker':marker,'sourceMetadata':metadata,'actorMetadata':actor_metadata,'nativeDrops':drops,
            'nativeSourceBlock':'minecraft:air','actorInventory':actor.get('Inventory',[]),'status':'PASS_THREE_INDEPENDENT_NATIVE_PARTICIPANTS'}
