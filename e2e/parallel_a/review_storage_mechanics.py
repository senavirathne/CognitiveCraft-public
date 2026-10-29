"""Independent native storage controls; no original pickup settlement inferred."""
import hashlib,json
from native_nbt import chunk
TAG='cognitivecraft_entity_removals'

def validate(records,proof):
    assert proof['status']=='PASS_NATIVE_STORAGE_MECHANICS_ONLY'
    assert proof['nativePickupProducer']=='INACTIVE' and proof['originalOwnerSettlements']==0
    assert proof['emptyRemoval']=='PASS_NATIVE_CLOSE_REOPEN_AND_TWO_EMPTY_SAVES'
    assert proof['opaqueMetadata']=='PASS_EXACT_INT_77_PRESERVED' and proof['contradiction']=='PASS_PERMANENT_NATIVE_QUARANTINE'
    for pos,raw in zip(((4,4),(5,4),(6,4)),records):
        assert raw is not None and raw['Position']==list(pos) and raw['Entities']==[] and isinstance(raw['DataVersion'],int)
    first,unknown,conflict=records
    metadata=json.loads(first[TAG]);assert metadata['schema']==1 and metadata['dimension']=='minecraft:overworld'
    assert (metadata['chunkX'],metadata['chunkZ'])==(4,4) and len(metadata['removals'])==1
    row=metadata['removals'][0];expected=proof['removal'];assert row['entity']==expected['entity']==proof['initialEntity']
    assert row['incarnation']==expected['incarnation'] and row['revision']==expected['revision']
    marker=json.loads(row['marker']);assert marker['schema']==1 and marker['phase']=='RESULT'
    assert {k:marker[k] for k in ('effect','intent','phase','result')}==expected['marker']
    assert unknown[TAG]==77 and not isinstance(unknown[TAG],bool)
    assert json.loads(conflict[TAG])=={'quarantined':True,'schema':0}
    original=json.loads(conflict[TAG+'_original'][TAG]);assert original['schema']==1 and len(original['removals'])==1
    assert original['removals'][0]['entity']==proof['conflictingEntity']
    movement=proof['movement'];assert [(c['dimension'],c['x'],c['z']) for c in movement['chunks']]==[('minecraft:overworld',x,4) for x in (4,5,6)]
    assert movement['current']=={'dimension':'minecraft:overworld','x':4,'z':4} and proof['changedUuid']=='PASS_GAP'
    return {'status':'PASS_INDEPENDENT_NATIVE_STORAGE_MECHANICS_ONLY','nativePickupProducer':'INACTIVE','originalOwnerSettlements':0,
            'emptyRecord':'PASS_EXACT_NATIVE_POSITION_MEMBERSHIP_AND_REMOVAL','unknownTag':'PASS_INT_77',
            'contradiction':'PASS_PERMANENT_QUARANTINE','nativeMovement':'PASS_THREE_SET_POS_RAW_CHUNKS_AND_UUID_GAP'}

def review(root):
    proof=json.loads((root/'a06-storage-mechanics.json').read_text());assert proof['directory']=='storage-mechanics/entities'
    directory=root/proof['directory'];region=directory/'r.0.0.mca'
    result=validate([chunk(region,x,4) for x in (4,5,6)],proof)
    result['nativeFiles']={p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(directory.rglob('*')) if p.is_file()}
    assert result['nativeFiles'];return result
