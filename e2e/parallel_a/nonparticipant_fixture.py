"""Ordinary setup containment for the idle foreign villager, outside resource work."""
import hashlib,json,uuid
from pathlib import Path

def layout():
    return {(x,y,z) for x in range(81,85) for z in range(64,68)
            if x in (81,84) or z in (64,67) for y in range(208,211)}

def protects(case):
    source=case['source'];destination=tuple(case['destination'][k] for k in ('x','y','z'))
    for p in layout():
        if all(source['min'+axis]<=p[n]<=source['max'+axis] for n,axis in enumerate(('X','Y','Z'))) or p==destination:
            raise ValueError('Idle-villager enclosure overlaps the original resource task')
    return True

def setup(h):
    assert not h.actions_active and h.a10_profile in ('A06','A09')
    entity=h.entities['PlayerB'];assert entity!=h.entities['PlayerA']
    before=h.wait(lambda:next((a for a in h.snapshot()['actors'] if a['uuid']==entity),None),'Idle foreign villager present before setup',25)
    assert before['alive'] and not before['baby'] and not before['noAI'] and not before['controlled']
    commands=['fill 81 208 64 84 210 64 minecraft:glass','fill 81 208 67 84 210 67 minecraft:glass',
              'fill 81 208 65 81 210 66 minecraft:glass','fill 84 208 65 84 210 66 minecraft:glass',
              'tp '+entity+' 82.5 208 65.5']
    h.console(commands)
    def applied():
        raw=(h.root/'server/snapshot.json').read_bytes();value=json.loads(raw)
        if value['time']<h.activated:return None
        actor=next((a for a in value['actors'] if a['uuid']==entity),None)
        if not actor or not (82<=actor['pos'][0]<84 and 65<=actor['pos'][2]<67):return None
        if not all('minecraft:glass' in value['blocks'].get(','.join(map(str,p)),'') for p in layout()):return None
        owner=value.get('knownPlayers',{}).get('PlayerB',{})
        if owner.get('uuid')!=h.players['PlayerB'] or not any(c['actor']['citizenId']==h.citizens['PlayerB']
                and c['actor']['entityId']==entity and c['availability']=='LOADED'
                for c in owner.get('citizens',{}).get('citizens',[])):return None
        return raw,value,actor
    raw,snapshot,actor=h.wait(applied,'Actual ordinary idle-villager enclosure before work',25)
    assert actor['alive'] and not actor['baby'] and not actor['noAI'] and not actor['controlled']
    path=h.root/'a10-fixture-snapshots'/(hashlib.sha256(raw).hexdigest()+'.json');path.parent.mkdir(exist_ok=True);path.write_bytes(raw)
    from baseline import native_process
    record={'schema':1,'kind':'ORDINARY_IDLE_FOREIGN_VILLAGER_ENCLOSURE','profile':h.a10_profile,
            'entity':entity,'citizen':h.citizens['PlayerB'],'principal':h.players['PlayerB'],
            'primary':h.entities['PlayerA'],'initialPose':[82.5,208,65.5],'originalDefaultPose':[80.5,208,68.5],
            'defaultAndFixtureEntityChunk':[5,4],'blocks':len(layout()),'commands':commands,
            'setupBlockPlacements':36,'preRequestActorRelocations':1,
            'claim':'Pre-request fixture setup only; no Job, journal, receipt or tested gameplay accounting mutation.',
            'normalAI':True,'nativeEffects':0,'beforeGameplay':True,'nativeSnapshot':path.relative_to(h.root).as_posix(),
            'nativeSnapshotSha256':hashlib.sha256(raw).hexdigest(),'nativeProcess':native_process(h)}
    target=h.root/('a10-nonparticipant-'+str(uuid.uuid4())+'.json');target.write_text(json.dumps(record,indent=2)+'\n')
    h.current_nonparticipant_fixture=record;h.event('a10-idle-foreign-actor-fixture',record)
    return record

def review_record(root,record,profile,jar):
    assert record['schema']==1 and record['kind']=='ORDINARY_IDLE_FOREIGN_VILLAGER_ENCLOSURE' and record['profile']==profile
    assert record['blocks']==len(layout())==36 and record['initialPose']==[82.5,208,65.5]
    assert record['originalDefaultPose']==[80.5,208,68.5] and record['defaultAndFixtureEntityChunk']==[5,4]
    assert record['normalAI'] and record['nativeEffects']==0 and record['beforeGameplay']
    assert record['entity']!=record['primary'] and record['commands']==[
        'fill 81 208 64 84 210 64 minecraft:glass','fill 81 208 67 84 210 67 minecraft:glass',
        'fill 81 208 65 81 210 66 minecraft:glass','fill 84 208 65 84 210 66 minecraft:glass',
        'tp '+record['entity']+' 82.5 208 65.5']
    path=Path(record['nativeSnapshot']);assert not path.is_absolute() and '..' not in path.parts
    raw=(root/path).read_bytes();assert hashlib.sha256(raw).hexdigest()==record['nativeSnapshotSha256'];snapshot=json.loads(raw)
    actor=next(a for a in snapshot['actors'] if a['uuid']==record['entity'])
    assert actor['alive'] and not actor['baby'] and not actor['noAI'] and not actor['controlled']
    assert 82<=actor['pos'][0]<84 and 65<=actor['pos'][2]<67
    assert all('minecraft:glass' in snapshot['blocks'][','.join(map(str,p))] for p in layout())
    owner=snapshot['knownPlayers']['PlayerB'];assert owner['uuid']==record['principal']
    assert any(c['actor']['citizenId']==record['citizen'] and c['actor']['entityId']==record['entity'] and c['availability']=='LOADED' for c in owner['citizens']['citizens'])
    process=record['nativeProcess']['namespaceProcess'];assert process['kind']=='server' and process['releaseSha256']==jar
    assert snapshot['pid']==process['pid'] and snapshot['time']>=process['start']*1000
    return {'status':'PASS_APPLIED_NORMAL_AI_IDLE_ACTOR_FIXTURE','entity':record['entity'],'snapshotSha256':record['nativeSnapshotSha256'],'blocks':36}

def review_world(world,record,case):
    protects(case)
    from handoff_fixture import native_block
    from review_receipt_prefix import regions,entity_uuid
    cache={}
    for p in layout():assert native_block(world,p,cache)[0]=='minecraft:glass'
    actor=next(e for r in regions(world,'entities') for e in r.get('Entities',[]) if entity_uuid(e)==record['entity'])
    assert actor['id']=='minecraft:villager' and actor['Health']>0 and actor.get('Age',0)>=0 and not actor.get('NoAI',0) and not actor.get('NoGravity',0)
    assert 82<=actor['Pos'][0]<84 and 65<=actor['Pos'][2]<67
    return {'status':'PASS_SAVED_NORMAL_AI_IDLE_ACTOR_FIXTURE','entity':record['entity'],'blocks':36,'originalTaskGeometryUnchanged':True}
