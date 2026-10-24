"""Reuse only an intact native saved world from this campaign's completed Actions artifact."""
import hashlib
import json
from pathlib import Path
import shutil

from runtime import digest,semantic_jar,ROOT
from suite import atomic,completed_jsonl,read,require
from generalization_support import Inconclusive


def find_runtime(directory):
    candidates=[p.parent for p in Path(directory).rglob('metadata.json')
                if (p.parent/'admissions.json').exists() and (p.parent/'server/game/world/level.dat').exists()]
    require(len(candidates)==1,'Exactly one genuine previous campaign runtime required')
    return candidates[0]


def restore(h,directory):
    source=find_runtime(directory); metadata=read(source/'metadata.json')
    require(metadata['sourceCommit']=='1ae31f09114e41edb61f41a9dede1b0a040b5269','Unexpected previous production/test provenance')
    jars=[p for p in Path(directory).rglob('ai-villages-0.1.0.jar') if digest(p)==metadata['releaseSha256']]
    require(bool(jars),'Previous production JAR provenance mismatch')
    require(semantic_jar(jars[0])==semantic_jar(),'Previous saved world cannot cross a production code change')
    previous=read(source/'results.json')
    require(previous and previous['baseline']=='ea7de8000b2b484b885576c34ed5358274936ce9','Saved-world baseline mismatch')
    # The completed artifact comes after Harness.finish; require explicit native server stop evidence as well.
    docker_log=(source/'docker.log').read_text(errors='replace')
    require('Stopped' in docker_log and 'minecraft' in docker_log,'Previous native server stop was not witnessed')
    enrollment=next(e['data']['feedback'] for e in completed_jsonl(source/'supervisor.jsonl')
                    if e['kind']=='command' and e['data'].get('player')=='PlayerA'
                    and e['data'].get('text')=='/aivillage kernel enroll')
    import re
    citizen=re.search(r'actor=([0-9a-f-]{36})',enrollment).group(1)
    entity=re.search(r'entity=([0-9a-f-]{36})',enrollment).group(1)
    world=source/'server/game/world'; destination=h.root/'server/game/world'
    files=[dict(path=str(p.relative_to(world)),sha256=digest(p),bytes=p.stat().st_size)
           for p in sorted(world.rglob('*')) if p.is_file() and p.name!='session.lock']
    shutil.copytree(world,destination,ignore=shutil.ignore_patterns('session.lock'))
    require(all(digest(destination/f['path'])==f['sha256'] for f in files),'Copied native save changed bytes')
    h.citizens['PlayerA']=citizen; h.entities['PlayerA']=entity
    h.admissions=read(source/'admissions.json')
    for admission in h.admissions:
        if admission['status']=='PASS':
            cap={'minecraft:wheat':'deliver_wheat','minecraft:oak_log':'deliver_oak_logs','minecraft:birch_log':'deliver_birch_logs'}[admission['item']]
            h.hashes['cognitivecraft:'+cap]=admission['view']['marker']['artifactSha256']
    proof=dict(accepted=False,previousSource=metadata['sourceCommit'],previousRun=40076986301,
        previousJarSha256=metadata['releaseSha256'],currentJarSha256=digest(ROOT/'fabric/build/libs/ai-villages-0.1.0.jar'),
        semanticJarSha256=semantic_jar(),citizen=citizen,entity=entity,files=files,artifacts=h.hashes,
        boundary='Native saved data only; no synthesized admission, skill, actor or resource results')
    atomic(h.root/'restored-world.json',proof)
    h.start('server'); h.start('PlayerA')
    h.ensure_client()
    h.console(['op PlayerA','tp PlayerA 66.5 208 69.5'])
    h.cleanup_world()
    h.console(['tp '+entity+' 66.5 208 66.5'])
    h.wait(lambda:any(a['uuid']==entity and a['alive'] for a in h.snapshot()['actors']),
           'Original live native citizen restored with same identity',75)
    h.wait(lambda:citizen in h.command('PlayerA','/aivillage kernel citizens','citizens='),
           'Original citizen scope recovered through the connected client',40)
    h.command('PlayerA','/aivillage kernel inference false','admission=false')
    h.cleanup_world()
    atomic(h.root/'previous-admissions.json',h.admissions)
    proof['accepted']=True; atomic(h.root/'restored-world.json',proof)
    h.event('genuine-saved-world-reuse',{'sourceRun':40076986301,'sourceCommit':metadata['sourceCommit'],
                                      'citizen':citizen,'entity':entity,'methods':h.hashes})
