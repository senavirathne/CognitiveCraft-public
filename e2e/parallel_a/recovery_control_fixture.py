"""Ordinary pre-request geometry for two fresh, bounded native recovery controls."""
import copy,hashlib,json
from pathlib import Path
from recovery_cases import originals,positions

CASES=('two-unit-prepared-no-effect','two-unit-delivered-remaining-one')

def expected_case(name):
    if name not in CASES:raise ValueError('Not a fresh two-unit recovery control')
    case=copy.deepcopy(next(c for c in originals(Path(__file__).with_name('a06_original_cases.json'))
                            if c['checkpoint']=='DEPOSIT' and c['item']!='minecraft:wheat'))
    case.update(id=name,requested=2);case['destination']['x']=78
    if any((p[0]//16,p[2]//16)!=(4,4) for p in positions(case)):
        raise ValueError('Control source is outside the supported native entity root')
    return case

def layout():
    return {(x,y,z) for x in range(64,81) for z in range(64,79)
            if x in (64,80) or z in (64,78) for y in range(208,211)}

def commands():
    return ['fill 64 208 64 80 210 64 minecraft:stone','fill 64 208 78 80 210 78 minecraft:stone',
            'fill 64 208 65 64 210 77 minecraft:stone','fill 80 208 65 80 210 77 minecraft:stone']

def setup(h,case):
    assert h.a10_profile=='A06' and not h.actions_active and case==expected_case(case['id'])
    targets=[*positions(case),[case['destination'][k] for k in ('x','y','z')]]
    assert not layout().intersection(map(tuple,targets))
    h.console(commands())
    def applied():
        raw=(h.root/'server/snapshot.json').read_bytes();value=json.loads(raw)
        if value['time']<h.activated:return None
        actor=next((a for a in value['actors'] if a['uuid']==h.entities['PlayerA']),None)
        if not actor or not (64<actor['pos'][0]<80 and 64<actor['pos'][2]<78):return None
        if not all('minecraft:stone' in value['blocks'].get(','.join(map(str,p)),'') for p in layout()):return None
        return raw,value,actor
    raw,snapshot,actor=h.wait(applied,'Actual contained fresh two-unit recovery control before work',25)
    assert actor['alive'] and not actor['baby'] and not actor['noAI'] and not actor['controlled']
    path=h.root/'a10-control-fixture-snapshots'/(hashlib.sha256(raw).hexdigest()+'.json')
    path.parent.mkdir(exist_ok=True);path.write_bytes(raw)
    from baseline import native_process
    return {'schema':1,'kind':'ORDINARY_CLOSED_ROOT_FRESH_TWO_UNIT_CONTROL','case':case,
            'entity':h.entities['PlayerA'],'entityChunk':[4,4],'normalAI':True,'blocks':len(layout()),
            'commands':commands(),'destinationFixedBeforeJobCreation':[78,208,74],
            'copiedTemplateDestination':[81,208,74],'setupBlockPlacements':180,'setupActorRelocations':0,
            'nativeSnapshot':path.relative_to(h.root).as_posix(),'nativeSnapshotSha256':hashlib.sha256(raw).hexdigest(),
            'nativeProcess':native_process(h),'nativeJournalEdits':0,'beforeGameplay':True,
            'claim':'Fresh control only, fixed before Job creation. Frozen original tasks and all production guards are unchanged.'}

def review(root,world,case,record):
    assert case==record['case']==expected_case(case['id'])
    assert record['schema']==1 and record['kind']=='ORDINARY_CLOSED_ROOT_FRESH_TWO_UNIT_CONTROL'
    assert record['entityChunk']==[4,4] and record['normalAI'] and record['beforeGameplay']
    assert record['blocks']==record['setupBlockPlacements']==len(layout())==180
    assert record['commands']==commands() and record['setupActorRelocations']==record['nativeJournalEdits']==0
    assert record['destinationFixedBeforeJobCreation']==[78,208,74] and record['copiedTemplateDestination']==[81,208,74]
    path=Path(record['nativeSnapshot']);assert not path.is_absolute() and '..' not in path.parts
    raw=(root/path).read_bytes();assert hashlib.sha256(raw).hexdigest()==record['nativeSnapshotSha256'];snapshot=json.loads(raw)
    actor=next(a for a in snapshot['actors'] if a['uuid']==record['entity'])
    assert actor['alive'] and not actor['baby'] and not actor['noAI'] and not actor['controlled']
    assert 64<actor['pos'][0]<80 and 64<actor['pos'][2]<78
    assert all('minecraft:stone' in snapshot['blocks'][','.join(map(str,p))] for p in layout())
    process=record['nativeProcess']['namespaceProcess'];assert process['kind']=='server'
    assert snapshot['pid']==process['pid'] and snapshot['time']>=process['start']*1000
    from handoff_fixture import native_block
    from review_receipt_prefix import regions,entity_uuid
    cache={}
    for p in layout():assert native_block(world,p,cache)[0]=='minecraft:stone'
    actor=next(e for f in regions(world,'entities') for e in f.get('Entities',[]) if entity_uuid(e)==record['entity'])
    assert actor['id']=='minecraft:villager' and actor['Health']>0 and actor.get('Age',0)>=0
    assert not actor.get('NoAI',0) and not actor.get('NoGravity',0)
    assert (int(actor['Pos'][0])//16,int(actor['Pos'][2])//16)==(4,4)
    return {'status':'PASS_SAVED_NORMAL_AI_FRESH_CONTROL_FIXTURE','blocks':180,'entityChunk':[4,4],
            'destinationFixedBeforeJobCreation':[78,208,74],'originalTwelveUnchanged':True}
