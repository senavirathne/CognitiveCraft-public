#!/usr/bin/env python3
"""Stage one lossless evidence artifact; record every excluded runtime file explicitly."""
import hashlib
import json
import os
from pathlib import Path
import shutil

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'build/e2e/world-evidence'

def exclusion(relative):
    parts=relative.parts; name=relative.name
    if name=='core' or name.startswith('core.') or name.endswith('.hprof'):
        return 'Native memory dump; runtime stdout, crash report and error logs retained'
    if 'game' in parts:
        tail=parts[parts.index('game')+1:]
        if tail and tail[0] in ('assets','libraries','versions','natives','mods','.fabric','cache'):
            return 'Reproducible Minecraft/Fabric runtime cache; binary hashes retained in process metadata'
    if parts and parts[0].startswith('saved-game-') and parts[1:2] and parts[1] in ('mods','.fabric','cache','libraries','versions'):
        return 'Reproducible cache from an isolated prior fixture; native world and logs retained'
    return None

def stage():
    if OUT.exists(): raise RuntimeError('Evidence staging must not overwrite a previous artifact')
    OUT.mkdir(parents=True)
    manifest={'schema':1,'integrity':'Every retained file is byte-identical and SHA-256 recorded; no lossy log/frame sampling',
              'retained':[],'excluded':[]}
    roots=[('generalization-world',ROOT/'build/e2e/generalization-world'),
           ('previous-world',ROOT/'build/e2e/previous-world')]
    for label,directory in roots:
        if not directory.exists(): continue
        for source in sorted(directory.rglob('*')):
            if not source.is_file(): continue
            if source.is_symlink(): raise RuntimeError('Unexpected evidence symlink')
            relative=source.relative_to(directory); reason=exclusion(relative)
            row={'path':label+'/'+str(relative),'bytes':source.stat().st_size}
            if reason:
                row['reason']=reason; manifest['excluded'].append(row); continue
            row['sha256']=hashlib.file_digest(source.open('rb'),'sha256').hexdigest()
            target=OUT/label/relative; target.parent.mkdir(parents=True,exist_ok=True)
            os.link(source,target); manifest['retained'].append(row)
    for source in [ROOT/'fabric/build/libs/ai-villages-0.1.0.jar',ROOT/'build/e2e-driver/client.jar',ROOT/'build/e2e-driver/observer.jar']:
        if source.exists():
            target=OUT/'binaries'/source.name; target.parent.mkdir(exist_ok=True)
            os.link(source,target)
            manifest['retained'].append({'path':'binaries/'+source.name,'bytes':source.stat().st_size,
                'sha256':hashlib.file_digest(source.open('rb'),'sha256').hexdigest()})
    (OUT/'artifact-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('WORLD_ARTIFACT_MANIFEST '+json.dumps({'retainedFiles':len(manifest['retained']),
        'retainedBytes':sum(r['bytes'] for r in manifest['retained']),
        'excludedFiles':len(manifest['excluded']),'excludedBytes':sum(r['bytes'] for r in manifest['excluded'])}),flush=True)

if __name__=='__main__': stage()
