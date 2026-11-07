"""Read-only native W04 setup oracle; commands sent are not proof of applied geometry."""
from native_nbt import chunk
from review_receipt_prefix import dimension_root, item, regions, entity_uuid


def w04_start(world, case, subject):
    root=dimension_root(world);cache={}
    def native(pos):
        x,y,z=pos;key=x//16,z//16
        if key not in cache:
            cache[key]=chunk(root/'region'/f'r.{x//512}.{z//512}.mca',*key)
        data=cache[key];assert data is not None,('Missing setup chunk',key)
        states=next(s for s in data['sections'] if s['Y']==y//16)['block_states'];palette=states['palette']
        index=((y&15)<<8)|((z&15)<<4)|(x&15)
        if len(palette)==1:entry=palette[0]
        else:
            bits=max(4,(len(palette)-1).bit_length());per=64//bits
            entry=palette[(states['data'][index//per]&((1<<64)-1))>>((index%per)*bits)&((1<<bits)-1)]
        if isinstance(entry,str):
            name=entry.split('[',1)[0]
            props=dict(v.split('=') for v in entry.split('[',1)[1].rstrip(']').split(',')) if '[' in entry else {}
        else:name=entry.get('id',entry.get('Name',entry.get('')));props=entry.get('properties',entry.get('Properties',{}))
        return name,props
    expected={}
    for x in range(63,86):
        for z in range(63,80):
            expected[x,207,z]=('minecraft:stone',{})
            expected[x,214,z]=('minecraft:glass',{})
            for y in range(208,214):
                expected[x,y,z]=('minecraft:glass' if x in (63,85) or z in (63,79) else 'minecraft:air',{})
    for x,y,z in case['positions']:
        props={'age':'7'} if case['item']=='minecraft:wheat' else {'axis':case['logAxis']}
        expected[x,y,z]=(case['item'],props)
        if case['item']=='minecraft:wheat':expected[x,y-1,z]=('minecraft:farmland',{})
    for x,y,z in case.get('immature',[]):
        expected[x,y,z]=('minecraft:wheat',{'age':'3'});expected[x,y-1,z]=('minecraft:farmland',{})
    for pos in case.get('standingSupports',[]):expected[tuple(pos)]=('minecraft:stone',{})
    if case['terrain']=='corridor':
        for z in [*range(65,71),*range(72,78)]:
            for y in (208,209):expected[69,y,z]=('minecraft:stone',{})
    elif case['terrain']=='detour':
        for z in range(67,75):
            for y in (208,209):expected[69,y,z]=('minecraft:stone',{})
    expected[tuple(case['destination'])]=('minecraft:'+case['container'],{})
    for pos in case['unrelated']:expected[tuple(pos)]=(case['nearbyUnrelated'],{})
    for pos,(name,props) in expected.items():
        actual,properties=native(pos)
        assert actual==name,('Frozen W04 geometry differs',case['id'],pos,name,actual)
        for key,value in props.items():
            # Vanilla's native palette can omit default y for a log.
            assert properties.get(key,'y' if key=='axis' else None)==value,(case['id'],pos,key,value,properties)
    x,_,z=case['destination'];data=cache[x//16,z//16]
    boxes=[e for e in data.get('block_entities',[]) if [e.get(k) for k in ('x','y','z')]==case['destination']]
    assert len(boxes)==1 and boxes[0]['id']=='minecraft:'+case['container'],('Frozen W04 native container absent',case['id'])
    assert sorted(item(e) for e in boxes[0].get('Items',[]))==sorted((slot,name,count,{}) for slot,name,count in case['destinationSlots']),('Frozen W04 destination slots differ',case['id'])
    actors=[e for frame in regions(world,'entities') for e in frame.get('Entities',[]) if entity_uuid(e)==subject]
    assert len(actors)==1 and actors[0]['id']=='minecraft:villager',('Frozen W04 subject absent',case['id'])
    actor=actors[0]
    assert not actor.get('NoAI',0) and actor.get('Age',0)>=0 and actor.get('Health',0)>0,('W04 requires a living normal-AI adult',case['id'])
    inventory=actor.get('Inventory',[])
    assert len(inventory)==case['actorOccupiedSlots'] and all(item(e)[1:]==('minecraft:cobblestone',64,{}) for e in inventory),('Frozen W04 actor inventory differs',case['id'])
    return {'status':'PASS_EXACT_NATIVE_W04_SETUP','case':case['id'],'subject':subject,'normalAI':True,'actorOccupiedSlotsChecked':len(inventory),'blocksChecked':len(expected),'chunksChecked':len(cache),'destinationSlotsChecked':len(case['destinationSlots'])}
