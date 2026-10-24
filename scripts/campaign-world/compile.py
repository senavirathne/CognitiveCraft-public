#!/usr/bin/env python3
"""Compile existing client/observer instruments once, adding the agent-owned native client controls."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from zipfile import ZipFile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'e2e'))
from runtime import SDK,java,digest
release=ROOT/'fabric/build/libs/ai-villages-0.1.0.jar'
libraries=[SDK/'minecraft-client.jar',release,*sorted((SDK/'libraries').rglob('*.jar')),
           *sorted((ROOT/'build/server-sdk/libs').glob('*.jar'))]
for kind in ('client','observer'):
    out=ROOT/'build/e2e-driver'/kind
    if out.exists(): shutil.rmtree(out)
    out.mkdir(parents=True)
    sources=sorted((ROOT/'e2e'/kind/'java').rglob('*.java'))
    if kind=='client': sources+=sorted((ROOT/'e2e/campaigns/world/client/java').rglob('*.java'))
    args=['--release','25','-encoding','UTF-8','-proc:none','-d',str(out),'-cp',os.pathsep.join(map(str,libraries)),*map(str,sources)]
    argfile=out.with_suffix('.args'); argfile.write_text('\n'.join('"'+s.replace('\\','\\\\').replace('"','\\"')+'"' for s in args))
    subprocess.run([java('javac'),'@'+str(argfile)],check=True)
    shutil.copytree(ROOT/'e2e'/kind/'resources',out,dirs_exist_ok=True)
    if kind=='client':
        path=out/'fabric.mod.json'; value=json.loads(path.read_text())
        value['entrypoints']['client'].append('dev.aivillages.e2e.world.WorldClient'); path.write_text(json.dumps(value))
    subprocess.run([java('jar'),'--create','--file',str(out.with_suffix('.jar')),'-C',str(out),'.'],check=True)
with ZipFile(release) as archive:
    if any('e2e' in name.lower() for name in archive.namelist()): raise RuntimeError('Test instrumentation entered the production JAR')
print('Compiled isolated campaign instruments; frozen production JAR SHA-256='+digest(release))
