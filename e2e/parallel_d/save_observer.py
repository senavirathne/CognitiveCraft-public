"""Observe original native region bytes. These observations never establish A settlement."""
import base64,hashlib
from pathlib import Path
from common import require,write,read,sha
from native_nbt import decode

def chunk(world,kind,x,z,out):
    paths=list(Path(world).glob(f'**/dimensions/minecraft/overworld/{kind}/r.{x//32}.{z//32}.mca'))
    require(len(paths)==1,f'one actual native region {kind}/{x}/{z}');p=paths[0];data=p.read_bytes();pos=((x%32)+(z%32)*32)*4;entry=data[pos:pos+4];sector=int.from_bytes(entry[:3],'big');count=entry[3]
    require(sector>=2 and count>0,'native chunk allocated');raw=data[sector*4096:(sector+count)*4096];n=int.from_bytes(raw[:4],'big');require(1<n<=len(raw)-4 and raw[4]==2,'native chunk compression');raw=raw[:n+4]
    row={'chunk_x':x,'chunk_z':z,'native_region_path':str(p.relative_to(world)),'region_sha256':sha(p),'native_chunk_base64':base64.b64encode(raw).decode(),'native_chunk_sha256':hashlib.sha256(raw).hexdigest()};write(out,row);native=decode(out)
    if kind=='region':require([native['xPos'],native['zPos']]==[x,z],'native saved block chunk coordinates')
    else:
        import struct
        pos=native['Position'];pos=list(struct.unpack('>ii',pos))if isinstance(pos,bytes)else pos;require(pos==[x,z],'native saved entity chunk coordinates')
    return native

def uuid(value):
    import uuid as u,struct
    if isinstance(value,bytes):return str(u.UUID(bytes=value))
    if isinstance(value,list):return str(u.UUID(bytes=struct.pack('>4I',*[x&0xffffffff for x in value])))
    return str(value)

def inventory(row,item):return sum(x.get('count',x.get('Count',0)) for x in row.get('Inventory',row.get('Items',[])) if x.get('id')==item)

def observe(world,descriptor,output):
    output=Path(output);output.mkdir(parents=True,exist_ok=False);d=read(descriptor) if isinstance(descriptor,(str,Path)) else descriptor;result={}
    for name,case in d['participants'].items():
        counts=[];roles={}
        for role in (('source','carrier','destination') if name=='courier' else ('sender','recipient')):
            p=case[role];pos=p['pos'];kind='entities' if 'entity' in p else 'region';f=output/f'{name}-{role}.json';nbt=chunk(Path(world),kind,int(pos[0])>>4,int(pos[2])>>4,f)
            if kind=='entities':candidates=[e for e in nbt.get('Entities',[]) if uuid(e['UUID'])==p['entity']]
            else:candidates=[e for e in nbt.get('block_entities',[]) if [e.get(k) for k in ('x','y','z')]==pos]
            require(len(candidates)==1,f'actual saved participant {name}/{role}');native=candidates[0];count=inventory(native,case['item']);counts.append(count);roles[role]={'count':count,'native_nbt_identity':{k:uuid(native[k]) if k=='UUID' else native[k] for k in ('id','UUID','x','y','z') if k in native},'native_marker':native.get('cognitivecraft_storage_v1'),'witness':f.name,'witness_sha256':sha(f)}
        result[name]={'vector':counts,'roles':roles}
    value={'classification':'UNKNOWN_NATIVE_PARTICIPANT_PERSISTENCE','scope':'ACTUAL_NATIVE_DISK_OBSERVATIONS_ONLY','integration_root':None,'effect_id':None,'save_proof':None,'participants':result};write(output/'distributions.json',value);return value
