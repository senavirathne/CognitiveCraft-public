"""Read-only extraction of exact installed-server storage/serialization hook bytecode."""
import argparse,hashlib,json,re
from pathlib import Path

SELECT={
 'net.minecraft.world.level.chunk.storage.EntityStorage':None,
 'net.minecraft.world.level.entity.PersistentEntitySectionManager':['storeChunkSections','processChunkUnload','storeAllChunks','saveAll','unloadEntity'],
 'net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback':None,
 'net.minecraft.world.entity.Entity':['setPosRaw','setLevelCallback','remove(','load('],
 'net.minecraft.world.level.entity.ChunkEntities':None,
 'net.minecraft.world.level.chunk.storage.SimpleRegionStorage':['write('],
 'net.minecraft.world.level.chunk.storage.IOWorker':['store('],
 'net.minecraft.nbt.CompoundTag':[],
 'net.minecraft.nbt.NbtUtils':[],
 'net.minecraft.core.UUIDUtil':[]
}
def sections(text):
    lines=text.splitlines();starts=[i for i,line in enumerate(lines) if re.match(r'^  (public|private|protected) .*\(',line)]
    return [(lines[a],lines[a:(starts[n+1] if n+1<len(starts) else len(lines))]) for n,a in enumerate(starts)]
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);a=p.parse_args()
    inventory=json.loads((a.root/'source-inventory.json').read_text());indexed={v['class']:v for v in inventory['nativeBytecode']}
    result={'schema':1,'source':inventory['commit'],'tree':inventory['tree'],'serverSha256':inventory['officialServerSha256'],
            'nativeEffects':0,'classes':[],'claim':'Exact hook inventory only; no pickup/owner/native storage pass.'}
    required={'net.minecraft.world.level.chunk.storage.EntityStorage','net.minecraft.world.entity.Entity','net.minecraft.world.level.entity.ChunkEntities'}
    for name,wanted in SELECT.items():
        row=indexed.get(name)
        if row is None or row['status']!='CAPTURED':
            if name in required:raise AssertionError(('Missing required native hook owner',name))
            result['classes'].append({'class':name,'status':'UNKNOWN'});continue
        path=a.root/row['path'];raw=path.read_bytes();assert hashlib.sha256(raw).hexdigest()==row['sha256']
        text=raw.decode();parts=sections(text)
        selected=[{'signature':signature,'bytecode':'\n'.join(body)} for signature,body in parts if wanted is None or any(pattern in signature for pattern in wanted)]
        fields=[line for line in text.splitlines() if re.match(r'^  (public|private|protected) ',line) and '(' not in line]
        value={'class':name,'status':'CAPTURED','sha256':row['sha256'],'fields':fields,
               'signatures':[signature for signature,body in parts],'methods':selected}
        if name in required:assert parts,('No native methods captured',name)
        result['classes'].append(value)
        print('A06_STORAGE_HOOK_BYTECODE '+json.dumps(value),flush=True)
    (a.root/'a06-storage-hook-audit.json').write_text(json.dumps(result,indent=2)+'\n')
if __name__=='__main__':main()
