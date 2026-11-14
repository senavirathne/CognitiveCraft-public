"""Read-only actual-H birth review. A live lineage capture never qualifies native P."""
import math
from review_receipt_prefix import sha,canonical,regions,entity_uuid

def trail(value,entity,incarnation,dimension,position):
    assert value['entity']==entity and value['incarnation']==incarnation
    chunks=[(c['dimension'],c['x'],c['z']) for c in value['chunks']]
    assert 1<=len(chunks)<=8 and chunks==sorted(set(chunks)) and all(c[0]==dimension for c in chunks)
    expected={'dimension':dimension,'x':math.floor(position[0]/16),'z':math.floor(position[2]/16)}
    assert value['current']==expected and (expected['dimension'],expected['x'],expected['z']) in chunks
    return chunks

def validate(entry,births,restarted,positions):
    assert restarted==[] and len(births)==1
    intent,result=entry['intent'],entry['result'];key=intent['key'];birth=births[0]
    assert birth['key']==key and birth['token']==sha(canonical(key)) and key['ordinal']==1 and key['root']==key['step']
    assert birth['complete'] is True and birth['pickupLineageAvailable'] is True and len(result['drops'])==1
    actor=result['actor'];dimension=actor['dimension'];actor_id=actor['address']
    chunks=trail(birth['actorTrail'],actor_id,actor['incarnation'],dimension,positions[actor_id])
    assert (birth['actorBirth']['dimension'],birth['actorBirth']['x'],birth['actorBirth']['z']) in chunks
    assert len(birth['drops'])==1;drop=birth['drops'][0];expected=result['drops'][0]
    assert drop['entity']==expected['entity'] and drop['incarnation']==expected['incarnation'] and drop['beforeNativeAdd'] is True
    chunks=trail(drop['trail'],expected['entity'],expected['incarnation'],dimension,positions[expected['entity']])
    source=intent['source'];assert drop['birth']=={'dimension':source['dimension'],'x':source['x']//16,'z':source['z']//16}
    assert (drop['birth']['dimension'],drop['birth']['x'],drop['birth']['z']) in chunks
    return {'status':'PASS_ACTUAL_H_BIRTHS_AND_RESTART_REFUSAL','actor':actor_id,'drop':expected['entity'],
            'livePickupLineage':'CAPTURED_FROM_ACTUAL_H_ONLY','restoredLiveLineages':0,'originalPickupSettlements':0,'nativePickupProducer':'INACTIVE'}

def review(root,result,entry):
    expected={entry['result']['actor']['address'],*[d['entity'] for d in entry['result']['drops']]};positions={}
    for record in regions(root/'saved-harvest-killed-world','entities'):
        for entity in record.get('Entities',[]):
            uid=entity_uuid(entity)
            if uid in expected:
                assert uid not in positions,'Duplicate native original H participant';positions[uid]=entity['Pos']
    assert set(positions)==expected
    return validate(entry,result['reached']['membershipBirths'],result['restored']['membershipBirths'],positions)
