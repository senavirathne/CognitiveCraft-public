#!/usr/bin/env python3
"""Bounded artifact transport. Native outcomes retain their original producer identity."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import urllib.request
import xml.etree.ElementTree as ET

STAGE=Path('build/e2e/parallel-b/B06')
REPOSITORY='senavirathne/CognitiveCraft-public'

def sha(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def load(path):return json.loads(path.read_text())
def write(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')

def validate_native(root,head,tree,run):
    base=root/STAGE;meta=load(base/'metadata.json');result=load(base/'result.json')
    if (meta.get('sourceCommit'),meta.get('sourceTree'),meta.get('dirty'),meta.get('profile'))!=(head,tree,False,'B06'):
        raise ValueError('Foreign, dirty or mismatched native producer')
    if str(meta.get('ci',{}).get('GITHUB_RUN_ID'))!=str(run) or str(meta['ci'].get('GITHUB_RUN_ATTEMPT'))!='1':
        raise ValueError('Native run/attempt mismatch')
    if result.get('status')!='PASS' or len(result.get('rows',[]))!=22:
        raise ValueError('Native diagnostic gate did not complete; collection cannot replace it')
    if sha(root/'fabric/build/libs/ai-villages-0.1.0.jar')!=meta['releaseSha256']:
        raise ValueError('Native production binary mismatch')
    return meta,result

def selected(path):
    parts=path.parts
    return 'game' not in parts and path.as_posix()!='server/observations.jsonl'

def bundle(source,output,head,tree,run,artifact):
    if output.exists():raise ValueError('Refuse to overwrite a review bundle')
    meta,result=validate_native(source,head,tree,run);base=source/STAGE
    original=load(base/'manifest.json');entries={row['path']:row for row in original['files']}
    critical=['metadata.json','result.json','matrix.json','configuration-manifest.json',
              'admission-birch_log.json','admission-oak_planks.json','server/b-guards.jsonl','screenshots.json']
    # Explicitly retain the completed pre-acknowledgement candidate as historical evidence.
    # It is never a substitute for the final producer's required enrollment witness.
    if head!='6c51554f8d3105da83f82df8a1be441a5e04610f':critical.append('enrollment-fixture.json')
    if any(name not in entries for name in critical):raise ValueError('Missing native evidence in original manifest')
    cases=sorted((base/'cases').glob('*.json'))
    if len(cases)!=22:raise ValueError('Missing native trial rows')
    copied=[]
    for name,row in entries.items():
        relative=Path(name)
        if relative.is_absolute() or '..' in relative.parts:raise ValueError('Unsafe native manifest path')
        if not selected(relative):continue
        src=base/relative
        if src.stat().st_size!=row['bytes'] or sha(src)!=row['sha256']:raise ValueError('Native evidence tampered: '+name)
        dst=output/STAGE/relative;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst);copied.append(row)
    full=output/STAGE/'full-native-manifest.json';full.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(base/'manifest.json',full)
    copied.append({'path':'full-native-manifest.json','bytes':full.stat().st_size,'sha256':sha(full)})
    provenance={'nativeSource':head,'nativeTree':tree,'nativeRun':run,'nativeAttempt':1,'nativeArtifact':artifact,
                'collectionSource':os.environ.get('GITHUB_SHA','local'),'gameplayExecutedByCollector':False,
                'omissions':['server/observations.jsonl','native game directories'],
                'omissionReason':'The complete original archive is retained; its repeated global snapshots/world bytes exceed the connector timeout. Original per-trial before/after, native guards, current owner states and all assertions are preserved byte-for-byte.'}
    write(output/STAGE/'manifest.json',{'source':meta,'nativeFullManifestSha256':sha(full),'provenance':provenance,'files':copied})
    modules={}
    for module in ('core','providers','fabric'):
        paths=list((source/module/'build/test-results/test').glob('TEST-*.xml'))
        if not paths:raise ValueError('Missing native producer module results: '+module)
        modules[module]=0
        for path in paths:
            report=ET.parse(path).getroot()
            if any(int(report.get(key,0)) for key in ('failures','errors','skipped')):raise ValueError('Native producer module gate failed')
            modules[module]+=int(report.get('tests',0));target=output/path.relative_to(source);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
    for path in (source/'fabric/build/libs').glob('*.jar'):
        target=output/path.relative_to(source);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
    files=[{'path':str(p.relative_to(output)),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(output.rglob('*')) if p.is_file()]
    write(output/'review-package.json',{'collectionIntegrity':'PASS','nativeDiagnosticResult':result['status'],
          'nativeOutcomeDistribution':result['distribution'],'moduleTests':modules,'provenance':provenance,'files':files})
    return {'collectionIntegrity':'PASS','nativeProducer':head,'nativeRun':run,'files':len(files),
            'moduleTests':modules,'nativeDistribution':result['distribution'],'gameplayExecutedByCollector':False}

def artifact_identity(run,name):
    token=os.environ.get('NATIVE_REVIEW_GITHUB_TOKEN')
    if not token:raise ValueError('Authenticated native artifact metadata is required')
    url=f'https://api.github.com/repos/{REPOSITORY}/actions/runs/{int(run)}/artifacts'
    request=urllib.request.Request(url,headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json'})
    with urllib.request.urlopen(request,timeout=30) as response:artifacts=json.load(response)['artifacts']
    matches=[a for a in artifacts if a['name']==name and not a['expired']]
    if len(matches)!=1:raise ValueError('Missing, expired or ambiguous native archive')
    a=matches[0]
    return {k:a[k] for k in ('id','name','size_in_bytes','digest','created_at','expires_at')}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--head',required=True);p.add_argument('--tree',required=True);p.add_argument('--run',type=int,required=True);p.add_argument('--artifact-name',required=True);a=p.parse_args()
    identity=artifact_identity(a.run,a.artifact_name)
    print(json.dumps(bundle(a.source,a.output,a.head,a.tree,a.run,identity)),flush=True)

if __name__=='__main__':main()
