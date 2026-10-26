#!/usr/bin/env python3
"""Copy bounded original evidence and verify exact producer identity in the producing job."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from suite import ROOT,atomic,read,require
from runtime import digest


def bundle(base,output,retain_failed=False):
    if output.exists():raise ValueError('Retain previous review package')
    meta=read(base/'metadata.json');result=read(base/'result.json')
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    tree=subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip()
    require((meta['sourceCommit'],meta['sourceTree'],meta['dirty'])==(head,tree,False),'Foreign/dirty native producer')
    require(result['status']=='PASS' or retain_failed,'Failed native evidence requires explicit retention mode')
    manifest=read(base/'manifest.json');require(manifest['source']==meta,'Native metadata envelope mismatch')
    copied=[]
    for row in manifest['files']:
        relative=Path(row['path'])
        require(not relative.is_absolute() and '..' not in relative.parts,'Unsafe native path')
        if 'game' in relative.parts or relative.as_posix()=='server/observations.jsonl':continue
        src=base/relative
        require(src.stat().st_size==row['bytes'] and digest(src)==row['sha256'],'Original evidence integrity failure')
        dst=output/base.name/relative;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst);copied.append(row)
    # The post-run audit is read-only and explicitly outside the original manifest.
    if (base/'resource-acquisition-audit.json').exists():
        shutil.copy2(base/'resource-acquisition-audit.json',output/base.name/'resource-acquisition-audit.json')
    shutil.copy2(base/'manifest.json',output/base.name/'full-native-manifest.json')
    modules={}
    for module in ('core','providers','fabric'):
        paths=sorted((ROOT/module/'build/test-results/test').glob('TEST-*.xml'));require(paths,'Missing module test results')
        total=0
        for p in paths:
            xml=ET.parse(p).getroot();require(not int(xml.get('failures','0')) and not int(xml.get('errors','0')),'Failed module gate')
            total+=int(xml.get('tests','0'));dst=output/module/p.name;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dst)
        modules[module]=total
    jar=ROOT/'fabric/build/libs/ai-villages-0.1.0.jar';require(digest(jar)==meta['releaseSha256'],'Native binary mismatch')
    shutil.copy2(jar,output/jar.name)
    report={'schema':1,'nativeSource':head,'nativeTree':tree,'nativeRun':os.environ.get('GITHUB_RUN_ID'),
        'nativeAttempt':os.environ.get('GITHUB_RUN_ATTEMPT'),'releaseSha256':digest(jar),'modules':modules,
        'nativeResult':result,'originalManifestSha256':digest(base/'manifest.json'),'copiedOriginalFiles':copied,
        'omitted':['native game directories','server/observations.jsonl'],
        'fullArchiveRetained':True,'collectionIsGameplay':False,'nativeGameplayQualified':result['status']=='PASS'}
    atomic(output/'review-package.json',report)
    print('NATIVE_STAGE_REVIEW',json.dumps({k:report[k] for k in ('nativeSource','nativeTree','releaseSha256','modules','nativeResult')}),flush=True)
    for p in sorted((base/'cases').glob('*.json')):
        row=read(p)
        if 'proof' in row:print('B07_NATIVE_PROOF',json.dumps({'path':p.name,'sha256':digest(p),'proof':row['proof'],'oracle':row['oracle']}),flush=True)
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--retain-failed',action='store_true')
    a=p.parse_args();bundle(a.root,a.output,a.retain_failed)


if __name__=='__main__':main()
