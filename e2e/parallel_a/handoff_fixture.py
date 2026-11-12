"""Ordinary static geometry keeps the three A07 handoffs in the supported entity root."""
import copy
from native_nbt import chunk
from recovery_cases import originals
from review_receipt_prefix import dimension_root,regions,entity_uuid

def expected_case(kind):
    from lifecycle_cases import HANDOFF_CASES
    from pathlib import Path
    assert kind in HANDOFF_CASES
    case=copy.deepcopy(next(c for c in originals(Path(__file__).with_name('a06_original_cases.json')) if c['checkpoint']=='DEPOSIT'))
    case.update(id=kind,requested=2,item='minecraft:oak_log');case['capability']['name']='cognitivecraft:deliver_oak_logs'
    # Only this fresh A07 control uses an inside-root destination. The twelve
    # frozen A06 originals and every already-created Job keep their destinations.
    case['destination']['x']=78
    return case

def layout():
    cells={(x,y,z):('minecraft:stone',{}) for x in range(64,81) for z in range(64,81)
           if x in (64,80) or z in (64,80) for y in range(208,211)}
    assert len(cells)==192
    return cells

def setup(h,case):
    assert case==expected_case(case['id']),'Changed original A07 handoff task'
    commands=['fill 64 208 64 80 210 64 minecraft:stone','fill 64 208 80 80 210 80 minecraft:stone',
              'fill 64 208 65 64 210 79 minecraft:stone','fill 80 208 65 80 210 79 minecraft:stone']
    h.console(commands)
    return {'schema':2,'kind':'ORDINARY_STATIC_SAME_ROOT_HANDOFF_ARENA','case':case,'blocks':len(layout()),
            'entityChunk':[4,4],'destinationFixedBeforeJobCreation':[78,208,74],'copiedTemplateDestination':[81,208,74],'normalAI':True,
            'commands':commands,'geometry':'Closed ordinary perimeter; complete paths and both source/destination inside one entity root',
            'metadataRepaired':False,'nativeGameplayQualification':'REQUIRED_FRESH_CONNECTED_RUN'}

def native_block(world,pos,cache):
    x,y,z=pos;key=x//16,z//16
    if key not in cache:cache[key]=chunk(dimension_root(world)/'region'/f'r.{x//512}.{z//512}.mca',*key)
    data=cache[key];assert data is not None
    states=next(s for s in data['sections'] if s['Y']==y//16)['block_states'];palette=states['palette']
    index=((y&15)<<8)|((z&15)<<4)|(x&15)
    if len(palette)==1:entry=palette[0]
    else:
        bits=max(4,(len(palette)-1).bit_length());per=64//bits
        entry=palette[(states['data'][index//per]&((1<<64)-1))>>((index%per)*bits)&((1<<bits)-1)]
    if isinstance(entry,str):
        name=entry.split('[',1)[0];properties=dict(v.split('=') for v in entry.split('[',1)[1].rstrip(']').split(',')) if '[' in entry else {}
    else:
        assert isinstance(entry,dict)
        if set(entry)=={''}:name,properties=entry[''],{}
        elif set(entry)=={'id','properties'}:name,properties=entry['id'],entry['properties']
        else:assert set(entry)<={'Name','Properties'} and 'Name' in entry;name,properties=entry['Name'],entry.get('Properties',{})
    assert isinstance(name,str) and isinstance(properties,dict) and all(isinstance(v,str) for v in properties.values())
    return name,properties

def review(world,case,old,successor,record):
    assert case==expected_case(case['id']) and record['case']==case
    assert record['schema']==2 and record['kind']=='ORDINARY_STATIC_SAME_ROOT_HANDOFF_ARENA'
    assert record['entityChunk']==[4,4] and record['blocks']==192 and record['normalAI'] is True and record['metadataRepaired'] is False
    assert record['destinationFixedBeforeJobCreation']==[78,208,74] and record['copiedTemplateDestination']==[81,208,74]
    cache={}
    for pos,(name,properties) in layout().items():
        actual,props=native_block(world,pos,cache);assert actual==name,(pos,name,actual)
        assert all(props.get(k)==v for k,v in properties.items()),(pos,properties,props)
    native={entity_uuid(e):e for frame in regions(world,'entities') for e in frame.get('Entities',[])}
    assert old!=successor
    for id in (old,successor):
        actor=native[id];assert actor['id']=='minecraft:villager' and not actor.get('NoAI',0) and actor.get('Age',0)>=0 and actor['Health']>0
        assert [int(actor['Pos'][0])//16,int(actor['Pos'][2])//16]==[4,4],('Handoff actor left the supported root',id,actor['Pos'])
    return {'status':'PASS_NATIVE_STATIC_HANDOFF_FIXTURE','blocksChecked':192,'chunksChecked':len(cache),
            'normalAdultActorsChecked':2,'entityChunk':[4,4],'originalTaskUnchanged':True,'metadataRepaired':False}
