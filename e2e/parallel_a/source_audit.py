"""Read-only A00 owner/consumer inventory and official native save bytecode capture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
CLASSES=[
    'net.minecraft.server.MinecraftServer', 'net.minecraft.server.level.ServerLevel',
    'net.minecraft.server.level.ServerChunkCache', 'net.minecraft.server.level.ChunkMap',
    'net.minecraft.world.level.chunk.storage.ChunkStorage',
    'net.minecraft.world.level.chunk.storage.EntityStorage',
    'net.minecraft.world.level.entity.PersistentEntitySectionManager',
    'net.minecraft.world.entity.Entity', 'net.minecraft.world.entity.npc.villager.Villager',
    'net.minecraft.world.entity.item.ItemEntity',
    'net.minecraft.world.level.block.entity.BlockEntity', 'net.minecraft.world.level.block.entity.ChestBlockEntity',
    'net.minecraft.world.ContainerHelper', 'net.minecraft.server.players.PlayerList',
    'net.minecraft.world.level.storage.PlayerDataStorage',
    'net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess',
    'net.minecraft.world.entity.npc.villager.AbstractVillager',
    'net.minecraft.world.entity.npc.InventoryCarrier', 'net.minecraft.world.SimpleContainer',
    'net.minecraft.world.level.chunk.storage.SimpleRegionStorage',
    'net.minecraft.world.level.chunk.storage.IOWorker', 'net.minecraft.world.level.chunk.storage.RegionFile',
    'net.minecraft.world.level.chunk.storage.SerializableChunkData',
    'net.minecraft.world.level.chunk.LevelChunk',
    'net.minecraft.world.level.storage.SavedDataStorage', 'net.minecraft.server.commands.SaveAllCommand',
]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out',required=True,type=Path);parser.add_argument('--native',action='store_true')
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    run=lambda *a:subprocess.check_output(a,cwd=ROOT,text=True).strip()
    consumers=[]
    for area in ('core/src/main/java','fabric/src/main/java'):
        for path in sorted((ROOT/area).rglob('*.java')):
            for n,line in enumerate(path.read_text().splitlines(),1):
                if re.search(r'Jobs\.Snapshot|Supplier<Jobs\.Snapshot>|jobs\.snapshot\(|\.snapshot\(\)\.jobs\(|lease[sO]*\.snapshot\(|JobOutcomeSource|KnowledgeAccessSource|retentionRoots\(',line):
                    consumers.append({'path':path.relative_to(ROOT).as_posix(),'line':n,'source':line.strip()})
    source={'commit':run('git','rev-parse','HEAD'),'tree':run('git','rev-parse','HEAD^{tree}'),
        'base':'ea7de8000b2b484b885576c34ed5358274936ce9','schemaPolicy':'no new schema allocated in A00',
        'baselineSchemas':{'Jobs':1,'JobJournal':[0,1,2,3],'ResourceLeaseJournal':1},
        'consumers':consumers,'nativeBytecode':[]}
    if args.native:
        jar=ROOT/'build/server-sdk/minecraft-server.jar'
        source['officialServerSha256']=hashlib.sha256(jar.read_bytes()).hexdigest()
        javap=str(Path(os.environ['JAVA_HOME'])/'bin/javap') if os.environ.get('JAVA_HOME') else 'javap'
        for name in CLASSES:
            result=subprocess.run([javap,'-classpath',str(jar),'-p','-c',name],capture_output=True,text=True)
            target=args.out/(name+'.javap.txt');target.write_text(result.stdout+result.stderr)
            source['nativeBytecode'].append({'class':name,'status':'CAPTURED' if result.returncode==0 else 'UNKNOWN',
                'exit':result.returncode,'path':target.name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
        require={'net.minecraft.server.MinecraftServer','net.minecraft.server.level.ServerLevel','net.minecraft.world.entity.npc.villager.Villager','net.minecraft.world.entity.item.ItemEntity'}
        if not all(r['status']=='CAPTURED' for r in source['nativeBytecode'] if r['class'] in require):
            raise RuntimeError('Required native save owner was not captured; inspect class inventory')
    (args.out/'source-inventory.json').write_text(json.dumps(source,indent=2)+'\n')
    print('PARALLEL_A_SOURCE_INVENTORY '+json.dumps({'commit':source['commit'],'consumers':len(consumers),
          'nativeClasses':len(source['nativeBytecode']),'schemaAllocated':False}))


if __name__=='__main__':main()
