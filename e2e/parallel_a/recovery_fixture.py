"""Independent raw native H/P/D and legacy-wheat proof. No production authority."""
import json,math
from native_nbt import chunk
from review_receipt_prefix import regions,dimension_root,entity_uuid,item,block_name,journal,sha,canonical

PARTICIPANT='cognitivecraft_deposit_evidence'
SOURCE='cognitivecraft_harvest_sources'
MEMBERSHIP='cognitivecraft_entity_membership'

def marker(entry):
    return {'schema':1,'effect':sha(canonical(entry['intent']['key'])),'intent':sha(canonical(entry['intent'])),'phase':'RESULT','result':sha(canonical(entry['result']))}

def source_row(world,source):
    x,y,z=(source[k] for k in ('x','y','z'))
    root=chunk(dimension_root(world)/'region'/f'r.{x//512}.{z//512}.mca',x//16,z//16)
    assert root is not None and SOURCE in root
    metadata=json.loads(root[SOURCE]);assert metadata['schema']==1 and metadata['dimension']==source['dimension']
    assert (metadata['chunkX'],metadata['chunkZ'])==(x//16,z//16)
    rows=[r for r in metadata['sources'] if (r['x'],r['y'],r['z'])==(x,y,z)];assert len(rows)==1
    return rows[0]

def participant(native,expected,expected_marker,inventory):
    metadata=json.loads(native[PARTICIPANT]);assert metadata['schema']==1
    assert metadata['incarnation']==expected['incarnation'] and metadata['revision']==expected['revision']
    assert json.loads(metadata['marker'])==expected_marker
    actual=[item(v) for v in native.get(inventory,[])]
    wanted=[(n,s['item'],s['count'],{}) for n,s in enumerate(expected['slots']) if s['count']]
    assert len({r[0] for r in actual})==len(actual) and sorted(actual)==wanted,(actual,wanted)
    return {'metadata':metadata,'inventory':actual}

def native_saved(world,stage,case=None):
    world=__import__('pathlib').Path(world)
    hh,harvests=journal(world/'data/cognitivecraft/harvest-evidence/v1/state.jsonl');assert len(harvests)==1
    h=harvests[0];hi,hr=h['intent'],h['result'];assert hr['live']=='COMPLETE' and hi['key']['ordinal']==1
    p=d=None
    if stage in ('PICKUP','DEPOSIT'):
        ph,pickups=journal(world/'data/cognitivecraft/pickup-evidence/v1/state.jsonl');assert len(pickups)==1 and ph['world']==hh['world']
        p=pickups[0];assert p['schema']==1 and p['intent']['harvest']==h and p['result']['live']=='COMPLETE'
        assert 'remaining' not in p['result'] and p['intent']['key']['ordinal']==2
        assert p['result']['source']==hr['source'] and p['result']['actor']['incarnation']==hr['actor']['incarnation']
        assert p['result']['actor']['revision']>hr['actor']['revision']
        r=p['result']['receipt'];old=hr['receipt']
        assert r['stage']=='PICKUP' and r['quantity']==old['quantity']==1
        assert all(r[k]==old[k] for k in ('world','principal','domain','run','batch','citizen','entity','dimension','source','destination','item','sourceBlock','drops'))
        assert r['id']!=old['id'] and r['operation']==p['intent']['operation']!=old['operation']
    if stage=='DEPOSIT':
        dh,deposits=journal(world/'data/cognitivecraft/deposit-evidence/v1/state.jsonl');assert len(deposits)==1 and dh['world']==hh['world']
        d=deposits[0];assert d['schema']==3 and d['result']['live']=='COMPLETE' and d['intent']['key']['ordinal']==3
        assert d['intent']['pickupPrefix']['pickup']==p and d['intent']['receiptPrefix']==[hr['receipt'],p['result']['receipt']]
        assert d['intent']['actor']==p['result']['actor']
        r=d['result']['receipt'];old=p['result']['receipt']
        assert r['quantity']==1 and r['operation']==d['intent']['operation'] and r['id'] not in (old['id'],hr['receipt']['id'])
        assert all(r[k]==old[k] for k in ('world','principal','domain','run','batch','citizen','entity','dimension','source','destination','item','sourceBlock','drops'))
        assert d['result']['actor']['revision']>d['intent']['actor']['revision'] and d['result']['chest']['revision']>d['intent']['chest']['revision']
    current={'HARVEST':h,'PICKUP':p,'DEPOSIT':d}[stage];key=current['intent']['key'];expected=marker(current)
    assert key['world']==hh['world'] and key['root']==key['step']
    for e in (h,p,d):
        if e is None:continue
        assert all(e['intent']['key'][k]==key[k] for k in ('world','root','step','attempt','generation'))
        assert e['result']['usage']['COMMITTED_EFFECTS']==e['intent']['key']['ordinal']
        assert e['result']['usage']['ATTEMPTED_EFFECTS']==e['intent']['key']['ordinal']
    if case:
        assert hi['item']==case['item'] and hi['area']==[case['source'][k] for k in ('minX','minY','minZ','maxX','maxY','maxZ')]
        assert hi['destination']==[case['destination'][k] for k in ('x','y','z')]
    frames=list(regions(world,'entities'));entities=[(e,f) for f in frames for e in f.get('Entities',[])]
    actors=[(e,f) for e,f in entities if entity_uuid(e)==hi['actor']['entity']];assert len(actors)==1 and actors[0][0]['id']=='minecraft:villager'
    actor,actor_frame=actors[0];actor_expected=hr['actor'] if stage=='HARVEST' else current['result']['actor']
    facts={'actor':participant(actor,actor_expected,expected,'Inventory')}
    s=hr['source'];sr=source_row(world,s)
    assert sr['incarnation']==s['incarnation'] and sr['revision']==s['revision'] and json.loads(sr['marker'])==expected
    assert s['state']=='minecraft:air' and block_name(world,[s[k] for k in ('x','y','z')])=='minecraft:air';facts['source']=sr
    if stage=='HARVEST':
        assert actor.get('Inventory',[])==[] and hr['actor']==hi['actorBefore']
        if hi['item']=='minecraft:wheat':
            assert h['schema']==2 and 'receipt' not in hr and hr['cropReceipt']['stage']=='HARVEST'
            crop=hr['cropReceipt'];assert crop['run']==hi['run'] and crop['actor']==hi['actor'] and crop['source']==hi['area'] and crop['destination']==hi['destination']
            assert crop['wheat']==sum(r['quantity'] for r in hr['drops'] if r['item']=='minecraft:wheat')==1
            assert all(r['item'] in ('minecraft:wheat','minecraft:wheat_seeds') for r in hr['drops'])
        else:
            assert h['schema']==1 and hr['receipt']['stage']=='HARVEST' and hr['receipt']['operation']==hi['operation']
            assert hr['receipt']['drops']==[r['entity'] for r in hr['drops']]
        outputs=[]
        for ref in hr['drops']:
            found=[e for e,f in entities if entity_uuid(e)==ref['entity']];assert len(found)==1 and found[0]['id']=='minecraft:item'
            actual=found[0];assert item(actual['Item'])[1:]==(ref['item'],ref['quantity'],{})
            state=json.loads(actual[PARTICIPANT]);assert state['schema']==1 and state['incarnation']==ref['incarnation'] and state['revision']==ref['revision'] and json.loads(state['marker'])==expected
            outputs.append({'entity':ref['entity'],'stack':item(actual['Item']),'metadata':state})
        facts['spawnedOutputs']=outputs
    else:
        assert all(not any(entity_uuid(e)==ref['entity'] for e,f in entities) for ref in hr['drops'])
        actor_trail=p['intent']['actorTrail'] if stage=='PICKUP' else d['intent']['pickupPrefix']['actorTrail'];drop_trail=p['intent']['dropTrail']
        saved_trace=json.loads(actor['cognitivecraft_entity_trail'])
        assert saved_trace['schema']==1 and saved_trace['complete'] and saved_trace['membership'] and json.loads(saved_trace['marker'])==expected
        assert saved_trace['entity']==actor_trail['entity'] and saved_trace['incarnation']==actor_trail['incarnation'] and saved_trace['revision']==actor_expected['revision']
        original_chunks={(c['x'],c['z']) for c in actor_trail['chunks']};actual_chunks=[tuple(c) for c in saved_trace['chunks']]
        assert 1<=len(actual_chunks)<=8 and actual_chunks==sorted(set(actual_chunks)) and original_chunks<=set(actual_chunks)
        assert saved_trace['dimension']==actor_trail['current']['dimension'] and tuple(saved_trace['current']) in actual_chunks
        actor_trail={'entity':actor_trail['entity'],'incarnation':actor_trail['incarnation'],
                     'chunks':[{'dimension':saved_trace['dimension'],'x':x,'z':z} for x,z in actual_chunks],
                     'current':{'dimension':saved_trace['dimension'],'x':saved_trace['current'][0],'z':saved_trace['current'][1]}}

        refs=[('ACTOR',actor_trail,actor_expected['revision'],True),('DROP',drop_trail,hr['drops'][0]['revision'],False)]
        native_members=[]
        for role,trail,revision,present in refs:
            for c in trail['chunks']:
                matching=[f for f in frames if f.get('Position')==[c['x'],c['z']]];assert len(matching)==1
                f=matching[0];metadata=json.loads(f[MEMBERSHIP]);assert metadata['schema']==1 and metadata['dimension']==c['dimension'] and (metadata['x'],metadata['z'])==(c['x'],c['z'])
                rows=[r for r in metadata['rows'] if r['entity']==trail['entity']];assert len(rows)==1;r=rows[0]
                assert r['role']==role and r['incarnation']==trail['incarnation'] and r['revision']==revision and json.loads(r['marker'])==expected
                assert r['chunks']==[[v['x'],v['z']] for v in trail['chunks']] and r['current']==[trail['current']['x'],trail['current']['z']]
                ids=[entity_uuid(e) for e in f.get('Entities',[])];assert len(ids)==len(set(ids))
                wanted=present and c==trail['current'];assert r['present']==wanted and (trail['entity'] in ids)==wanted
                if role=='ACTOR' and wanted:assert [math.floor(actor['Pos'][n])//16 for n in (0,2)]==[c['x'],c['z']]
                native_members.append({'role':role,'entity':trail['entity'],'chunk':c,'row':r,'nativePresent':wanted,'nativeUuidSetSha256':sha(canonical(sorted(ids)))})
        facts['memberships']=native_members
    if stage=='DEPOSIT':
        pos=list(map(int,d['intent']['chest']['address'].split(',')))
        chests=[e for f in regions(world,'region') for e in f.get('block_entities',[]) if [e['x'],e['y'],e['z']]==pos]
        assert len(chests)==1 and chests[0]['id']=='minecraft:chest'
        facts['chest']=participant(chests[0],d['result']['chest'],expected,'Items')
        assert actor.get('Inventory',[])==[] and sum(s['count'] for s in d['result']['chest']['slots'])==1
    return {'status':'PASS_INDEPENDENT_NATIVE_'+stage,'key':key,'entry':current,'harvest':h,'pickup':p,'deposit':d,'marker':expected,'native':facts}
