#!/usr/bin/env python3
"""Emit bounded raw native evidence projections for review without a local runtime."""
import argparse
import hashlib
import json
from pathlib import Path
from review_bundle import STAGE,load,sha

def emit(name,value):
    raw=json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True)
    pieces=[raw[i:i+16000] for i in range(0,len(raw),16000)]
    print('B06_AUDIT_START',name,hashlib.sha256(raw.encode()).hexdigest(),len(raw),len(pieces),flush=True)
    for i,piece in enumerate(pieces):print('B06_AUDIT_PART',name,i,piece,flush=True)
    print('B06_AUDIT_END',name,flush=True)

def owner(o):
    return {k:o.get(k) for k in ('id','state','reason','generation')} | {
        'attempts':[{k:a.get(k) for k in ('id','generation','terminal','uncertain')} for a in o.get('attempts',[])]}

def owners(data):
    out={}
    for player,d in data.items():
        jobs={j['id']:j for j in d.get('jobs',{}).values()}
        for run in d.get('runs',{}).values():
            if run.get('responsibility') is not None:jobs[run['responsibility']['id']]=run['responsibility']
        out[player]={'uuid':d.get('uuid'),'online':d.get('online'),
            'runs':[{'id':r.get('id'),'phase':r.get('phase'),'responsibility':r.get('responsibility',{}).get('id')} for r in d.get('runs',{}).values()],
            'owners':[owner(o) for o in jobs.values()]}
    return out

def frame(s):
    points={f'{x},208,66' for x in range(70,77)} | {'74,208,69','77,208,72','10004,208,10004'}
    return {k:s.get(k) for k in ('time','gameTick','pid','actors','containers','drops')} | {
        'resourceBlocks':{k:v for k,v in s.get('blocks',{}).items() if k in points},
        'playerNativeState':{p:{k:d.get(k) for k in ('uuid','pos','online','assessmentInventory')} for p,d in s.get('knownPlayers',{}).items()},
        'livePlayers':{p:{k:d.get(k) for k in ('uuid','pos','alive')} for p,d in s.get('players',{}).items()}}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--label',required=True);a=p.parse_args()
    if a.label not in ('final','prior'):raise ValueError('Explicit review label required')
    base=a.root/STAGE;package=load(a.root/'review-package.json')
    header={'metadata':load(base/'metadata.json'),'matrix':load(base/'matrix.json'),'result':load(base/'result.json'),
        'provenance':package['provenance'],'moduleTests':package['moduleTests'],'collectionIntegrity':package['collectionIntegrity'],
        'actualProductionSha256':sha(a.root/'fabric/build/libs/ai-villages-0.1.0.jar'),
        'nativeManifestSha256':sha(base/'full-native-manifest.json'),'reviewManifestSha256':sha(base/'manifest.json'),
        'models':load(base/'models.json'),'recipeFilesSha256':sha(base/'recipe-files.json')}
    if (base/'enrollment-fixture.json').exists():
        e=load(base/'enrollment-fixture.json');header['enrollment']={'before':frame(e['before']),'subjects':[
            {k:r[k] for k in ('player','entity','citizen')} | {'nativeWitness':frame(r['nativeWitness'])} for r in e['subjects']]}
    emit(a.label+'.header',header)
    for i,path in enumerate(sorted((base/'cases').glob('*.json'))):
        row=load(path);row['rawFileSha256']=sha(path)
        row['before']=frame(row['before']);row['after']=frame(row['after'])
        settlement=row['settlement'];acks=settlement.get('cancellationAcknowledgements',[])
        settlement['cancellationAcknowledgements']=[{k:r[k] for k in ('player','job','feedback')} | {'before':owner(r['before'])} for r in acks]
        if 'nativeSnapshotAfterSettlement' in settlement:
            s=settlement.pop('nativeSnapshotAfterSettlement');settlement['nativeParticipants']=frame(s);settlement['nativeOwners']=owners(s['knownPlayers'])
        else:settlement['nativeOwners']=owners(settlement.pop('nativeOwnerProjection'))
        emit(f'{a.label}.case-{i:03d}',row)
    emit(a.label+'.guards',[json.loads(line) for line in (base/'server/b-guards.jsonl').read_text().splitlines()])

if __name__=='__main__':main()
