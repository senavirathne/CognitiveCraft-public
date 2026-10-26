#!/usr/bin/env python3
"""Isolated real Minecraft host integration. No synthetic player or Jobs success claim."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile,ZIP_DEFLATED

ROOT=Path(__file__).resolve().parents[2]


def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--built',action='store_true')
    parser.add_argument('--entrypoint',action='append')
    args=parser.parse_args();root=args.root.resolve();root.mkdir(parents=True,exist_ok=False)
    spec=importlib.util.spec_from_file_location('cc_parallel_b_headless',ROOT/'scripts/headless.py')
    headless=importlib.util.module_from_spec(spec);spec.loader.exec_module(headless)
    runtime=headless.prepare() if args.built else headless.build()[0]
    release=ROOT/'fabric/build/libs/ai-villages-0.1.0.jar';tests=ROOT/'fabric/build/libs/ai-villages-0.1.0-gametest.jar'
    production_hash=sha(release)
    with ZipFile(runtime[0]) as native:
        version=json.loads(native.read('version.json'))
        if version['id']!='26.3':raise ValueError('Native recipe host runtime changed')
        installed_data={name:hashlib.sha256(native.read(name)).hexdigest() for name in [
            'data/minecraft/recipe/oak_planks.json','data/minecraft/recipe/birch_planks.json',
            'data/minecraft/recipe/stick.json','data/minecraft/recipe/crafting_table.json','data/minecraft/recipe/wooden_axe.json',
            'data/minecraft/tags/item/planks.json','data/minecraft/tags/item/wooden_tool_materials.json']}
    with ZipFile(release) as z:
        if any('gametest' in n.lower() or 'e2e' in n.lower() for n in z.namelist()):raise ValueError('Test code in production JAR')
    entries=args.entrypoint or ['dev.aivillages.fabric.GeneralizedRecipeChoiceGameTests','dev.aivillages.fabric.ConcretePlankChoiceGameTests',
                               'dev.aivillages.fabric.BirchPlankEffectGameTests','dev.aivillages.fabric.OakPlankEffectGameTests']
    metadata={'schema':1,'nativeHostOnly':True,'connectedClients':0,'autonomousGameplay':False,
              'runtimeAuthority':'Explicit bounded test-scope authority plus actual native gateway control; no authoritative Job/receipt',
              'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
              'sourceTree':subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip(),
              'dirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)),
              'productionSha256':production_hash,'originalTestJarSha256':sha(tests),'minecraft':'26.3','loader':'0.19.5',
              'dependencyLockSha256':sha(ROOT/'scripts/dependencies.json'),'nativeVersion':version,
              'installedRecipeDataSha256':installed_data,
              'nativeDependencies':[{'file':str(p.relative_to(headless.SDK)),'sha256':sha(p)} for p in [*runtime,headless.SDK/'fabric-api.jar',headless.SDK/'gametest.jar']],
              'javaVersion':subprocess.run([headless.executable('java'),'-version'],capture_output=True,text=True,check=True).stderr,
              'ci':{k:os.environ[k] for k in ['GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_SHA','GITHUB_REF_NAME'] if k in os.environ},'phases':[]}
    (root/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
    for index,entry in enumerate(entries):
        phase=root/f'{index:02d}-{entry.rsplit(".",1)[1]}';game=phase/'game';mods=game/'mods';mods.mkdir(parents=True)
        report=phase/'native-results.xml';evidence=phase/'evidence';evidence.mkdir()
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        (game/'server.properties').write_text(f'online-mode=false\nserver-ip=127.0.0.1\nserver-port={port}\n')
        selected=mods/tests.name
        with ZipFile(tests) as source,ZipFile(selected,'w',ZIP_DEFLATED) as target:
            for name in source.namelist():
                data=source.read(name)
                if name=='fabric.mod.json':
                    value=json.loads(data);value['entrypoints']['fabric-gametest']=[entry];data=json.dumps(value).encode()
                target.writestr(name,data)
        import shutil
        for path in [headless.SDK/'fabric-api.jar',headless.SDK/'gametest.jar',release]:shutil.copy2(path,mods/path.name)
        command=[headless.executable('java'),'-Xms256M','-Xmx2G','-Dfabric-api.gametest',
                 '-Dfabric-api.gametest.report-file='+str(report),'-Dcognitivecraft.parallelB.evidence='+str(evidence),
                 '-cp',os.pathsep.join(map(str,runtime)),'net.fabricmc.loader.impl.launch.knot.KnotServer','nogui']
        identity={'entrypoint':entry,'command':command,'port':port,'testJarSha256':sha(selected),'result':'NOT_EXECUTED'}
        metadata['phases'].append(identity);(root/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
        with (phase/'stdout.log').open('w') as output:
            result=subprocess.run(command,cwd=game,stdout=output,stderr=subprocess.STDOUT,timeout=500)
        cases=[c for c in ET.parse(report).getroot().iter('testcase') if c.get('name','').startswith('ai_villages_tests:')] if report.exists() else []
        passed=result.returncode==0 and bool(cases) and not any(c.find(t) is not None for c in cases for t in ['failure','error','skipped'])
        identity.update(result='PASS' if passed else 'FAIL',exit=result.returncode,nativeCases=len(cases),report=str(report.relative_to(root)))
        (root/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
        if not passed:raise RuntimeError('Real native host phase failed: '+entry+'; '+str(phase/'stdout.log'))
        if sha(release)!=production_hash:raise ValueError('Host qualification changed the production JAR')
        print(entry,'PASS',len(cases),'actual Minecraft test(s)',flush=True)
    files=[{'path':str(p.relative_to(root)),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(root.rglob('*')) if p.is_file()]
    (root/'manifest.json').write_text(json.dumps({'source':metadata,'files':files},indent=2)+'\n')
    print('NATIVE_HOST_QUALIFICATION PASS; connected-client/autonomous/recovery claims=false',flush=True)


if __name__=='__main__':main()
